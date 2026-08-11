package io.reliabilityai.gateway.dataplane.governance.domain;

import io.reliabilityai.gateway.common.Preconditions;
import io.reliabilityai.gateway.dataplane.governance.api.Decision;
import io.reliabilityai.gateway.dataplane.governance.api.DenialReason;
import io.reliabilityai.gateway.dataplane.governance.api.GovernanceRequest;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeSet;

/**
 * The pure decision core (GV-D12): a deterministic function of {@code (request, policy,
 * entitlement, usage, now)} with no I/O, no wall clock, no randomness and no mutable state. Given
 * identical inputs it always returns the identical verdict and the identical explanation, which is
 * what makes a governance decision replayable during a compliance audit.
 *
 * <p>Evaluation follows the frozen precedence hierarchy with <b>deny-overrides</b> (GV-D2): any
 * domain that denies denies the whole request, and a permit requires every domain to permit. Order
 * therefore never changes the outcome — only which reason is reported (the highest-precedence
 * binding denial) and how quickly the evaluation short-circuits.
 *
 * <pre>
 *   1 security  2 tenant  3 compliance  4 residency
 *   5 authorization (model, capability, tool)  6 feature flags  7 quota/rate  8 budget
 * </pre>
 *
 * <p>Approval is evaluated <em>last</em>, only once every hard domain has permitted — a request
 * that would be denied anyway must never be sent to a human for approval.
 */
public final class GovernanceEvaluator {

  private final Duration usageStalenessTolerance;

  /**
   * Creates the evaluator.
   *
   * @param usageStalenessTolerance how old a usage reading may be and still be enforced against;
   *     beyond this the engine denies rather than admit unbounded spend (GV-D5/GV-D6)
   */
  public GovernanceEvaluator(final Duration usageStalenessTolerance) {
    Preconditions.requireNonNull(usageStalenessTolerance, "usageStalenessTolerance");
    if (usageStalenessTolerance.isNegative()) {
      throw new IllegalArgumentException("usageStalenessTolerance must not be negative");
    }
    this.usageStalenessTolerance = usageStalenessTolerance;
  }

  /**
   * Evaluates one admission question.
   *
   * @param request the neutral admission question
   * @param policy the tenant's resolved policy set, empty when unresolvable
   * @param entitlement the tenant's ceilings, empty when unavailable
   * @param usage the tenant's usage reading, empty when unavailable
   * @param flagStates the resolved flag state per gated feature the request depends on
   * @param now the injected-clock instant
   * @return the verdict
   */
  public Decision evaluate(
      final GovernanceRequest request,
      final Optional<PolicySet> policy,
      final Optional<Entitlement> entitlement,
      final Optional<UsageState> usage,
      final Map<String, Optional<Boolean>> flagStates,
      final Instant now) {
    Preconditions.requireNonNull(request, "request");
    Preconditions.requireNonNull(policy, "policy");
    Preconditions.requireNonNull(entitlement, "entitlement");
    Preconditions.requireNonNull(usage, "usage");
    Preconditions.requireNonNull(flagStates, "flagStates");
    Preconditions.requireNonNull(now, "now");

    // --- 1/2 security + tenant: no policy at all is the fail-closed case, never "unrestricted".
    // ---
    if (policy.isEmpty()) {
      return deny(DenialReason.POLICY_NOT_FOUND, "no policy snapshot for tenant", Map.of());
    }
    final PolicySet policySet = policy.orElseThrow();
    final Map<String, String> versions = versions(policySet, entitlement);

    if (!policySet.tenantEnabled()) {
      return deny(DenialReason.TENANT_DISABLED, "tenant disabled by policy", versions);
    }

    // --- 3 compliance: a regime the tenant is not attested for can never be satisfied. ---
    for (final String regime : sorted(request.requiredComplianceRegimes())) {
      if (!policySet.complianceRegimes().contains(regime)) {
        return deny(DenialReason.COMPLIANCE_CONFLICT, "regime unattested", versions);
      }
    }

    // --- 4 residency: hard domain, never downgraded (GV-D9). ---
    if (!policySet.allowedRegions().contains(request.requestedRegion().value())) {
      return deny(DenialReason.REGION_NOT_ALLOWED, "region outside residency scope", versions);
    }

    // --- 5 authorization: model, then capabilities, then tools. ---
    if (!policySet.allowedModels().contains(request.requestedModel())) {
      return deny(DenialReason.MODEL_NOT_ALLOWED, "model not permitted for tenant", versions);
    }
    for (final String capability : sorted(request.requestedCapabilities())) {
      if (!policySet.allowedCapabilities().contains(capability)) {
        return deny(DenialReason.UNAUTHORIZED, "capability not permitted", versions);
      }
    }
    for (final String tool : sorted(request.requestedTools())) {
      if (!policySet.allowedTools().contains(tool)) {
        return deny(DenialReason.TOOL_UNAUTHORIZED, "tool not permitted", versions);
      }
    }

    // --- 6 feature flags: absent or off both fail safe to off; a flag can never open a hard gate.
    // ---
    for (final String feature : sorted(request.requestedFeatures())) {
      if (!policySet.gatedFeatures().contains(feature)) {
        continue; // ungated features need no flag
      }
      final Optional<Boolean> state = flagStates.getOrDefault(feature, Optional.empty());
      if (state.isEmpty() || !state.orElseThrow()) {
        return deny(DenialReason.FEATURE_DISABLED, "gated feature not enabled", versions);
      }
    }

    // --- 7/8 quota, rate and budget: unenforceable ceilings deny rather than admit blind. ---
    if (entitlement.isEmpty() || usage.isEmpty()) {
      return deny(DenialReason.POLICY_UNAVAILABLE, "entitlement or usage unavailable", versions);
    }
    final Entitlement limits = entitlement.orElseThrow();
    final UsageState state = usage.orElseThrow();

    if (state.asOf().plus(usageStalenessTolerance).isBefore(now)) {
      return deny(
          DenialReason.POLICY_UNAVAILABLE, "usage reading stale beyond tolerance", versions);
    }
    if (state.requestsUsed() >= policySet.maxRequestsPerWindow()) {
      return deny(DenialReason.RATE_LIMITED, "request-rate ceiling reached", versions);
    }
    if (state.requestsUsed() >= limits.quotaLimit()) {
      return deny(DenialReason.QUOTA_EXCEEDED, "quota ceiling reached", versions);
    }
    // Strictly greater-than: a request landing exactly on the ceiling is still within budget.
    if (state.spentMicros() + request.projectedSpendMicros() > limits.budgetLimitMicros()) {
      return deny(DenialReason.BUDGET_EXCEEDED, "budget ceiling would be exceeded", versions);
    }

    // --- approval: evaluated only after every hard domain permitted. ---
    final List<String> awaiting = new ArrayList<>();
    for (final String capability : sorted(request.requestedCapabilities())) {
      if (policySet.approvalRequiredCapabilities().contains(capability)) {
        awaiting.add(capability);
      }
    }
    if (!awaiting.isEmpty()) {
      return new Decision.RequireApproval(List.copyOf(awaiting), versions);
    }

    return new Decision.Permit(
        new ResolvedGovernanceContext(
            sorted(policySet.allowedRegions()),
            sorted(policySet.complianceRegimes()),
            sorted(request.requestedCapabilities()),
            sorted(request.requestedTools()),
            Math.max(0L, limits.quotaLimit() - state.requestsUsed()),
            Math.max(0L, limits.budgetLimitMicros() - state.spentMicros())),
        versions);
  }

  private static Decision deny(
      final DenialReason reason, final String explanation, final Map<String, String> versions) {
    return new Decision.Deny(reason, explanation, versions);
  }

  /** Snapshot versions recorded on every decision so it can be replayed exactly (GV-D12). */
  private static Map<String, String> versions(
      final PolicySet policy, final Optional<Entitlement> entitlement) {
    final Map<String, String> versions = new LinkedHashMap<>();
    versions.put("policy", policy.version().name() + ":" + policy.version().version());
    entitlement.ifPresent(
        held ->
            versions.put("entitlement", held.version().name() + ":" + held.version().version()));
    return Map.copyOf(versions);
  }

  /** Sorted copy — set iteration order must never leak into a decision or its explanation. */
  private static List<String> sorted(final java.util.Set<String> values) {
    return List.copyOf(new TreeSet<>(values));
  }
}

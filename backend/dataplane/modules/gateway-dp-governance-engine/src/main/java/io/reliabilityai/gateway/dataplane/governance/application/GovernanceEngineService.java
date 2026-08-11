package io.reliabilityai.gateway.dataplane.governance.application;

import io.reliabilityai.gateway.canonical.context.PrincipalContext;
import io.reliabilityai.gateway.canonical.context.RequestContext;
import io.reliabilityai.gateway.canonical.context.TenantContext;
import io.reliabilityai.gateway.canonical.decision.GovernanceDecision;
import io.reliabilityai.gateway.common.Preconditions;
import io.reliabilityai.gateway.dataplane.governance.api.AuditSinkPort;
import io.reliabilityai.gateway.dataplane.governance.api.Decision;
import io.reliabilityai.gateway.dataplane.governance.api.DenialReason;
import io.reliabilityai.gateway.dataplane.governance.api.EntitlementSnapshotPort;
import io.reliabilityai.gateway.dataplane.governance.api.FeatureFlagSnapshotPort;
import io.reliabilityai.gateway.dataplane.governance.api.GovernanceDecisionRecord;
import io.reliabilityai.gateway.dataplane.governance.api.GovernanceEnginePort;
import io.reliabilityai.gateway.dataplane.governance.api.GovernanceRequest;
import io.reliabilityai.gateway.dataplane.governance.api.PolicySnapshotPort;
import io.reliabilityai.gateway.dataplane.governance.api.UsageStatePort;
import io.reliabilityai.gateway.dataplane.governance.domain.Entitlement;
import io.reliabilityai.gateway.dataplane.governance.domain.GovernanceEvaluator;
import io.reliabilityai.gateway.dataplane.governance.domain.PolicySet;
import io.reliabilityai.gateway.dataplane.governance.domain.UsageState;
import io.reliabilityai.gateway.ports.ClockPort;
import io.reliabilityai.gateway.ports.GovernancePort;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * The Policy Enforcement Point (Doc 21, C4, AD-019): resolves the tenant's cached snapshots, runs
 * the pure evaluator, emits the audit fact, and returns the verdict.
 *
 * <p>This class does the <em>impure</em> half — snapshot reads, clock read, audit emission — and
 * keeps every policy judgement inside {@link GovernanceEvaluator}, which is a pure function. That
 * split is what makes the decision logic exhaustively testable without stubbing time or I/O.
 *
 * <p><b>Fail closed everywhere.</b> A snapshot that will not resolve, a usage feed that has gone
 * stale, or an unexpected exception anywhere in resolution all produce a DENY. There is no path
 * through this class that admits a request it could not fully evaluate (Doc 21 §42).
 *
 * <p>It implements two inbound contracts. {@link GovernanceEnginePort#govern} is the full engine,
 * taking the complete admission question. {@link GovernancePort#authorize} is the frozen canonical
 * façade the pipeline calls; see {@link #authorize} for what that narrower signature can and cannot
 * decide.
 */
public final class GovernanceEngineService implements GovernanceEnginePort, GovernancePort {

  private final PolicySnapshotPort policySnapshots;
  private final EntitlementSnapshotPort entitlementSnapshots;
  private final UsageStatePort usageStates;
  private final FeatureFlagSnapshotPort featureFlags;
  private final AuditSinkPort audit;
  private final GovernanceEvaluator evaluator;
  private final ClockPort clock;

  /**
   * Creates the engine with explicit collaborators.
   *
   * @param policySnapshots the cached tenant policy snapshot reader
   * @param entitlementSnapshots the cached quota/budget ceiling reader
   * @param usageStates the bounded-staleness usage counter reader
   * @param featureFlags the cached flag-state reader
   * @param audit the governance audit sink
   * @param evaluator the pure decision core
   * @param clock the injected clock — the only source of time
   */
  public GovernanceEngineService(
      final PolicySnapshotPort policySnapshots,
      final EntitlementSnapshotPort entitlementSnapshots,
      final UsageStatePort usageStates,
      final FeatureFlagSnapshotPort featureFlags,
      final AuditSinkPort audit,
      final GovernanceEvaluator evaluator,
      final ClockPort clock) {
    this.policySnapshots = Preconditions.requireNonNull(policySnapshots, "policySnapshots");
    this.entitlementSnapshots =
        Preconditions.requireNonNull(entitlementSnapshots, "entitlementSnapshots");
    this.usageStates = Preconditions.requireNonNull(usageStates, "usageStates");
    this.featureFlags = Preconditions.requireNonNull(featureFlags, "featureFlags");
    this.audit = Preconditions.requireNonNull(audit, "audit");
    this.evaluator = Preconditions.requireNonNull(evaluator, "evaluator");
    this.clock = Preconditions.requireNonNull(clock, "clock");
  }

  @Override
  public Decision govern(final GovernanceRequest request) {
    Preconditions.requireNonNull(request, "request");
    final Decision decision = decide(request);
    emitAudit(request, decision);
    return decision;
  }

  /**
   * The frozen canonical façade (AD-002). The pipeline calls this between authentication and
   * routing.
   *
   * <p><b>Scope limit, stated plainly:</b> {@code GovernancePort.authorize} is a frozen contract
   * that carries only request, principal and tenant context — it has no field for the requested
   * model, capabilities or tools. This façade therefore enforces every domain those inputs support
   * (policy resolution, tenant status, compliance, residency, quota, rate, budget) and cannot
   * enforce the model/capability/tool authorization domains, which require {@link #govern}. Rather
   * than silently permitting what it cannot see, the resolved context it returns as obligations
   * names exactly the scope it did resolve, and a caller needing model-level authorization must use
   * {@code govern}.
   *
   * @param requestContext correlation, idempotency, causation and region
   * @param principal the authenticated principal
   * @param tenant the resolved tenant scope
   * @return the canonical permit/deny decision with its reason
   */
  @Override
  public GovernanceDecision authorize(
      final RequestContext requestContext,
      final PrincipalContext principal,
      final TenantContext tenant) {
    Preconditions.requireNonNull(requestContext, "requestContext");
    Preconditions.requireNonNull(principal, "principal");
    Preconditions.requireNonNull(tenant, "tenant");

    final Optional<PolicySet> policy = resolvePolicy(tenant);
    // The model/capability/tool domains are unrepresentable in this frozen signature, so they are
    // supplied empty: an empty allow-list check trivially passes and is not a hidden permit.
    final GovernanceRequest request =
        new GovernanceRequest(
            requestContext,
            principal,
            tenant,
            policy.map(set -> firstAllowedModel(set)).orElse(ANY_MODEL),
            requestContext.region(),
            java.util.Set.of(),
            java.util.Set.of(),
            java.util.Set.of(),
            java.util.Set.of(),
            0L);

    final Decision decision = decide(request);
    emitAudit(request, decision);
    return toCanonical(decision);
  }

  /** Resolution + evaluation, with every failure collapsing to a fail-closed denial. */
  private Decision decide(final GovernanceRequest request) {
    try {
      final Optional<PolicySet> policy = resolvePolicy(request.tenant());
      final Optional<Entitlement> entitlement =
          entitlementSnapshots.entitlementFor(request.tenant().tenantScope());
      final Optional<UsageState> usage = usageStates.usageFor(request.tenant().tenantScope());

      final Map<String, Optional<Boolean>> flagStates = new LinkedHashMap<>();
      for (final String feature : request.requestedFeatures()) {
        flagStates.put(feature, featureFlags.enabled(request.tenant().tenantScope(), feature));
      }

      return evaluator.evaluate(request, policy, entitlement, usage, flagStates, clock.now());
    } catch (final RuntimeException resolutionFailure) {
      // A snapshot reader that throws tells us nothing about whether this request is allowed.
      return new Decision.Deny(
          DenialReason.POLICY_UNAVAILABLE, "policy resolution failed", Map.of());
    }
  }

  private Optional<PolicySet> resolvePolicy(final TenantContext tenant) {
    return policySnapshots.policyFor(tenant.tenantScope());
  }

  /**
   * Emits the audit fact for every outcome, permits included. An audit sink failure is swallowed:
   * it must never change a decision the engine has already made.
   */
  private void emitAudit(final GovernanceRequest request, final Decision decision) {
    try {
      audit.record(
          new GovernanceDecisionRecord(
              request.requestContext().correlationId().value() + ":gov",
              request.requestContext().correlationId(),
              request.tenant().tenantScope(),
              outcomeOf(decision),
              bindingOf(decision),
              decision.policyVersions(),
              clock.now()));
    } catch (final RuntimeException auditFailure) {
      // deliberately ignored — see method javadoc
    }
  }

  private static String outcomeOf(final Decision decision) {
    if (decision instanceof Decision.Permit) {
      return "PERMIT";
    }
    if (decision instanceof Decision.RequireApproval) {
      return "REQUIRE_APPROVAL";
    }
    return ((Decision.Deny) decision).reason().code();
  }

  private static String bindingOf(final Decision decision) {
    if (decision instanceof Decision.Deny denied) {
      return denied.reason().name();
    }
    if (decision instanceof Decision.RequireApproval) {
      return "APPROVAL";
    }
    return "none";
  }

  /** Maps the rich verdict onto the frozen canonical decision record. */
  private static GovernanceDecision toCanonical(final Decision decision) {
    if (decision instanceof Decision.Permit permit) {
      return new GovernanceDecision(true, permit.context().residencyScope(), "permitted");
    }
    if (decision instanceof Decision.RequireApproval approval) {
      return new GovernanceDecision(false, approval.awaitingCapabilities(), "require-approval");
    }
    final Decision.Deny denied = (Decision.Deny) decision;
    return new GovernanceDecision(false, List.of(), denied.reason().code());
  }

  /**
   * Placeholder model for the canonical façade, which carries no requested model. It is never
   * compared against a real allow-list: {@link #firstAllowedModel} substitutes a model the tenant
   * already holds, so the model domain is a no-op on this path rather than a spurious denial.
   */
  private static final io.reliabilityai.gateway.canonical.identity.CanonicalModelId ANY_MODEL =
      new io.reliabilityai.gateway.canonical.identity.CanonicalModelId("unspecified");

  private static io.reliabilityai.gateway.canonical.identity.CanonicalModelId firstAllowedModel(
      final PolicySet policy) {
    return policy.allowedModels().stream()
        .min(
            java.util.Comparator.comparing(
                io.reliabilityai.gateway.canonical.identity.CanonicalModelId::value))
        .orElse(ANY_MODEL);
  }
}

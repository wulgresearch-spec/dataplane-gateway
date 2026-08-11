package io.reliabilityai.gateway.dataplane.governance.application;

import io.reliabilityai.gateway.canonical.context.PrincipalContext;
import io.reliabilityai.gateway.canonical.context.RequestContext;
import io.reliabilityai.gateway.canonical.context.TenantContext;
import io.reliabilityai.gateway.canonical.decision.GovernanceDecision;
import io.reliabilityai.gateway.common.Preconditions;
import io.reliabilityai.gateway.dataplane.governance.api.DenialReason;
import io.reliabilityai.gateway.dataplane.governance.api.GovernanceAdmissionPort;
import io.reliabilityai.gateway.dataplane.governance.api.PolicyAudit;
import io.reliabilityai.gateway.dataplane.governance.api.PolicyAuditEvent;
import io.reliabilityai.gateway.dataplane.governance.api.PolicyDecision;
import io.reliabilityai.gateway.dataplane.governance.api.PolicyMetrics;
import io.reliabilityai.gateway.dataplane.governance.api.PolicyRequest;
import io.reliabilityai.gateway.dataplane.governance.api.PolicyScope;
import io.reliabilityai.gateway.dataplane.governance.api.PolicyScopeRef;
import io.reliabilityai.gateway.dataplane.governance.api.PolicyUsage;
import io.reliabilityai.gateway.dataplane.governance.api.PolicyUsagePort;
import io.reliabilityai.gateway.dataplane.governance.api.PolicyVersion;
import io.reliabilityai.gateway.dataplane.governance.api.PolicyViolation;
import io.reliabilityai.gateway.dataplane.governance.api.ResolvedPolicyContext;
import io.reliabilityai.gateway.dataplane.governance.api.ScopeChain;
import io.reliabilityai.gateway.dataplane.governance.api.TickerPort;
import io.reliabilityai.gateway.dataplane.governance.api.Verdict;
import io.reliabilityai.gateway.dataplane.governance.domain.EffectivePolicy;
import io.reliabilityai.gateway.dataplane.governance.domain.PolicyEvaluator;
import io.reliabilityai.gateway.dataplane.governance.domain.PolicySnapshot;
import io.reliabilityai.gateway.dataplane.governance.domain.UsageLookup;
import io.reliabilityai.gateway.dataplane.governance.internal.PolicyLoader;
import io.reliabilityai.gateway.dataplane.governance.internal.PolicyRegistry;
import io.reliabilityai.gateway.ports.ClockPort;
import io.reliabilityai.gateway.ports.GovernancePort;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * The Policy Enforcement Point: the non-bypassable admission gate that runs between authentication
 * and routing (Doc 21, C4, AD-018/AD-019).
 *
 * <p>The split of responsibility here is the same one that makes the module testable. This class
 * does the impure half — resolve the generation in force, read the clock, read consumption
 * counters, emit the audit fact, move the counters — and delegates every judgement to {@link
 * PolicyEvaluator}, which is a pure function. Nothing about whether a request is admitted depends
 * on anything in this file.
 *
 * <p><b>It cannot throw.</b> Every path through {@link #govern} ends in a {@link PolicyDecision},
 * including the paths where the clock misbehaves, the snapshot source has never delivered anything,
 * or a collaborator throws something nobody anticipated. An admission gate that can propagate an
 * exception has a path that ends somewhere other than a decision, and whoever catches it has to
 * guess — which is the silent-permit failure GV-INV exists to rule out. The guess is made here
 * instead, once, in the only safe direction.
 *
 * <p><b>Nothing is admitted silently.</b> Two of the four verdicts admit the request, but both
 * carry their violations into the decision, into the caller's obligations, and into the audit
 * trail. An advisory breach and a shadow rule leave exactly as much evidence as a refusal does.
 *
 * <p><b>An unconfigured gate refuses.</b> "Policy says nothing about this request" and "no policy
 * has ever reached this node" are different answers, and only the first is legitimate. A node that
 * has never received a generation refuses everything rather than treating an empty policy set as
 * permission — the difference between a gateway with no restrictions and a gateway with no gate.
 */
public final class GovernanceEngine implements GovernancePort, GovernanceAdmissionPort {

  private final PolicyRegistry registry;
  private final PolicyEvaluator evaluator;
  private final PolicyUsagePort usage;
  private final PolicyAudit audit;
  private final PolicyMetrics metrics;
  private final ClockPort clock;
  private final TickerPort ticker;
  private final PolicyLoader loader;

  /**
   * Creates the engine with explicit collaborators; nothing is discovered or located.
   *
   * @param registry the read path onto the generation in force
   * @param evaluator the pure decision core
   * @param usage the bounded-staleness consumption reader
   * @param audit the decision audit sink
   * @param metrics the counters to publish to
   * @param clock the injected clock — the only source of wall time
   * @param ticker the injected monotonic ticker used to measure evaluation cost
   * @param loader the reload path, or null when this node's policy is installed directly
   */
  public GovernanceEngine(
      final PolicyRegistry registry,
      final PolicyEvaluator evaluator,
      final PolicyUsagePort usage,
      final PolicyAudit audit,
      final PolicyMetrics metrics,
      final ClockPort clock,
      final TickerPort ticker,
      final PolicyLoader loader) {
    this.registry = Preconditions.requireNonNull(registry, "registry");
    this.evaluator = Preconditions.requireNonNull(evaluator, "evaluator");
    this.usage = Preconditions.requireNonNull(usage, "usage");
    this.audit = Preconditions.requireNonNull(audit, "audit");
    this.metrics = Preconditions.requireNonNull(metrics, "metrics");
    this.clock = Preconditions.requireNonNull(clock, "clock");
    this.ticker = Preconditions.requireNonNull(ticker, "ticker");
    this.loader = loader;
  }

  /**
   * Decides whether a request is admitted.
   *
   * @param request the governance question
   * @return the decision — never null, and this method never throws
   */
  public PolicyDecision govern(final PolicyRequest request) {
    Preconditions.requireNonNull(request, "request");
    final Instant now = readClock();
    final PolicyDecision decision = decide(request, now);
    publish(request, decision, now, false);
    return decision;
  }

  /**
   * The admission seam the pipeline calls. Identical to {@link #govern(PolicyRequest)}; the
   * separate name exists so the pipeline can depend on the narrow {@link GovernanceAdmissionPort}
   * rather than on this class, which is what lets a deployment swap the engine for the legacy
   * evaluator behind an adapter without the pipeline changing.
   *
   * @param request the full admission question
   * @return the decision — never null, and this method never throws
   */
  @Override
  public PolicyDecision admit(final PolicyRequest request) {
    return govern(request);
  }

  /**
   * The frozen canonical façade the pipeline calls between authentication and routing.
   *
   * <p><b>Scope limit, stated plainly.</b> {@code GovernancePort.authorize} carries only request,
   * principal and tenant context. It has no field for the model, the tools, the token counts or the
   * projected spend, so on this path those attributes are absent and the policy types that compare
   * against them have nothing to compare — they contribute no violation. What this façade
   * <em>does</em> enforce is everything the frozen signature can express: kill switches, tenant
   * suspension, maintenance windows, residency, and every consumption-based cap that depends on
   * counters rather than on request contents.
   *
   * <p><b>Quantitative policies refuse on this path.</b> Token, cost and provider policies depend
   * on facts this signature cannot carry, so on this path those quantities are <em>unknown</em> —
   * and an unknown quantity makes a mandatory ceiling unenforceable, which refuses (Doc 21 §42). A
   * deployment that authors a budget cap and still calls this façade will see every request refused
   * with {@code policy-unavailable}, which is the correct and visible failure: the alternative is a
   * budget cap that silently never fires. Model and tool policies, whose inputs are absent rather
   * than unknown, contribute no violation as before.
   *
   * <p>The alternative would be to substitute placeholder values for the missing attributes, which
   * would either refuse every request or admit past a real allow-list. Enforcing exactly what is
   * visible, and saying so, is the honest option. A caller that needs the full policy set must use
   * {@link #admit(PolicyRequest)} and supply the request's actual shape.
   *
   * @param requestContext correlation, idempotency, causation and region
   * @param principal the authenticated principal
   * @param tenant the resolved tenant scope
   * @return the canonical permit/deny decision with its obligations
   */
  @Override
  public GovernanceDecision authorize(
      final RequestContext requestContext,
      final PrincipalContext principal,
      final TenantContext tenant) {
    Preconditions.requireNonNull(requestContext, "requestContext");
    Preconditions.requireNonNull(principal, "principal");
    Preconditions.requireNonNull(tenant, "tenant");

    final PolicyRequest request =
        PolicyRequest.builder()
            .requestContext(requestContext)
            .principal(principal)
            .tenant(tenant)
            .scopeChain(ScopeChain.forTenant(tenant.tenantScope()))
            .region(requestContext.region())
            .build();
    final PolicyDecision decision = govern(request);
    return new GovernanceDecision(decision.admits(), obligations(decision), decision.reasonCode());
  }

  /**
   * Fetches, compiles and installs the current generation without restarting anything (Doc 21 §47).
   *
   * @return {@code true} when a new generation was put in force
   */
  public boolean reload() {
    return loader != null && loader.reload();
  }

  /**
   * Restores a generation this node previously served.
   *
   * @param version the generation to restore
   * @return {@code true} when restored
   */
  public boolean rollbackTo(final PolicyVersion version) {
    return registry.rollbackTo(version).isPresent();
  }

  /**
   * The generation currently in force, recorded on every decision it makes.
   *
   * @return the current policy version
   */
  public PolicyVersion currentVersion() {
    return registry.currentVersion();
  }

  /**
   * The read path onto the generation in force, for the simulation engine and for composition.
   *
   * @return the policy registry
   */
  public PolicyRegistry registry() {
    return registry;
  }

  /** Resolution plus evaluation, with every failure collapsing to a refusal. */
  private PolicyDecision decide(final PolicyRequest request, final Instant now) {
    try {
      if (!registry.isInstalled()) {
        // No generation has ever reached this node. An empty policy set would evaluate to "no
        // constraints", which for an admission gate is indistinguishable from not being there.
        metrics.policyUnavailable();
        return PolicyDecision.failClosed(
            DenialReason.POLICY_NOT_FOUND, PolicyVersion.NONE, 0L, now);
      }
      final EffectivePolicy policy = registry.effectiveFor(request.scopeChain());
      return evaluator.evaluate(request, policy, memoisedUsage(), now, ticker);
    } catch (final RuntimeException failure) {
      // A collaborator that throws has told us nothing about whether this request is within policy.
      metrics.policyUnavailable();
      return PolicyDecision.failClosed(
          DenialReason.POLICY_UNAVAILABLE, currentVersionOrNone(), 0L, now);
    }
  }

  /**
   * Evaluates a request against an arbitrary generation without any side effect — no audit under
   * the enforcement identity, no counters, nothing installed. The simulation engine's entry point.
   *
   * @param request the governance question
   * @param snapshot the generation to evaluate against
   * @param now the instant to evaluate as of
   * @return the decision this generation would have produced
   */
  PolicyDecision evaluateAgainst(
      final PolicyRequest request, final PolicySnapshot snapshot, final Instant now) {
    try {
      return evaluator.evaluate(
          request, snapshot.effectiveFor(request.scopeChain()), memoisedUsage(), now, ticker);
    } catch (final RuntimeException failure) {
      return PolicyDecision.failClosed(
          DenialReason.POLICY_UNAVAILABLE, snapshot.version(), 0L, now);
    }
  }

  /**
   * A per-request memo over the consumption port.
   *
   * <p>A chain can mention the same node more than once across several ceilings — a project with
   * both a requests-per-minute and a daily-budget cap reads the same counter twice — and a
   * governance evaluation that reads the coordination tier repeatedly for one request is paying the
   * same cost several times for an answer that cannot change mid-decision. Memoising also makes the
   * evaluation internally consistent: every ceiling sees the same reading, so two caps cannot
   * disagree about how much has been consumed.
   *
   * <p>The map is per-call and never escapes, so it needs no synchronisation and shares nothing
   * across requests (AD-021).
   */
  private UsageLookup memoisedUsage() {
    final Map<PolicyScopeRef, Optional<PolicyUsage>> seen = new HashMap<>(4);
    return scope -> seen.computeIfAbsent(scope, this::readUsage);
  }

  private Optional<PolicyUsage> readUsage(final PolicyScopeRef scope) {
    try {
      final Optional<PolicyUsage> reading = usage.usageFor(scope);
      return reading == null ? Optional.empty() : reading;
    } catch (final RuntimeException unavailable) {
      // Unknown consumption, which the evaluator treats as a cap it cannot enforce.
      return Optional.empty();
    }
  }

  private Instant readClock() {
    try {
      final Instant now = clock.now();
      return now == null ? Instant.EPOCH : now;
    } catch (final RuntimeException broken) {
      // A refusal stamped at the epoch is obviously wrong to a reader; an admission would not be.
      return Instant.EPOCH;
    }
  }

  private PolicyVersion currentVersionOrNone() {
    try {
      return registry.currentVersion();
    } catch (final RuntimeException unavailable) {
      return PolicyVersion.NONE;
    }
  }

  /**
   * Records the decision. Audit and metrics failures are contained; neither may change a verdict.
   */
  private void publish(
      final PolicyRequest request,
      final PolicyDecision decision,
      final Instant now,
      final boolean simulated) {
    try {
      metrics.decision(decision.verdict(), decision.latencyNanos());
      decision
          .binding()
          .ifPresent(violation -> metrics.binding(violation.type().domain(), decision.verdict()));
      if (DenialReason.POLICY_UNAVAILABLE.code().equals(decision.reasonCode())) {
        metrics.staleUsage();
      }
    } catch (final RuntimeException ignored) {
      // A metrics backend must never be able to refuse traffic.
    }
    try {
      audit.record(auditEvent(request, decision, now, simulated));
    } catch (final RuntimeException ignored) {
      // Losing an audit write is bad; letting an audit outage decide admissions is worse.
    }
  }

  private PolicyAuditEvent auditEvent(
      final PolicyRequest request,
      final PolicyDecision decision,
      final Instant now,
      final boolean simulated) {
    final List<String> ruleIds =
        decision.violations().stream().map(PolicyViolation::ruleId).toList();
    return new PolicyAuditEvent(
        request.requestContext().correlationId().value() + ":gov",
        request.requestContext().correlationId(),
        request.tenant().tenantScope(),
        nodeId(request.scopeChain(), PolicyScope.API_KEY),
        request.principal().principalId().value(),
        decision.verdict(),
        decision.reasonCode(),
        ruleIds,
        decision.policyVersion(),
        decision.latencyNanos(),
        simulated,
        now);
  }

  private static String nodeId(final ScopeChain chain, final PolicyScope scope) {
    for (final PolicyScopeRef ref : chain.refs()) {
      if (ref.scope() == scope) {
        return ref.id();
      }
    }
    return PolicyAuditEvent.ABSENT;
  }

  /**
   * Turns a decision into the obligations the frozen canonical decision carries forward.
   *
   * <p>These are the resolved constraints the Router must honour, plus an explicit marker for every
   * violation that did not refuse the request. Surfacing the non-binding violations is what keeps
   * an advisory breach from being invisible to everything except the audit stream (Doc 21 §28.3).
   */
  private static List<String> obligations(final PolicyDecision decision) {
    if (!decision.admits()) {
      return List.of();
    }
    final ResolvedPolicyContext context = decision.context();
    final List<String> obligations = new ArrayList<>();
    context.residencyScope().forEach(region -> obligations.add("residency:" + region));
    context.complianceRegimes().forEach(regime -> obligations.add("compliance:" + regime));
    context.allowedProviders().forEach(provider -> obligations.add("provider:" + provider));
    if (context.streamingRequired()) {
      obligations.add("require:streaming");
    }
    if (context.jsonModeRequired()) {
      obligations.add("require:json-mode");
    }
    for (final PolicyViolation violation : decision.violations()) {
      obligations.add(prefixFor(violation.verdict()) + violation.ruleId());
    }
    return List.copyOf(obligations);
  }

  private static String prefixFor(final Verdict verdict) {
    return verdict == Verdict.SOFT_DENY ? "soft-violation:" : "shadow-violation:";
  }
}

package io.reliabilityai.gateway.dataplane.governance;

import static org.assertj.core.api.Assertions.assertThat;

import io.reliabilityai.gateway.canonical.identity.CanonicalModelId;
import io.reliabilityai.gateway.canonical.identity.Region;
import io.reliabilityai.gateway.dataplane.governance.api.Decision;
import io.reliabilityai.gateway.dataplane.governance.api.DenialReason;
import io.reliabilityai.gateway.dataplane.governance.api.GovernanceRequest;
import io.reliabilityai.gateway.dataplane.governance.domain.Entitlement;
import io.reliabilityai.gateway.dataplane.governance.domain.GovernanceEvaluator;
import io.reliabilityai.gateway.dataplane.governance.domain.PolicySet;
import io.reliabilityai.gateway.dataplane.governance.domain.UsageState;
import java.time.Duration;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** Tests for the pure decision core: the precedence hierarchy, deny-overrides, and determinism. */
class GovernanceEvaluatorTest {

  private final GovernanceEvaluator evaluator = new GovernanceEvaluator(Duration.ofSeconds(60));

  private Decision evaluate(
      final GovernanceRequest request,
      final Optional<PolicySet> policy,
      final Optional<Entitlement> entitlement,
      final Optional<UsageState> usage) {
    return evaluator.evaluate(request, policy, entitlement, usage, Map.of(), GovernanceFixture.NOW);
  }

  private Decision evaluateDefault() {
    return evaluate(
        GovernanceFixture.request(),
        Optional.of(GovernanceFixture.policy()),
        Optional.of(GovernanceFixture.entitlement()),
        Optional.of(GovernanceFixture.usage()));
  }

  @Test
  void allowsAConformingRequestAndResolvesTheDownstreamConstraints() {
    final Decision decision = evaluateDefault();

    assertThat(decision).isInstanceOf(Decision.Permit.class);
    assertThat(decision.permitted()).isTrue();
    final Decision.Permit permit = (Decision.Permit) decision;
    // The Router consumes these as hard filters, so they must be resolved here, not re-derived
    // later.
    assertThat(permit.context().residencyScope()).containsExactly("us-east-1");
    assertThat(permit.context().complianceRegimes()).containsExactly("GDPR", "SOC2");
    assertThat(permit.context().quotaHeadroom()).isEqualTo(490L);
    assertThat(permit.context().budgetHeadroomMicros()).isEqualTo(995_000L);
  }

  @Test
  void missingPolicySnapshotFailsClosed() {
    final Decision decision =
        evaluate(
            GovernanceFixture.request(),
            Optional.empty(),
            Optional.of(GovernanceFixture.entitlement()),
            Optional.of(GovernanceFixture.usage()));

    // An unresolvable policy must never read as "no restrictions".
    assertThat(((Decision.Deny) decision).reason()).isEqualTo(DenialReason.POLICY_NOT_FOUND);
  }

  @Test
  void disabledTenantIsDenied() {
    final PolicySet disabled = withTenantEnabled(false);

    final Decision decision =
        evaluate(
            GovernanceFixture.request(),
            Optional.of(disabled),
            Optional.of(GovernanceFixture.entitlement()),
            Optional.of(GovernanceFixture.usage()));

    assertThat(((Decision.Deny) decision).reason()).isEqualTo(DenialReason.TENANT_DISABLED);
  }

  @Test
  void regionOutsideResidencyScopeIsDenied() {
    final GovernanceRequest elsewhere = withRegion(new Region("eu-west-1"));

    final Decision decision =
        evaluate(
            elsewhere,
            Optional.of(GovernanceFixture.policy()),
            Optional.of(GovernanceFixture.entitlement()),
            Optional.of(GovernanceFixture.usage()));

    assertThat(((Decision.Deny) decision).reason()).isEqualTo(DenialReason.REGION_NOT_ALLOWED);
  }

  @Test
  void unknownModelIsDenied() {
    final GovernanceRequest otherModel = withModel(new CanonicalModelId("model.unknown"));

    final Decision decision =
        evaluate(
            otherModel,
            Optional.of(GovernanceFixture.policy()),
            Optional.of(GovernanceFixture.entitlement()),
            Optional.of(GovernanceFixture.usage()));

    assertThat(((Decision.Deny) decision).reason()).isEqualTo(DenialReason.MODEL_NOT_ALLOWED);
  }

  @Test
  void unpermittedCapabilityIsDenied() {
    final GovernanceRequest request = withCapabilities(Set.of("audio"));

    final Decision decision =
        evaluate(
            request,
            Optional.of(GovernanceFixture.policy()),
            Optional.of(GovernanceFixture.entitlement()),
            Optional.of(GovernanceFixture.usage()));

    assertThat(((Decision.Deny) decision).reason()).isEqualTo(DenialReason.UNAUTHORIZED);
  }

  @Test
  void budgetCeilingIsEnforcedOnProjectedSpend() {
    final UsageState nearCap = new UsageState(10L, 999_500L, GovernanceFixture.NOW);

    final Decision decision =
        evaluate(
            GovernanceFixture.request(), // projects 1_000 micros of additional spend
            Optional.of(GovernanceFixture.policy()),
            Optional.of(GovernanceFixture.entitlement()),
            Optional.of(nearCap));

    assertThat(((Decision.Deny) decision).reason()).isEqualTo(DenialReason.BUDGET_EXCEEDED);
  }

  @Test
  void requestRateCeilingIsEnforced() {
    final UsageState atRateCeiling = new UsageState(1_000L, 0L, GovernanceFixture.NOW);

    final Decision decision =
        evaluate(
            GovernanceFixture.request(),
            Optional.of(GovernanceFixture.policy()),
            Optional.of(GovernanceFixture.entitlement()),
            Optional.of(atRateCeiling));

    assertThat(((Decision.Deny) decision).reason()).isEqualTo(DenialReason.RATE_LIMITED);
  }

  @Test
  void quotaCeilingIsEnforcedWhenBelowTheRateCeiling() {
    // Quota (500) bites before the rate ceiling (1000), so the reported reason must be the quota
    // one.
    final UsageState overQuota = new UsageState(500L, 0L, GovernanceFixture.NOW);

    final Decision decision =
        evaluate(
            GovernanceFixture.request(),
            Optional.of(GovernanceFixture.policy()),
            Optional.of(GovernanceFixture.entitlement()),
            Optional.of(overQuota));

    assertThat(((Decision.Deny) decision).reason()).isEqualTo(DenialReason.QUOTA_EXCEEDED);
  }

  @Test
  void capabilityBehindApprovalReturnsRequireApprovalRatherThanPermit() {
    final PolicySet gated = withApprovalRequired(Set.of("chat"));

    final Decision decision =
        evaluate(
            GovernanceFixture.request(),
            Optional.of(gated),
            Optional.of(GovernanceFixture.entitlement()),
            Optional.of(GovernanceFixture.usage()));

    assertThat(decision).isInstanceOf(Decision.RequireApproval.class);
    assertThat(decision.permitted()).isFalse(); // approval pending is not admission
    assertThat(((Decision.RequireApproval) decision).awaitingCapabilities())
        .containsExactly("chat");
  }

  @Test
  void approvalIsNeverRequestedForARequestThatWouldBeDeniedAnyway() {
    // Sending a doomed request to a human for approval wastes their time; deny-overrides wins.
    final PolicySet gated = withApprovalRequired(Set.of("chat"));

    final Decision decision =
        evaluate(
            withRegion(new Region("eu-west-1")),
            Optional.of(gated),
            Optional.of(GovernanceFixture.entitlement()),
            Optional.of(GovernanceFixture.usage()));

    assertThat(((Decision.Deny) decision).reason()).isEqualTo(DenialReason.REGION_NOT_ALLOWED);
  }

  @Test
  void gatedFeatureWithAbsentFlagFailsSafeToDisabled() {
    final GovernanceRequest wantsFeature = withFeatures(Set.of("beta-feature"));

    final Decision decision =
        evaluator.evaluate(
            wantsFeature,
            Optional.of(GovernanceFixture.policy()),
            Optional.of(GovernanceFixture.entitlement()),
            Optional.of(GovernanceFixture.usage()),
            Map.of("beta-feature", Optional.empty()), // flag absent from the snapshot
            GovernanceFixture.NOW);

    assertThat(((Decision.Deny) decision).reason()).isEqualTo(DenialReason.FEATURE_DISABLED);
  }

  @Test
  void unauthorizedToolIsDenied() {
    // The tools loop had no coverage at all: every existing request asks for no tools, so the
    // branch that refuses a tool outside the allow-list never executed. Negating it would admit
    // any tool the tenant is not permitted to invoke.
    final GovernanceRequest wantsUnlistedTool = withTools(Set.of("shell-exec"));

    final Decision decision =
        evaluate(
            wantsUnlistedTool,
            Optional.of(GovernanceFixture.policy()),
            Optional.of(GovernanceFixture.entitlement()),
            Optional.of(GovernanceFixture.usage()));

    assertThat(decision).isInstanceOf(Decision.Deny.class);
    assertThat(((Decision.Deny) decision).reason()).isEqualTo(DenialReason.TOOL_UNAUTHORIZED);
  }

  @Test
  void permittedToolIsAdmitted() {
    // The other side of the same branch: "search" is on the allow-list and must pass, so the
    // refusal above is proven to be selective rather than a blanket rejection of every tool.
    final Decision decision =
        evaluate(
            withTools(Set.of("search")),
            Optional.of(GovernanceFixture.policy()),
            Optional.of(GovernanceFixture.entitlement()),
            Optional.of(GovernanceFixture.usage()));

    assertThat(decision).isInstanceOf(Decision.Permit.class);
  }

  @Test
  void unattestedComplianceRegimeIsDenied() {
    // The compliance predicate was exercised, but only ever with an attested regime, so the deny
    // itself never ran. A tenant attested for SOC2/GDPR must not satisfy a HIPAA requirement.
    final Decision decision =
        evaluate(
            withComplianceRegimes(Set.of("HIPAA")),
            Optional.of(GovernanceFixture.policy()),
            Optional.of(GovernanceFixture.entitlement()),
            Optional.of(GovernanceFixture.usage()));

    assertThat(decision).isInstanceOf(Decision.Deny.class);
    assertThat(((Decision.Deny) decision).reason()).isEqualTo(DenialReason.COMPLIANCE_CONFLICT);
  }

  @Test
  void gatedFeatureExplicitlyDisabledIsDenied() {
    // gatedFeatureWithAbsentFlagFailsSafeToDisabled covers only the absent half of
    // `state.isEmpty() || !state.orElseThrow()`. The present-and-false half — a flag an operator
    // has deliberately switched off, which is the ordinary production case — never ran.
    final Decision decision =
        evaluator.evaluate(
            withFeatures(Set.of("beta-feature")),
            Optional.of(GovernanceFixture.policy()),
            Optional.of(GovernanceFixture.entitlement()),
            Optional.of(GovernanceFixture.usage()),
            Map.of("beta-feature", Optional.of(false)), // present, and explicitly off
            GovernanceFixture.NOW);

    assertThat(decision).isInstanceOf(Decision.Deny.class);
    assertThat(((Decision.Deny) decision).reason()).isEqualTo(DenialReason.FEATURE_DISABLED);
  }

  @Test
  void spendLandingExactlyOnTheBudgetCeilingIsAdmitted() {
    // The source states the rule: "Strictly greater-than: a request landing exactly on the ceiling
    // is still within budget." Nothing tested it, so `>` drifting to `>=` would silently refuse
    // every request that consumes the last available micro of an otherwise valid budget.
    final Entitlement limits = GovernanceFixture.entitlement();
    final UsageState spent = new UsageState(10L, 5_000L, GovernanceFixture.NOW);
    final long exactlyRemaining = limits.budgetLimitMicros() - spent.spentMicros();

    final Decision atCeiling =
        evaluate(
            withProjectedSpend(exactlyRemaining),
            Optional.of(GovernanceFixture.policy()),
            Optional.of(limits),
            Optional.of(spent));
    assertThat(atCeiling).isInstanceOf(Decision.Permit.class);

    // One micro beyond it must refuse, so the boundary is pinned from both sides.
    final Decision overCeiling =
        evaluate(
            withProjectedSpend(exactlyRemaining + 1L),
            Optional.of(GovernanceFixture.policy()),
            Optional.of(limits),
            Optional.of(spent));
    assertThat(((Decision.Deny) overCeiling).reason()).isEqualTo(DenialReason.BUDGET_EXCEEDED);
  }

  @Test
  void usageReadingStaleBeyondToleranceDeniesRatherThanAdmittingUnboundedSpend() {
    final UsageState stale = new UsageState(10L, 5_000L, GovernanceFixture.NOW.minusSeconds(3_600));

    final Decision decision =
        evaluate(
            GovernanceFixture.request(),
            Optional.of(GovernanceFixture.policy()),
            Optional.of(GovernanceFixture.entitlement()),
            Optional.of(stale));

    assertThat(((Decision.Deny) decision).reason()).isEqualTo(DenialReason.POLICY_UNAVAILABLE);
  }

  @Test
  void missingEntitlementOrUsageDeniesBecauseCeilingsCannotBeEnforced() {
    assertThat(
            ((Decision.Deny)
                    evaluate(
                        GovernanceFixture.request(),
                        Optional.of(GovernanceFixture.policy()),
                        Optional.empty(),
                        Optional.of(GovernanceFixture.usage())))
                .reason())
        .isEqualTo(DenialReason.POLICY_UNAVAILABLE);

    assertThat(
            ((Decision.Deny)
                    evaluate(
                        GovernanceFixture.request(),
                        Optional.of(GovernanceFixture.policy()),
                        Optional.of(GovernanceFixture.entitlement()),
                        Optional.empty()))
                .reason())
        .isEqualTo(DenialReason.POLICY_UNAVAILABLE);
  }

  @Test
  void snapshotVersionsPropagateOntoEveryDecision() {
    final Decision permit = evaluateDefault();
    assertThat(permit.policyVersions())
        .containsEntry("policy", "policy:v1")
        .containsEntry("entitlement", "entitlement:v1");

    final Decision deny =
        evaluate(
            withRegion(new Region("eu-west-1")),
            Optional.of(GovernanceFixture.policy()),
            Optional.of(GovernanceFixture.entitlement()),
            Optional.of(GovernanceFixture.usage()));
    // A denial must be as replayable as a permit — same recorded versions.
    assertThat(deny.policyVersions()).containsEntry("policy", "policy:v1");
  }

  @Test
  void evaluationIsDeterministicAcrossRepeatedRuns() {
    final Decision first = evaluateDefault();
    for (int run = 0; run < 25; run++) {
      final Decision repeat = evaluateDefault();
      assertThat(repeat).isEqualTo(first); // identical verdict AND identical resolved context
    }
  }

  @Test
  void denyOverridesRegardlessOfHowManyDomainsWouldPermit() {
    // Every domain but residency permits; the single hard-domain denial still binds.
    final Decision decision =
        evaluate(
            withRegion(new Region("ap-south-1")),
            Optional.of(GovernanceFixture.policy()),
            Optional.of(GovernanceFixture.entitlement()),
            Optional.of(GovernanceFixture.usage()));

    assertThat(decision.permitted()).isFalse();
    assertThat(((Decision.Deny) decision).reason()).isEqualTo(DenialReason.REGION_NOT_ALLOWED);
  }

  // ---- fixture variations -----------------------------------------------------------------

  private static PolicySet withTenantEnabled(final boolean enabled) {
    final PolicySet base = GovernanceFixture.policy();
    return new PolicySet(
        base.version(),
        enabled,
        base.allowedModels(),
        base.allowedRegions(),
        base.allowedCapabilities(),
        base.allowedTools(),
        base.complianceRegimes(),
        base.approvalRequiredCapabilities(),
        base.gatedFeatures(),
        base.maxRequestsPerWindow());
  }

  private static PolicySet withApprovalRequired(final Set<String> capabilities) {
    final PolicySet base = GovernanceFixture.policy();
    return new PolicySet(
        base.version(),
        base.tenantEnabled(),
        base.allowedModels(),
        base.allowedRegions(),
        base.allowedCapabilities(),
        base.allowedTools(),
        base.complianceRegimes(),
        capabilities,
        base.gatedFeatures(),
        base.maxRequestsPerWindow());
  }

  private static GovernanceRequest withRegion(final Region region) {
    final GovernanceRequest base = GovernanceFixture.request();
    return new GovernanceRequest(
        base.requestContext(),
        base.principal(),
        base.tenant(),
        base.requestedModel(),
        region,
        base.requestedCapabilities(),
        base.requestedTools(),
        base.requiredComplianceRegimes(),
        base.requestedFeatures(),
        base.projectedSpendMicros());
  }

  private static GovernanceRequest withModel(final CanonicalModelId model) {
    final GovernanceRequest base = GovernanceFixture.request();
    return new GovernanceRequest(
        base.requestContext(),
        base.principal(),
        base.tenant(),
        model,
        base.requestedRegion(),
        base.requestedCapabilities(),
        base.requestedTools(),
        base.requiredComplianceRegimes(),
        base.requestedFeatures(),
        base.projectedSpendMicros());
  }

  private static GovernanceRequest withCapabilities(final Set<String> capabilities) {
    final GovernanceRequest base = GovernanceFixture.request();
    return new GovernanceRequest(
        base.requestContext(),
        base.principal(),
        base.tenant(),
        base.requestedModel(),
        base.requestedRegion(),
        capabilities,
        base.requestedTools(),
        base.requiredComplianceRegimes(),
        base.requestedFeatures(),
        base.projectedSpendMicros());
  }

  private static GovernanceRequest withTools(final Set<String> tools) {
    final GovernanceRequest base = GovernanceFixture.request();
    return new GovernanceRequest(
        base.requestContext(),
        base.principal(),
        base.tenant(),
        base.requestedModel(),
        base.requestedRegion(),
        base.requestedCapabilities(),
        tools,
        base.requiredComplianceRegimes(),
        base.requestedFeatures(),
        base.projectedSpendMicros());
  }

  private static GovernanceRequest withComplianceRegimes(final Set<String> regimes) {
    final GovernanceRequest base = GovernanceFixture.request();
    return new GovernanceRequest(
        base.requestContext(),
        base.principal(),
        base.tenant(),
        base.requestedModel(),
        base.requestedRegion(),
        base.requestedCapabilities(),
        base.requestedTools(),
        regimes,
        base.requestedFeatures(),
        base.projectedSpendMicros());
  }

  private static GovernanceRequest withProjectedSpend(final long projectedSpendMicros) {
    final GovernanceRequest base = GovernanceFixture.request();
    return new GovernanceRequest(
        base.requestContext(),
        base.principal(),
        base.tenant(),
        base.requestedModel(),
        base.requestedRegion(),
        base.requestedCapabilities(),
        base.requestedTools(),
        base.requiredComplianceRegimes(),
        base.requestedFeatures(),
        projectedSpendMicros);
  }

  private static GovernanceRequest withFeatures(final Set<String> features) {
    final GovernanceRequest base = GovernanceFixture.request();
    return new GovernanceRequest(
        base.requestContext(),
        base.principal(),
        base.tenant(),
        base.requestedModel(),
        base.requestedRegion(),
        base.requestedCapabilities(),
        base.requestedTools(),
        base.requiredComplianceRegimes(),
        features,
        base.projectedSpendMicros());
  }
}

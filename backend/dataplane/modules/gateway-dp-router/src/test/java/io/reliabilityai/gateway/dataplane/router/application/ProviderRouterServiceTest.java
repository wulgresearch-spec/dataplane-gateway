package io.reliabilityai.gateway.dataplane.router.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.reliabilityai.gateway.canonical.identity.CanonicalModelId;
import io.reliabilityai.gateway.canonical.identity.CorrelationId;
import io.reliabilityai.gateway.canonical.identity.Region;
import io.reliabilityai.gateway.canonical.identity.SnapshotVersion;
import io.reliabilityai.gateway.canonical.identity.TenantScope;
import io.reliabilityai.gateway.dataplane.router.api.CapabilityDescriptor;
import io.reliabilityai.gateway.dataplane.router.api.CapabilityRegistryPort;
import io.reliabilityai.gateway.dataplane.router.api.CapabilityRegistrySnapshot;
import io.reliabilityai.gateway.dataplane.router.api.RoutingFailure.FailureReason;
import io.reliabilityai.gateway.dataplane.router.api.RoutingPolicy;
import io.reliabilityai.gateway.dataplane.router.api.RoutingPolicyPort;
import io.reliabilityai.gateway.dataplane.router.api.RoutingRequest;
import io.reliabilityai.gateway.dataplane.router.api.RoutingResult;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** Deterministic, fail-closed routing tests (Doc 19 §8/§11/§18). */
class ProviderRouterServiceTest {

  private static final TenantScope TENANT = TenantScope.of("org-1", "tenant-1");
  private static final Region REGION = new Region("us-east-1");
  private static final RoutingPolicy POLICY = new RoutingPolicy(1L, 1L, 1_000_000L, 0.9, Set.of());

  private final FakeRegistry registry = new FakeRegistry();
  private final FakePolicy policyPort = new FakePolicy(POLICY);
  private final ProviderRouterService router = new ProviderRouterService(registry, policyPort);

  private static CapabilityDescriptor eligible(
      final String id, final long cost, final long latency) {
    return new CapabilityDescriptor(
        id,
        new CanonicalModelId("model-x"),
        "route/" + id,
        Set.of("streaming", "tool_calling"),
        128_000,
        Set.of("SOC2", "HIPAA"),
        Set.of("us-east-1"),
        cost,
        1.0,
        latency,
        false);
  }

  private static RoutingRequest request(final String correlationId) {
    return new RoutingRequest(
        new CanonicalModelId("model-x"),
        new CorrelationId(correlationId),
        TENANT,
        REGION,
        Set.of("streaming"),
        4_000,
        Set.of("SOC2"),
        0L,
        Set.of());
  }

  private void snapshot(final CapabilityDescriptor... descriptors) {
    registry.snapshot =
        new CapabilityRegistrySnapshot(
            new SnapshotVersion("capability", "v1"), List.of(descriptors));
  }

  private static String primaryRoute(final RoutingResult result) {
    return ((RoutingResult.Routed) result).primary().providerRouteRef();
  }

  private static FailureReason reason(final RoutingResult result) {
    return ((RoutingResult.Failed) result).failure().reason();
  }

  @Test
  void routesToCheapestEligibleCandidateWithFailover() {
    snapshot(eligible("a", 100, 10), eligible("b", 50, 10), eligible("c", 200, 10));
    final RoutingResult result = router.route(request("corr-1"));
    assertThat(result).isInstanceOf(RoutingResult.Routed.class);
    assertThat(primaryRoute(result)).isEqualTo("route/b"); // cheapest wins
    assertThat(((RoutingResult.Routed) result).failover()).hasSize(2); // all hard-filtered
    assertThat(((RoutingResult.Routed) result).capabilitySnapshotVersion().version())
        .isEqualTo("v1");
  }

  @Test
  void failsClosedWhenPolicyMissing() {
    policyPort.policy = null;
    snapshot(eligible("a", 100, 10));
    assertThat(reason(router.route(request("c")))).isEqualTo(FailureReason.STALE_CONSTRAINT);
  }

  @Test
  void failsClosedWhenRegistryEmptyOrNoDescriptors() {
    registry.snapshot = null;
    assertThat(reason(router.route(request("c")))).isEqualTo(FailureReason.STALE_CONSTRAINT);
    snapshot();
    assertThat(reason(router.route(request("c")))).isEqualTo(FailureReason.NO_ELIGIBLE_PROVIDER);
  }

  @Test
  void hardTiersFailClosedWithBindingReason() {
    // tier 1 policy deny
    policyPort.policy = new RoutingPolicy(1L, 1L, 1L, 0.9, Set.of("a"));
    snapshot(eligible("a", 100, 10));
    assertThat(reason(router.route(request("c")))).isEqualTo(FailureReason.POLICY_CONFLICT);
    policyPort.policy = POLICY;

    // tier 2 compliance
    snapshot(
        withCompliance(
            eligible("a", 100, 10),
            Set.of("SOC2"))); // missing required HIPAA? request needs SOC2 only
    // Make request require HIPAA which candidate lacks:
    final var reqHipaa =
        new RoutingRequest(
            new CanonicalModelId("model-x"),
            new CorrelationId("c"),
            TENANT,
            REGION,
            Set.of("streaming"),
            0,
            Set.of("HIPAA"),
            0L,
            Set.of());
    snapshot(withCompliance(eligible("a", 100, 10), Set.of("SOC2")));
    assertThat(reason(router.route(reqHipaa))).isEqualTo(FailureReason.COMPLIANCE_CONFLICT);

    // tier 3 residency
    snapshot(withRegions(eligible("a", 100, 10), Set.of("eu-west-1")));
    assertThat(reason(router.route(request("c")))).isEqualTo(FailureReason.RESIDENCY_CONFLICT);

    // tier 4 capability
    snapshot(withCapabilities(eligible("a", 100, 10), Set.of("vision")));
    assertThat(reason(router.route(request("c")))).isEqualTo(FailureReason.CAPABILITY_UNSATISFIED);

    // tier 5 availability
    snapshot(withAvailability(eligible("a", 100, 10), 0.5));
    assertThat(reason(router.route(request("c")))).isEqualTo(FailureReason.NO_AVAILABLE_PROVIDER);

    // tier 6 reliability
    snapshot(withCircuitOpen(eligible("a", 100, 10)));
    assertThat(reason(router.route(request("c")))).isEqualTo(FailureReason.RELIABILITY_FLOOR);

    // budget
    snapshot(eligible("a", 5_000, 10));
    final var budgetReq =
        new RoutingRequest(
            new CanonicalModelId("model-x"),
            new CorrelationId("c"),
            TENANT,
            REGION,
            Set.of("streaming"),
            0,
            Set.of("SOC2"),
            100L,
            Set.of());
    assertThat(reason(router.route(budgetReq))).isEqualTo(FailureReason.BUDGET_EXCEEDED);
  }

  @Test
  void selectionIsDeterministicForTheSameRequest() {
    snapshot(eligible("a", 100, 10), eligible("b", 100, 10)); // equal score → tiebreak
    final String first = primaryRoute(router.route(request("corr-42")));
    for (int i = 0; i < 50; i++) {
      assertThat(primaryRoute(router.route(request("corr-42")))).isEqualTo(first);
    }
  }

  @Test
  void tiebreakDistributesAcrossCorrelationIds() {
    snapshot(eligible("a", 100, 10), eligible("b", 100, 10)); // equal score
    boolean sawA = false;
    boolean sawB = false;
    for (int i = 0; i < 200 && !(sawA && sawB); i++) {
      final String primary = primaryRoute(router.route(request("corr-" + i)));
      sawA |= primary.equals("route/a");
      sawB |= primary.equals("route/b");
    }
    assertThat(sawA && sawB)
        .as("deterministic tiebreak distributes primary across the tied set")
        .isTrue();
  }

  @Test
  void preferredCandidateGetsBonus() {
    snapshot(eligible("a", 100, 10), eligible("b", 100, 10));
    final var reqPreferB =
        new RoutingRequest(
            new CanonicalModelId("model-x"),
            new CorrelationId("c"),
            TENANT,
            REGION,
            Set.of("streaming"),
            0,
            Set.of("SOC2"),
            0L,
            Set.of("b"));
    assertThat(primaryRoute(router.route(reqPreferB))).isEqualTo("route/b");
  }

  @Test
  void extremeCostWeightNeverOverflowsIntoPreferringTheExpensiveCandidate() {
    // A huge cost weight × a huge candidate cost overflows the penalty; without saturating
    // arithmetic
    // it would wrap negative and rank the absurdly-expensive candidate best. With saturation, the
    // cheap candidate correctly wins (overflow-hardened, deterministic ranking, Doc 19 §13).
    policyPort.policy = new RoutingPolicy(Long.MAX_VALUE / 2, 1L, 0L, 0.9, Set.of());
    snapshot(eligible("a", Long.MAX_VALUE, 1), eligible("b", 1L, 1));
    assertThat(primaryRoute(router.route(request("c")))).isEqualTo("route/b");
  }

  @Test
  void rejectsNullRequest() {
    assertThatThrownBy(() -> router.route(null)).isInstanceOf(NullPointerException.class);
  }

  // ---- the requested-model boundary ------------------------------------------------------------
  // Governance authorises the model the caller asked for and resolves that request's permitted
  // routes by exact model equality. If selection could leave that model, a request admitted for one
  // model could invoke another that no decision ever approved — while metering and the response
  // still named the model the caller asked for.

  @Test
  void aModelNoCandidateServesIsRefused() {
    snapshot(servingModel(eligible("b1", 1, 1), "model-B"));

    final RoutingResult result = router.route(requestFor("model-A"));

    assertThat(result).isInstanceOf(RoutingResult.Failed.class);
    assertThat(reason(result)).isEqualTo(FailureReason.NO_ELIGIBLE_PROVIDER);
  }

  @Test
  void aBetterScoringCandidateForAnotherModelIsNeverSelected() {
    // The other model's candidate is strictly cheaper and strictly faster, so on score alone it
    // would win outright. It must not be reachable at all.
    snapshot(
        servingModel(eligible("a1", 10_000, 500), "model-A"),
        servingModel(eligible("b1", 1, 1), "model-B"));

    assertThat(primaryRoute(router.route(requestFor("model-A")))).isEqualTo("route/a1");
  }

  @Test
  void failoverNeverLeavesTheRequestedModel() {
    // Failover is where a boundary is most easily lost: the primary is scrutinised, the rest are
    // assumed. Every entry must be as eligible as the primary.
    snapshot(
        servingModel(eligible("a1", 100, 10), "model-A"),
        servingModel(eligible("a2", 200, 10), "model-A"),
        servingModel(eligible("b1", 1, 1), "model-B"),
        servingModel(eligible("b2", 2, 1), "model-B"));

    final RoutingResult.Routed routed = (RoutingResult.Routed) router.route(requestFor("model-A"));

    assertThat(routed.primary().canonicalModelId().value()).isEqualTo("model-A");
    assertThat(routed.failover()).hasSize(1);
    assertThat(routed.failover())
        .allSatisfy(target -> assertThat(target.canonicalModelId().value()).isEqualTo("model-A"));
  }

  @Test
  void aPreferredCandidateForAnotherModelCannotWin() {
    // The preference bonus dwarfs the cost and latency weights, so this proves preference is a
    // tie-breaker among eligible candidates and never a way into the set.
    snapshot(
        servingModel(eligible("a1", 100, 10), "model-A"),
        servingModel(eligible("b1", 100, 10), "model-B"));
    final var preferOtherModel =
        new RoutingRequest(
            new CanonicalModelId("model-A"),
            new CorrelationId("c"),
            TENANT,
            REGION,
            Set.of("streaming"),
            0,
            Set.of("SOC2"),
            0L,
            Set.of("b1"));

    assertThat(primaryRoute(router.route(preferOtherModel))).isEqualTo("route/a1");
  }

  @Test
  void modelMatchingIsExactAndNotFuzzy() {
    // Case folding, prefixes and substrings are each a way back to the same gap, so none of them
    // may resolve to a candidate.
    snapshot(servingModel(eligible("a1", 100, 10), "model-A"));

    for (final String nearMiss :
        List.of("MODEL-A", "model-a", "model-A-extra", "model-A-something", "model", "model-A ")) {
      assertThat(reason(router.route(requestFor(nearMiss))))
          .as("near miss %s must not match", nearMiss)
          .isEqualTo(FailureReason.NO_ELIGIBLE_PROVIDER);
    }
    assertThat(router.route(requestFor("model-A"))).isInstanceOf(RoutingResult.Routed.class);
  }

  @Test
  void aModelMismatchBindsBeforeAnyPolicyTier() {
    // The other model's candidate is also tenant-denied. Were the tiers applied first the surfaced
    // reason would be POLICY_CONFLICT, naming a conflict with a candidate the request was never
    // entitled to be compared against.
    policyPort.policy = new RoutingPolicy(1L, 1L, 1_000_000L, 0.9, Set.of("b1"));
    snapshot(servingModel(eligible("b1", 100, 10), "model-B"));

    assertThat(reason(router.route(requestFor("model-A"))))
        .isEqualTo(FailureReason.NO_ELIGIBLE_PROVIDER);
  }

  @Test
  void aCandidateSittingExactlyOnEachHardTierBoundaryIsStillEligible() {
    // Each of these tiers is a limit rather than a target: a candidate whose context length exactly
    // meets the minimum, whose availability exactly equals the floor, and whose cost exactly equals
    // the ceiling satisfies all three. Reading any of them exclusively would refuse candidates the
    // configuration permits, which is a self-inflicted outage rather than a safety margin.
    final CapabilityDescriptor onEveryBoundary =
        withAvailability(withContextLength(eligible("a", 4_000, 10), 4_000), 0.9);
    snapshot(onEveryBoundary);
    final var atCeiling =
        new RoutingRequest(
            new CanonicalModelId("model-x"),
            new CorrelationId("c"),
            TENANT,
            REGION,
            Set.of("streaming"),
            4_000,
            Set.of("SOC2"),
            4_000L,
            Set.of());

    assertThat(primaryRoute(router.route(atCeiling))).isEqualTo("route/a");
  }

  @Test
  void aCandidateOneStepPastEachHardTierBoundaryIsRefused() {
    // The other side of the same three limits, so the boundary is pinned from both directions and
    // the binding reason names the tier that actually excluded the candidate.
    snapshot(withContextLength(eligible("a", 100, 10), 3_999));
    assertThat(reason(router.route(request("c")))).isEqualTo(FailureReason.CAPABILITY_UNSATISFIED);

    snapshot(withAvailability(eligible("a", 100, 10), 0.899));
    assertThat(reason(router.route(request("c")))).isEqualTo(FailureReason.NO_AVAILABLE_PROVIDER);

    snapshot(eligible("a", 4_001, 10));
    final var justOverCeiling =
        new RoutingRequest(
            new CanonicalModelId("model-x"),
            new CorrelationId("c"),
            TENANT,
            REGION,
            Set.of("streaming"),
            4_000,
            Set.of("SOC2"),
            4_000L,
            Set.of());
    assertThat(reason(router.route(justOverCeiling))).isEqualTo(FailureReason.BUDGET_EXCEEDED);
  }

  private static CapabilityDescriptor withContextLength(
      final CapabilityDescriptor d, final int contextLength) {
    return new CapabilityDescriptor(
        d.candidateId(),
        d.canonicalModelId(),
        d.providerRouteRef(),
        d.supportedCapabilities(),
        contextLength,
        d.complianceAttestations(),
        d.allowedRegions(),
        d.costMicros(),
        d.availabilityScore(),
        d.expectedLatencyMillis(),
        d.circuitOpen());
  }

  private static RoutingRequest requestFor(final String model) {
    return new RoutingRequest(
        new CanonicalModelId(model),
        new CorrelationId("corr-1"),
        TENANT,
        REGION,
        Set.of("streaming"),
        4_000,
        Set.of("SOC2"),
        0L,
        Set.of());
  }

  private static CapabilityDescriptor servingModel(
      final CapabilityDescriptor d, final String model) {
    return new CapabilityDescriptor(
        d.candidateId(),
        new CanonicalModelId(model),
        d.providerRouteRef(),
        d.supportedCapabilities(),
        d.maxContextLength(),
        d.complianceAttestations(),
        d.allowedRegions(),
        d.costMicros(),
        d.availabilityScore(),
        d.expectedLatencyMillis(),
        d.circuitOpen());
  }

  private static CapabilityDescriptor withCompliance(
      final CapabilityDescriptor d, final Set<String> c) {
    return new CapabilityDescriptor(
        d.candidateId(),
        d.canonicalModelId(),
        d.providerRouteRef(),
        d.supportedCapabilities(),
        d.maxContextLength(),
        c,
        d.allowedRegions(),
        d.costMicros(),
        d.availabilityScore(),
        d.expectedLatencyMillis(),
        d.circuitOpen());
  }

  private static CapabilityDescriptor withRegions(
      final CapabilityDescriptor d, final Set<String> r) {
    return new CapabilityDescriptor(
        d.candidateId(),
        d.canonicalModelId(),
        d.providerRouteRef(),
        d.supportedCapabilities(),
        d.maxContextLength(),
        d.complianceAttestations(),
        r,
        d.costMicros(),
        d.availabilityScore(),
        d.expectedLatencyMillis(),
        d.circuitOpen());
  }

  private static CapabilityDescriptor withCapabilities(
      final CapabilityDescriptor d, final Set<String> c) {
    return new CapabilityDescriptor(
        d.candidateId(),
        d.canonicalModelId(),
        d.providerRouteRef(),
        c,
        d.maxContextLength(),
        d.complianceAttestations(),
        d.allowedRegions(),
        d.costMicros(),
        d.availabilityScore(),
        d.expectedLatencyMillis(),
        d.circuitOpen());
  }

  private static CapabilityDescriptor withAvailability(
      final CapabilityDescriptor d, final double a) {
    return new CapabilityDescriptor(
        d.candidateId(),
        d.canonicalModelId(),
        d.providerRouteRef(),
        d.supportedCapabilities(),
        d.maxContextLength(),
        d.complianceAttestations(),
        d.allowedRegions(),
        d.costMicros(),
        a,
        d.expectedLatencyMillis(),
        d.circuitOpen());
  }

  private static CapabilityDescriptor withCircuitOpen(final CapabilityDescriptor d) {
    return new CapabilityDescriptor(
        d.candidateId(),
        d.canonicalModelId(),
        d.providerRouteRef(),
        d.supportedCapabilities(),
        d.maxContextLength(),
        d.complianceAttestations(),
        d.allowedRegions(),
        d.costMicros(),
        d.availabilityScore(),
        d.expectedLatencyMillis(),
        true);
  }

  private static final class FakeRegistry implements CapabilityRegistryPort {
    private CapabilityRegistrySnapshot snapshot;

    @Override
    public Optional<CapabilityRegistrySnapshot> currentSnapshot() {
      return Optional.ofNullable(snapshot);
    }
  }

  private static final class FakePolicy implements RoutingPolicyPort {
    private RoutingPolicy policy;

    private FakePolicy(final RoutingPolicy policy) {
      this.policy = policy;
    }

    @Override
    public Optional<RoutingPolicy> resolve(final TenantScope tenantScope) {
      return Optional.ofNullable(policy);
    }
  }
}

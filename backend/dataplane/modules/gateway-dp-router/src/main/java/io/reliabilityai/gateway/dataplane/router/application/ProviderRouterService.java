package io.reliabilityai.gateway.dataplane.router.application;

import io.reliabilityai.gateway.canonical.decision.RouteTarget;
import io.reliabilityai.gateway.common.Preconditions;
import io.reliabilityai.gateway.dataplane.router.api.CapabilityDescriptor;
import io.reliabilityai.gateway.dataplane.router.api.CapabilityRegistryPort;
import io.reliabilityai.gateway.dataplane.router.api.CapabilityRegistrySnapshot;
import io.reliabilityai.gateway.dataplane.router.api.ProviderRouterPort;
import io.reliabilityai.gateway.dataplane.router.api.RoutingFailure;
import io.reliabilityai.gateway.dataplane.router.api.RoutingFailure.FailureReason;
import io.reliabilityai.gateway.dataplane.router.api.RoutingPolicy;
import io.reliabilityai.gateway.dataplane.router.api.RoutingPolicyPort;
import io.reliabilityai.gateway.dataplane.router.api.RoutingRequest;
import io.reliabilityai.gateway.dataplane.router.api.RoutingResult;
import io.reliabilityai.gateway.dataplane.router.domain.RouterDigest;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.function.Predicate;

/**
 * The Provider Router (C1, Doc 19 §8): a deterministic, single-pass pipeline over the cached
 * candidate set — resolve policy → hard-filter (tenant, compliance, residency, capability,
 * availability, reliability, budget) → soft-score (cost, latency, preference) → deterministically
 * select and rank — emitting an immutable {@link RoutingResult} or failing closed with the binding
 * constraint (Doc 19 §11/§12/§13/§18). No hot-path network, no provider SDK, no credentials (Doc 19
 * PR-D12). Stateless, side-effect-free, and virtual-thread-safe (no locks; snapshots injected
 * read-only).
 *
 * <p><b>Hard tiers are applied in canonical hierarchy order</b> so the surfaced failure names the
 * highest-priority tier the request conflicts with (Doc 19 §11.1). A hard tier is <b>never</b>
 * relaxed to keep a candidate, and cost/preference can never trade away compliance/residency/policy
 * (§12).
 */
public final class ProviderRouterService implements ProviderRouterPort {

  private final CapabilityRegistryPort registry;
  private final RoutingPolicyPort policyPort;

  /**
   * Creates the router against its injected snapshot ports (AD-002).
   *
   * @param registry the cached capability registry (Doc 19 §7)
   * @param policyPort the resolved routing policy source (Doc 19 §10)
   */
  public ProviderRouterService(
      final CapabilityRegistryPort registry, final RoutingPolicyPort policyPort) {
    this.registry = Preconditions.requireNonNull(registry, "registry");
    this.policyPort = Preconditions.requireNonNull(policyPort, "policyPort");
  }

  @Override
  public RoutingResult route(final RoutingRequest request) {
    Preconditions.requireNonNull(request, "request");

    final Optional<RoutingPolicy> maybePolicy = policyPort.resolve(request.tenantScope());
    if (maybePolicy.isEmpty()) {
      return failed(FailureReason.STALE_CONSTRAINT);
    }
    final RoutingPolicy policy = maybePolicy.get();

    final Optional<CapabilityRegistrySnapshot> maybeSnapshot = registry.currentSnapshot();
    if (maybeSnapshot.isEmpty()) {
      return failed(FailureReason.STALE_CONSTRAINT);
    }
    final CapabilityRegistrySnapshot snapshot = maybeSnapshot.get();
    if (snapshot.descriptors().isEmpty()) {
      return failed(FailureReason.NO_ELIGIBLE_PROVIDER);
    }

    // A candidate that does not serve the requested model is not a candidate at all, so this runs
    // ahead of the policy tiers rather than among them. Governance authorises the model the caller
    // asked for — and resolves that request's permitted routes by exactly this equality — so
    // selecting a candidate for some other model would invoke a model no decision ever approved,
    // while metering and the response still named the requested one. Equality is canonical and
    // exact: no case folding, prefix matching, aliasing or substitution, because each of those
    // would reintroduce the gap by a narrower route.
    List<CapabilityDescriptor> survivors = snapshot.descriptors();
    survivors = filter(survivors, c -> c.canonicalModelId().equals(request.canonicalModelId()));
    if (survivors.isEmpty()) {
      return failed(FailureReason.NO_ELIGIBLE_PROVIDER);
    }

    // Hard tiers 1-6 + budget, applied in canonical order; the tier that empties the set is
    // binding.
    survivors = filter(survivors, c -> !policy.deniedCandidates().contains(c.candidateId()));
    if (survivors.isEmpty()) {
      return failed(FailureReason.POLICY_CONFLICT);
    }
    survivors =
        filter(
            survivors, c -> c.complianceAttestations().containsAll(request.requiredCompliance()));
    if (survivors.isEmpty()) {
      return failed(FailureReason.COMPLIANCE_CONFLICT);
    }
    survivors = filter(survivors, c -> c.allowedRegions().contains(request.region().value()));
    if (survivors.isEmpty()) {
      return failed(FailureReason.RESIDENCY_CONFLICT);
    }
    survivors =
        filter(
            survivors,
            c ->
                c.supportedCapabilities().containsAll(request.requiredCapabilities())
                    && c.maxContextLength() >= request.minContextLength());
    if (survivors.isEmpty()) {
      return failed(FailureReason.CAPABILITY_UNSATISFIED);
    }
    survivors = filter(survivors, c -> c.availabilityScore() >= policy.availabilityFloor());
    if (survivors.isEmpty()) {
      return failed(FailureReason.NO_AVAILABLE_PROVIDER);
    }
    survivors = filter(survivors, c -> !c.circuitOpen());
    if (survivors.isEmpty()) {
      return failed(FailureReason.RELIABILITY_FLOOR);
    }
    survivors =
        filter(
            survivors,
            c -> request.costCeilingMicros() == 0 || c.costMicros() <= request.costCeilingMicros());
    if (survivors.isEmpty()) {
      return failed(FailureReason.BUDGET_EXCEEDED);
    }

    // Soft scoring over survivors + deterministic ranking (score desc, then per-request order key).
    final String correlationId = request.correlationId().value();
    final List<CapabilityDescriptor> ranked = new ArrayList<>(survivors);
    ranked.sort(
        Comparator.comparingLong((CapabilityDescriptor c) -> score(c, request, policy))
            .reversed()
            .thenComparing(c -> RouterDigest.orderKey(correlationId, c.candidateId())));

    final RouteTarget primary = toRouteTarget(ranked.get(0));
    final List<RouteTarget> failover = new ArrayList<>();
    for (int i = 1; i < ranked.size(); i++) {
      failover.add(toRouteTarget(ranked.get(i)));
    }
    return new RoutingResult.Routed(primary, failover, snapshot.version());
  }

  private static long score(
      final CapabilityDescriptor candidate,
      final RoutingRequest request,
      final RoutingPolicy policy) {
    // Higher is better. Lower cost and latency raise the score; a preferred candidate gets a bonus.
    // Saturating arithmetic: an overflowing cost/latency penalty must NOT wrap negative (which
    // would
    // make a pathologically expensive candidate rank best) — it saturates so the score stays
    // monotonic
    // and deterministic for any inputs (Doc 19 §13 deterministic ranking; overflow-hardened).
    final long preference =
        request.preferredCandidates().contains(candidate.candidateId())
            ? policy.preferenceBonus()
            : 0L;
    final long costPenalty = satMul(policy.costWeight(), candidate.costMicros());
    final long latencyPenalty = satMul(policy.latencyWeight(), candidate.expectedLatencyMillis());
    return satSub(satSub(preference, costPenalty), latencyPenalty);
  }

  /**
   * Saturating multiply: clamps to {@link Long#MAX_VALUE}/{@link Long#MIN_VALUE} instead of
   * wrapping.
   */
  private static long satMul(final long a, final long b) {
    try {
      return Math.multiplyExact(a, b);
    } catch (final ArithmeticException overflow) {
      return ((a ^ b) < 0) ? Long.MIN_VALUE : Long.MAX_VALUE; // opposite signs ⇒ very negative
    }
  }

  /** Saturating subtract: clamps instead of wrapping. */
  private static long satSub(final long a, final long b) {
    try {
      return Math.subtractExact(a, b);
    } catch (final ArithmeticException overflow) {
      return b > 0
          ? Long.MIN_VALUE
          : Long.MAX_VALUE; // subtracting a large positive ⇒ very negative
    }
  }

  private static List<CapabilityDescriptor> filter(
      final List<CapabilityDescriptor> candidates,
      final Predicate<CapabilityDescriptor> predicate) {
    final List<CapabilityDescriptor> kept = new ArrayList<>();
    for (final CapabilityDescriptor candidate : candidates) {
      if (predicate.test(candidate)) {
        kept.add(candidate);
      }
    }
    return kept;
  }

  private static RouteTarget toRouteTarget(final CapabilityDescriptor candidate) {
    return new RouteTarget(candidate.canonicalModelId(), candidate.providerRouteRef());
  }

  private static RoutingResult failed(final FailureReason reason) {
    return new RoutingResult.Failed(new RoutingFailure(reason));
  }
}

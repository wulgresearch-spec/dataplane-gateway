package io.reliabilityai.gateway.dataplane.router.api;

import io.reliabilityai.gateway.common.Preconditions;
import java.util.Set;

/**
 * The resolved routing policy for a tenant (Doc 19 §10/§12). Authored by C4
 * Governance/Configuration and consumed read-only by the Router — the Router enforces, never
 * authors, weights or policy (Doc 19 PRM-4/§4). Soft-scoring composite weights and the availability
 * floor come from here, as does the tenant deny-list (hard tier 1). Immutable.
 *
 * @param costWeight the cost weight for soft scoring (lower cost preferred; per-unit-cost penalty)
 * @param latencyWeight the latency weight for soft scoring (lower latency preferred)
 * @param preferenceBonus the bonus applied to a tenant-preferred candidate
 * @param availabilityFloor the minimum availability score a candidate must meet (tier 5), in
 *     [0.0,1.0]
 * @param deniedCandidates candidate route ids the tenant policy excludes (tier 1); defensively
 *     copied
 */
public record RoutingPolicy(
    long costWeight,
    long latencyWeight,
    long preferenceBonus,
    double availabilityFloor,
    Set<String> deniedCandidates) {

  /** Compact constructor validating weights and defensively copying the deny-list. */
  public RoutingPolicy {
    Preconditions.requireNonNegative(costWeight, "costWeight");
    Preconditions.requireNonNegative(latencyWeight, "latencyWeight");
    Preconditions.requireNonNegative(preferenceBonus, "preferenceBonus");
    if (availabilityFloor < 0.0 || availabilityFloor > 1.0) {
      throw new IllegalArgumentException("availabilityFloor must be within [0.0, 1.0]");
    }
    deniedCandidates =
        Set.copyOf(Preconditions.requireNonNull(deniedCandidates, "deniedCandidates"));
  }
}

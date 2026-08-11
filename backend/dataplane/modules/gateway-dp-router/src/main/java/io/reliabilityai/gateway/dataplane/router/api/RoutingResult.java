package io.reliabilityai.gateway.dataplane.router.api;

import io.reliabilityai.gateway.canonical.decision.RouteTarget;
import io.reliabilityai.gateway.canonical.identity.SnapshotVersion;
import io.reliabilityai.gateway.common.Preconditions;
import java.util.List;

/**
 * The terminal, immutable routing outcome (Doc 19 §7/§14/§18). Sealed and fail-closed: either a
 * {@link Routed} decision (an ordered, fully hard-filtered candidate set — primary + policy-safe
 * failover) or a {@link Failed} outcome naming the binding constraint. Every candidate in a {@link
 * Routed} result has passed all hard tiers, so failover is always policy-safe (Doc 19 §30).
 */
public sealed interface RoutingResult permits RoutingResult.Routed, RoutingResult.Failed {

  /**
   * A successful routing decision.
   *
   * @param primary the selected primary route target
   * @param failover the ranked, hard-filtered failover route targets (may be empty)
   * @param capabilitySnapshotVersion the pinned capability snapshot version (reproducibility, §14)
   */
  record Routed(
      RouteTarget primary, List<RouteTarget> failover, SnapshotVersion capabilitySnapshotVersion)
      implements RoutingResult {
    /** Compact constructor validating fields and defensively copying the failover list. */
    public Routed {
      Preconditions.requireNonNull(primary, "primary");
      failover = failover == null ? List.of() : List.copyOf(failover);
      Preconditions.requireNonNull(capabilitySnapshotVersion, "capabilitySnapshotVersion");
    }
  }

  /**
   * A fail-closed routing outcome.
   *
   * @param failure the neutral binding-constraint failure
   */
  record Failed(RoutingFailure failure) implements RoutingResult {
    /** Compact constructor validating the failure. */
    public Failed {
      Preconditions.requireNonNull(failure, "failure");
    }
  }
}

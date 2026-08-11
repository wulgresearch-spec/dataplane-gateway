package io.reliabilityai.gateway.canonical.decision;

import io.reliabilityai.gateway.common.Preconditions;

/**
 * The Router's decision (C1, Doc 19, Doc 33 §10.4). Immutable; a decision-replay artifact (Doc 32
 * §CRS). The Router decides; a pre-routing plugin only contributes advisory signals (Doc 28 §EPC,
 * Doc 32 §PEB).
 *
 * @param routeTarget the selected route target
 */
public record RoutingDecision(RouteTarget routeTarget) {

  /** Compact constructor validating the route target presence. */
  public RoutingDecision {
    Preconditions.requireNonNull(routeTarget, "routeTarget");
  }
}

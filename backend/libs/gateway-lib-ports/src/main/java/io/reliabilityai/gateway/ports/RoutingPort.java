package io.reliabilityai.gateway.ports;

import io.reliabilityai.gateway.canonical.decision.RoutingDecision;
import io.reliabilityai.gateway.canonical.io.CanonicalRequest;

/**
 * The inbound routing port implemented by the Provider Router (C1, Doc 19, AD-007). Selects a
 * provider-neutral route target from the capability snapshot; provider identity stays internal to
 * the adapter (Doc 25 §7.1). A pre-routing plugin only contributes signals (Doc 28 §EPC).
 */
public interface RoutingPort {

  /**
   * Selects a route for the request (Doc 19).
   *
   * @param request the canonical request
   * @return the routing decision (route target)
   */
  RoutingDecision route(CanonicalRequest request);
}

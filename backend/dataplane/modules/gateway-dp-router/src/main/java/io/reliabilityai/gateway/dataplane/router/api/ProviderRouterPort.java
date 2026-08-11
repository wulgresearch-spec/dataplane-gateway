package io.reliabilityai.gateway.dataplane.router.api;

/**
 * The inbound Provider Router port (C1, Doc 19 §7, AD-002/AD-007). Selects a provider-neutral route
 * deterministically from cached snapshots — no hot-path network, no provider SDK, no credentials —
 * and fails closed. Called after authentication and governance, before Reliability (Doc 19 §7/§30).
 */
public interface ProviderRouterPort {

  /**
   * Routes a request deterministically, fail-closed (Doc 19 §8).
   *
   * @param request the neutral routing request
   * @return a {@link RoutingResult}: a routed decision or a typed failure
   */
  RoutingResult route(RoutingRequest request);
}

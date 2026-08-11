package io.reliabilityai.gateway.dataplane.reliability.api;

/**
 * The per-route circuit-state seam (Doc 20 §7/§10, RE-D4). Sourced read-only from the shared
 * reliability snapshot/tier (AD-022). An open circuit means the Engine skips that route (local
 * skip) and fails over (Doc 20 §10) — it never invokes a route whose circuit is open. Thread-safe.
 */
public interface CircuitPort {

  /**
   * Whether the circuit for the given route is open (skip and fail over, Doc 20 §10).
   *
   * @param providerRouteRef the opaque route reference
   * @return {@code true} if the circuit is open
   */
  boolean isOpen(String providerRouteRef);
}

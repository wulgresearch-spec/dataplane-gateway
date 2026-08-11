package io.reliabilityai.gateway.dataplane.provider.api;

/**
 * Where a provider is in its lifecycle.
 *
 * <p>The states exist to make one distinction the gateway previously could not draw: a provider
 * that was never wired, a provider whose declaration disagreed with the published snapshot, and a
 * provider that started and is now answering. Collapsing those into "present or absent" meant a
 * mis-declared provider looked identical to a working one until a request hit it.
 *
 * <p>Only {@link #READY} and {@link #DEGRADED} routes are bound for dispatch. Degraded still
 * routes: health is a background observation, and refusing traffic because a probe failed would
 * hand the routing decision to a stale side-channel rather than to Reliability's live circuit
 * breaker.
 */
public enum ProviderState {

  /** Declared and registered, not yet validated against the published capability snapshot. */
  DISCOVERED(false),

  /** Declaration agrees with the published snapshot; not yet started. */
  VALIDATED(false),

  /** Started and dispatchable. */
  READY(true),

  /** Started and dispatchable, but its last liveness probe failed. */
  DEGRADED(true),

  /** Rejected at validation or failed to start — never dispatchable. */
  FAILED(false),

  /** Withdrawn by an operator — never dispatchable. */
  DISABLED(false);

  private final boolean dispatchable;

  ProviderState(final boolean dispatchable) {
    this.dispatchable = dispatchable;
  }

  /**
   * Whether requests may be dispatched to a provider in this state.
   *
   * @return {@code true} for {@link #READY} and {@link #DEGRADED}
   */
  public boolean dispatchable() {
    return dispatchable;
  }
}

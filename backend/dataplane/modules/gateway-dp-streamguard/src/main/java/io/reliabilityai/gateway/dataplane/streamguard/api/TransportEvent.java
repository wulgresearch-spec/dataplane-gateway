package io.reliabilityai.gateway.dataplane.streamguard.api;

import io.reliabilityai.gateway.common.Preconditions;

/**
 * A pulled transport event from a {@link TransportSession} (Doc 18 §7). Sealed: an emitted {@link
 * TransportDelta}, or the terminal {@link TransportVerdict} that ends the session. A session yields
 * zero or more {@code Delta} events followed by exactly one {@code Completed} verdict (Doc 18
 * §9/§29/§30).
 */
public sealed interface TransportEvent permits TransportEvent.Delta, TransportEvent.Completed {

  /**
   * An emitted, effectively-once, arrival-ordered data delta (Doc 18 §29).
   *
   * @param delta the transport delta
   */
  record Delta(TransportDelta delta) implements TransportEvent {
    /** Compact constructor validating the delta. */
    public Delta {
      Preconditions.requireNonNull(delta, "delta");
    }
  }

  /**
   * The terminal transport verdict ending the session (Doc 18 §9/§30).
   *
   * @param verdict the transport verdict
   */
  record Completed(TransportVerdict verdict) implements TransportEvent {
    /** Compact constructor validating the verdict. */
    public Completed {
      Preconditions.requireNonNull(verdict, "verdict");
    }
  }
}

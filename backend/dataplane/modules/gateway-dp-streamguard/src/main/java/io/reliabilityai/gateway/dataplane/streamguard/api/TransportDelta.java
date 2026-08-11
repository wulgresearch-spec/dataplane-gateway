package io.reliabilityai.gateway.dataplane.streamguard.api;

import io.reliabilityai.gateway.common.Preconditions;

/**
 * A canonical, ordered, UTF-8-clean transport delta emitted to the pipeline (Doc 18 §6/§8). It
 * carries a monotonic per-session {@code seq} (Doc 18 §16) and an opaque, structurally-well-formed
 * payload (Doc 18 §20.1); StreamGuard never interprets its semantics — SchemaLock (Doc 17) does.
 * Emitted effectively-once and in arrival order (Doc 18 §29). Immutable; the payload is defensively
 * cloned.
 */
public final class TransportDelta {

  private final long seq;
  private final byte[] payload;

  private TransportDelta(final long seq, final byte[] payload) {
    this.seq = seq;
    this.payload = payload;
  }

  /**
   * Creates a data delta with the given monotonic sequence and cloned opaque payload.
   *
   * @param seq the monotonic per-session sequence ({@code >= 0})
   * @param payload the opaque, structurally-well-formed payload bytes (defensively cloned)
   * @return the immutable delta
   */
  public static TransportDelta data(final long seq, final byte[] payload) {
    Preconditions.requireNonNegative(seq, "seq");
    Preconditions.requireNonNull(payload, "payload");
    return new TransportDelta(seq, payload.clone());
  }

  /**
   * The monotonic per-session sequence (Doc 18 §16).
   *
   * @return the sequence
   */
  public long seq() {
    return seq;
  }

  /**
   * A defensive clone of the opaque payload bytes.
   *
   * @return a clone of the payload
   */
  public byte[] payload() {
    return payload.clone();
  }
}

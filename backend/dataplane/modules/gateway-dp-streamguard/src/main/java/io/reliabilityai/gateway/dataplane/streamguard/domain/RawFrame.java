package io.reliabilityai.gateway.dataplane.streamguard.domain;

import io.reliabilityai.gateway.common.Preconditions;

/**
 * A framing-decoded transport unit (Doc 18 §6) — one SSE event / one NDJSON line / one JSON-array
 * element — with its raw opaque payload bytes and a transport {@link Kind}. The framing decoder
 * extracts it structurally; it <b>never</b> interprets payload semantics (Doc 18 §11/§20.1).
 * Immutable; the payload is defensively cloned.
 */
public final class RawFrame {

  /** The transport role of a frame (Doc 18 §6/§23 — data vs liveness vs explicit terminal). */
  public enum Kind {
    /** Content-bearing transport unit (emitted as a data delta). */
    DATA,
    /** Liveness keepalive (SSE comment / event-stream keepalive) — never emitted (Doc 18 §23). */
    HEARTBEAT,
    /** Explicit transport terminal (SSE {@code [DONE]}-class, array close) (Doc 18 §21). */
    TERMINAL
  }

  private final Kind kind;
  private final byte[] payload;

  private RawFrame(final Kind kind, final byte[] payload) {
    this.kind = kind;
    this.payload = payload;
  }

  /**
   * Creates a frame with cloned payload bytes.
   *
   * @param kind the transport kind
   * @param payload the opaque payload bytes (defensively cloned; empty for heartbeat/terminal)
   * @return the immutable frame
   */
  public static RawFrame of(final Kind kind, final byte[] payload) {
    Preconditions.requireNonNull(kind, "kind");
    Preconditions.requireNonNull(payload, "payload");
    return new RawFrame(kind, payload.clone());
  }

  /**
   * The transport kind.
   *
   * @return the kind
   */
  public Kind kind() {
    return kind;
  }

  /**
   * A defensive clone of the opaque payload bytes.
   *
   * @return a clone of the payload
   */
  public byte[] payload() {
    return payload.clone();
  }

  /**
   * The payload length in bytes (for bounded-buffer accounting; no content exposure).
   *
   * @return the payload byte length
   */
  public int payloadLength() {
    return payload.length;
  }
}

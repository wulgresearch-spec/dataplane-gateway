package io.reliabilityai.gateway.dataplane.streamguard.domain;

/**
 * The closed transport-failure taxonomy (Doc 18 §22). Neutral classes mapping to the Doc 12 §12
 * error taxonomy; a provider-native code is never surfaced (Doc 12 §16.10). The default on any
 * unknown is fail-closed surface (SG-INV, Doc 18 §53).
 */
public enum TransportFailureClass {
  /** Closed / ended without an explicit transport terminal (Doc 18 §21). */
  TRUNCATED,
  /** Missing sequence / unresolved reorder (Doc 18 §18/§19). */
  GAP,
  /** Invalid UTF-8 or framing (Doc 18 §13/§15) — fail closed, never substitute. */
  DECODE,
  /** CRC / framing integrity failure, e.g. event-stream CRC (Doc 18 §15). */
  CORRUPTION,
  /** Duplicate beyond the safe suppression window (Doc 18 §17/§33) — fail closed. */
  DUPLICATE,
  /** Replay of a prior stream/segment (Doc 18 §33) — fail closed. */
  REPLAY,
  /** Inactivity or total-duration budget exceeded (Doc 18 §24). */
  TIMEOUT,
  /** Provider / connection dropped (Doc 18 §25). */
  DISCONNECT,
  /** A bounded buffer / window was exceeded (Doc 18 §31/§32) — fail closed. */
  OVERFLOW,
  /** Client / pipeline cancellation (Doc 18 §27) — terminal. */
  CANCELLED,
  /** Unknown transport framing (Doc 18 §11) — fail closed. */
  UNSUPPORTED_FRAMING
}

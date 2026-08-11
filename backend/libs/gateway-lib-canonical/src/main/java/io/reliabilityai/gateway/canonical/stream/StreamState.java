package io.reliabilityai.gateway.canonical.stream;

/**
 * StreamGuard's stream lifecycle state (C3, Doc 18 §30, Doc 33 §10.3). The terminal verdict yields
 * authoritative usage (Doc 18 CV-5); finalization occurs only after a terminal state (Doc 32 §SFC).
 */
public enum StreamState {
  /** Stream opened, no chunks yet. */
  OPEN,
  /** Streaming in progress. */
  STREAMING,
  /** Cleanly completed — authoritative final usage available. */
  TERMINAL_COMPLETE,
  /** Truncated mid-stream — consumed-only usage (Doc 18 §26). */
  TERMINAL_PARTIAL,
  /** Failed mid-stream. */
  TERMINAL_FAILED,
  /** Cancelled mid-stream (consumed usage still metered, Doc 22 §24.1 CA-6). */
  TERMINAL_CANCELLED
}

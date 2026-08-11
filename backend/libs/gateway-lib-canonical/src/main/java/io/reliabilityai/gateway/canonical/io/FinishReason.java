package io.reliabilityai.gateway.canonical.io;

/**
 * Canonical, provider-neutral finish reason for a response (Doc 25 §6). Provider-native finish
 * signals are normalized to this set by the adapter (AD-007).
 */
public enum FinishReason {
  /** Natural completion. */
  STOP,
  /** Truncated by max length. */
  LENGTH,
  /** Ended to emit tool calls. */
  TOOL_CALLS,
  /** Blocked by content policy. */
  CONTENT_FILTER,
  /** Ended in error. */
  ERROR
}

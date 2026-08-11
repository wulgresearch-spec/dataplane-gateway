package io.reliabilityai.gateway.dataplane.observability.api;

/**
 * Structured-log severity (Doc 27 §5, Doc 14 §7). Metadata only — never a content-bearing message.
 */
public enum LogLevel {
  /** Debug. */
  DEBUG,
  /** Informational. */
  INFO,
  /** Warning. */
  WARN,
  /** Error. */
  ERROR
}

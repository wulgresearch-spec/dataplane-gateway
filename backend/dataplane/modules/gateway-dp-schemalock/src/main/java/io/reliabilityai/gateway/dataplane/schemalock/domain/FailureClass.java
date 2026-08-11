package io.reliabilityai.gateway.dataplane.schemalock.domain;

/**
 * The neutral structured-output failure taxonomy (Doc 17 §25, SL-D11) driving repair/retry/surface
 * decisions. Neutral (never a provider-native code, Doc 12 §16.10); the default on any unknown is
 * the most conservative outcome — <b>surface</b> (SL-INV, Doc 17 §48). Maps cleanly to the Doc 12
 * §12 error taxonomy.
 */
public enum FailureClass {
  /** Caller's schema is invalid — fail fast at compile, surface 400-class (not recoverable). */
  SCHEMA_INVALID(false),
  /** Output parsed but violates the schema — bounded repair/guided-retry then surface. */
  NON_CONFORMANT(true),
  /** Output not parseable as JSON — bounded guided-retry then surface. */
  MALFORMED(true),
  /** Stream ended incomplete (StreamGuard) — bounded retry then surface. */
  TRUNCATED(true),
  /** No viable strategy for schema+provider — downgrade or surface. */
  CAPABILITY_UNSUPPORTED(false),
  /** Complexity/size/depth cap hit — fail closed, surface (never recoverable). */
  RESOURCE_EXCEEDED(false),
  /** Upstream provider failure — per Retry Engine. */
  PROVIDER_ERROR(true),
  /** Upstream timeout — per Retry Engine. */
  TIMEOUT(true);

  private final boolean recoverable;

  FailureClass(final boolean recoverable) {
    this.recoverable = recoverable;
  }

  /**
   * Whether this class is bounded-recoverable via repair/guided-retry (Doc 17 §24.1 retry
   * criteria).
   *
   * @return {@code true} if recoverable
   */
  public boolean recoverable() {
    return recoverable;
  }
}

package io.reliabilityai.gateway.canonical.decision;

/**
 * Attempt classes, metered distinctly (Doc 23 §18, aligned with Doc 20 §24.1). Facts (what
 * happened), not decisions. Provider usage = sum of all attempts; the delivered one is customer
 * usage.
 */
public enum AttemptClass {
  /** The initial attempt. */
  INITIAL,
  /** A failover to another provider/route. */
  FAILOVER,
  /** A hedge attempt. */
  HEDGE,
  /** A SchemaLock-guided re-ask (Doc 17 §24). */
  GUIDED_RETRY,
  /** A transport-level retry. */
  TRANSPORT_RETRY,
  /** A cancelled attempt (consumed usage still metered, Doc 22 §24.1 CA-6). */
  CANCELLED,
  /** A failed attempt. */
  FAILED,
  /** The successful, delivered attempt (Doc 20 §25). */
  SUCCESSFUL
}

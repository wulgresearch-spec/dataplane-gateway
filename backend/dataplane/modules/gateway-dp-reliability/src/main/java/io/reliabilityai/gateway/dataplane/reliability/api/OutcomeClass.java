package io.reliabilityai.gateway.dataplane.reliability.api;

/**
 * The neutral classification of one provider attempt outcome (Doc 20 §6/§13). Provider-neutral — no
 * provider-native codes. An unknown outcome defaults to {@link #NON_RETRYABLE} (fail closed, §13).
 */
public enum OutcomeClass {
  /** The attempt succeeded. */
  SUCCESS,
  /** Transient transport/overload — retry the same candidate then fail over. */
  RETRYABLE,
  /** Permanent (4xx/auth/bad-request/content) — surface, never retry. */
  NON_RETRYABLE,
  /** The attempt timed out — retryable. */
  TIMEOUT,
  /** Provider throttle — retry honoring backoff. */
  RATE_LIMITED,
  /** The route circuit was open — skip to failover. */
  CIRCUIT_OPEN,
  /** Unrecoverable — surface. */
  FATAL
}

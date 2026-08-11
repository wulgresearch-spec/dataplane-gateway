package io.reliabilityai.gateway.dataplane.schemalock.api;

import io.reliabilityai.gateway.dataplane.schemalock.domain.FailureClass;

/**
 * The retry-coordination seam to the Retry Engine (Doc 17 §24/§44). SchemaLock <b>decides</b> a
 * retry is warranted (a recoverable class) and bounds its own attempts (Doc 17 §24.1); the Retry
 * Engine <b>owns</b> the shared ≤10% retry budget, backoff, and budget accounting (Doc 15 T-017).
 * This port lets the Retry Engine veto a retry when the shared budget is exhausted — SchemaLock
 * never loops unbounded (Doc 17 §24). Exhaustion ⇒ SchemaLock fails closed and surfaces (Doc 17
 * §24.1).
 */
public interface RetryDecisionPort {

  /**
   * Whether the Retry Engine permits another attempt within the shared budget (Doc 17 §24).
   *
   * @param nextAttempt the 1-based number of the attempt being requested
   * @param failureClass the recoverable failure class prompting the retry
   * @return {@code true} if a retry is permitted; {@code false} ⇒ surface (budget exhausted)
   */
  boolean shouldRetry(int nextAttempt, FailureClass failureClass);
}

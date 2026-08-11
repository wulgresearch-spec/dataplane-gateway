package io.reliabilityai.gateway.canonical.identity;

import io.reliabilityai.gateway.common.Preconditions;

/**
 * Execution identity — {@code (idempotencyKey, attemptId, correlationId)}, owned by Reliability
 * (Doc 20, Doc 33 §10.9). The replay/dedup anchor consumed by Usage Metering (Doc 23 §14.1), Cost
 * (Doc 22), Observability (Doc 27 §18.1), and the runtime sequence (Doc 32).
 *
 * <p>Immutable and never mutated (Doc 33 §TIC-5, Doc 27 RDD-1). A distinct {@code attemptId} per
 * attempt under the same {@code idempotencyKey} prevents retry double-counting.
 *
 * @param idempotencyKey the request idempotency key (Doc 12 §14)
 * @param attemptId the per-attempt id (Doc 20)
 * @param correlationId the request correlation id (Doc 27 §9)
 */
public record ExecutionIdentity(
    IdempotencyKey idempotencyKey, AttemptId attemptId, CorrelationId correlationId) {

  /** Compact constructor validating all components are present. */
  public ExecutionIdentity {
    Preconditions.requireNonNull(idempotencyKey, "idempotencyKey");
    Preconditions.requireNonNull(attemptId, "attemptId");
    Preconditions.requireNonNull(correlationId, "correlationId");
  }
}

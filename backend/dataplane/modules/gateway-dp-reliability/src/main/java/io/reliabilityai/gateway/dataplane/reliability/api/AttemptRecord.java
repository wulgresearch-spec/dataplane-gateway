package io.reliabilityai.gateway.dataplane.reliability.api;

import io.reliabilityai.gateway.canonical.io.ErrorCategory;
import io.reliabilityai.gateway.common.Preconditions;

/**
 * An immutable record of one attempt in the deterministic execution history (Doc 20 §6/RE-D9).
 * Carries the attempt number, the opaque route id, the neutral outcome class, and (on failure) the
 * neutral error category — never a provider name or content. The history is replay-safe:
 * reconstructing the same inputs yields the same sequence of records.
 *
 * @param attemptNumber the 1-based attempt number across the whole request
 * @param providerRouteRef the opaque route reference of the candidate attempted (never a provider
 *     name)
 * @param outcomeClass the neutral outcome classification
 * @param errorCategory the neutral error category on failure, or {@code null} on success/skip
 */
public record AttemptRecord(
    int attemptNumber,
    String providerRouteRef,
    OutcomeClass outcomeClass,
    ErrorCategory errorCategory) {

  /** Compact constructor validating required fields. */
  public AttemptRecord {
    if (attemptNumber < 1) {
      throw new IllegalArgumentException("attemptNumber must be >= 1");
    }
    Preconditions.requireNonBlank(providerRouteRef, "providerRouteRef");
    Preconditions.requireNonNull(outcomeClass, "outcomeClass");
  }
}

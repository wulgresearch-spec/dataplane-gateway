package io.reliabilityai.gateway.canonical.context;

import io.reliabilityai.gateway.canonical.decision.AttemptClass;
import io.reliabilityai.gateway.canonical.identity.AttemptId;
import io.reliabilityai.gateway.common.Preconditions;

/**
 * The per-attempt retry context (C2, Doc 33 §10.1, Doc 20). Immutable per attempt; retry policy is
 * solely Reliability's (Doc 25 §17.1).
 *
 * @param attemptId the per-attempt id (part of Doc 33 ExecutionIdentity)
 * @param attemptClass the attempt class (Doc 23 §18)
 * @param attemptNumber the 1-based attempt number
 */
public record RetryContext(AttemptId attemptId, AttemptClass attemptClass, int attemptNumber) {

  /** Compact constructor validating fields and attempt number. */
  public RetryContext {
    Preconditions.requireNonNull(attemptId, "attemptId");
    Preconditions.requireNonNull(attemptClass, "attemptClass");
    if (attemptNumber < 1) {
      throw new IllegalArgumentException("attemptNumber must be >= 1");
    }
  }
}

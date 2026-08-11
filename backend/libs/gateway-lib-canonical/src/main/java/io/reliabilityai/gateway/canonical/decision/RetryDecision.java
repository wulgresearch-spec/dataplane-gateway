package io.reliabilityai.gateway.canonical.decision;

import io.reliabilityai.gateway.common.Preconditions;

/**
 * Reliability's retry/failover decision (C2, Doc 20, Doc 33 §10.4). The adapter emits advisory
 * error hints (Doc 25 §16.1); Reliability alone decides (Doc 25 §17.1). Immutable; decision-replay
 * artifact.
 *
 * @param retry whether another attempt will be made
 * @param nextAttemptClass the class of the next attempt if retrying
 */
public record RetryDecision(boolean retry, AttemptClass nextAttemptClass) {

  /** Compact constructor validating the next-attempt class presence. */
  public RetryDecision {
    Preconditions.requireNonNull(nextAttemptClass, "nextAttemptClass");
  }
}

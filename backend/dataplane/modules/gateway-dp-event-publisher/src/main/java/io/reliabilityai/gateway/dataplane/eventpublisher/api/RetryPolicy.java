package io.reliabilityai.gateway.dataplane.eventpublisher.api;

import io.reliabilityai.gateway.common.Preconditions;

/**
 * A bounded retry policy (Doc 07). Caps broker-send attempts and classifies which faults are worth
 * retrying — a permanent fault fails fast to avoid a retry storm. Immutable and deterministic;
 * backoff timing is a scheduler concern layered by the caller and is not part of this correctness
 * contract.
 *
 * @param maxAttempts the maximum number of send attempts (&gt;= 1)
 */
public record RetryPolicy(int maxAttempts) {

  /** Compact constructor validating the attempt bound. */
  public RetryPolicy {
    if (maxAttempts < 1) {
      throw new IllegalArgumentException("maxAttempts must be >= 1");
    }
  }

  /**
   * Whether a failure is retryable: a {@link BrokerException} iff transient; any other runtime
   * exception is treated as permanent (fail fast, fail closed).
   *
   * @param failure the failure to classify
   * @return {@code true} if the attempt may be retried
   */
  public boolean isRetryable(final RuntimeException failure) {
    Preconditions.requireNonNull(failure, "failure");
    return failure instanceof BrokerException brokerException && brokerException.isRetryable();
  }
}

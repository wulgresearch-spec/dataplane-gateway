package io.reliabilityai.gateway.dataplane.reliability.domain;

import io.reliabilityai.gateway.canonical.io.ErrorCategory;
import io.reliabilityai.gateway.common.Preconditions;
import io.reliabilityai.gateway.dataplane.reliability.api.OutcomeClass;

/**
 * Maps a neutral {@link ErrorCategory} (from the adapter's neutral error mapping) to a reliability
 * {@link OutcomeClass} and the {@link RetryAction} to take (Doc 20 §13). Pure and total — every
 * category maps, and any unknown defaults to surface (fail closed, §13). The Engine never
 * interprets provider- native error bodies (RE-D10). Deterministic.
 */
public final class OutcomeClassifier {

  /** What the Engine does next after an attempt failure (Doc 20 §8/§9/§13). */
  public enum RetryAction {
    /** Retry the same candidate (transient), then fail over when retries are exhausted. */
    RETRY,
    /** Advance to the next candidate in the immutable list (this route cannot serve). */
    FAILOVER,
    /** Surface the failure — non-retryable, fail closed. */
    SURFACE
  }

  /**
   * The classification of an attempt failure.
   *
   * @param outcomeClass the neutral outcome class (recorded in history)
   * @param action the next action
   */
  public record Classification(OutcomeClass outcomeClass, RetryAction action) {}

  private OutcomeClassifier() {}

  /**
   * Classifies a neutral error category (Doc 20 §13).
   *
   * @param category the neutral error category
   * @return the classification (outcome class + action)
   */
  public static Classification classify(final ErrorCategory category) {
    Preconditions.requireNonNull(category, "category");
    return switch (category) {
      case TRANSPORT -> new Classification(OutcomeClass.RETRYABLE, RetryAction.RETRY);
      case TIMEOUT -> new Classification(OutcomeClass.TIMEOUT, RetryAction.RETRY);
      case RATE_LIMITED -> new Classification(OutcomeClass.RATE_LIMITED, RetryAction.RETRY);
      // A provider that is down cannot be recovered by retrying it — fail over to the next
      // candidate.
      case PROVIDER_UNAVAILABLE -> new Classification(OutcomeClass.RETRYABLE, RetryAction.FAILOVER);
      // 4xx / auth / content / malformed are deterministic per-request failures — surface, never
      // retry.
      case PROVIDER_REJECTED, AUTH_FAILED, CONTENT_FILTERED, MALFORMED_RESPONSE ->
          new Classification(OutcomeClass.NON_RETRYABLE, RetryAction.SURFACE);
      // Default on any unknown outcome is fail-closed surface (Doc 20 §13).
      case UNKNOWN -> new Classification(OutcomeClass.FATAL, RetryAction.SURFACE);
    };
  }
}

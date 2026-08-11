package io.reliabilityai.gateway.dataplane.embedding.api;

import io.reliabilityai.gateway.common.Preconditions;
import java.time.Instant;

/**
 * Whether a provider is worth calling right now (AD-030 §10).
 *
 * <p>Observed, never probed. Health here is a summary of what real traffic has just experienced,
 * not the result of a synthetic request — a health check that succeeds while production calls fail
 * is worse than no health check, because it is trusted.
 *
 * @param state the current view
 * @param consecutiveFailures how many attempts in a row have failed
 * @param lastSuccess when a call last succeeded, or epoch when never
 * @param lastFailureReason why the last failure happened, or empty when none
 */
public record EmbeddingHealth(
    State state,
    int consecutiveFailures,
    Instant lastSuccess,
    java.util.Optional<EmbeddingFailure> lastFailureReason) {

  /** How usable a provider is. */
  public enum State {
    /** Serving normally. */
    HEALTHY,
    /** Failing intermittently; still tried, but the operator should know. */
    DEGRADED,
    /** Failing consistently; the registry will prefer another provider if one exists. */
    UNAVAILABLE
  }

  /**
   * Validates the health view.
   *
   * @param state the current view
   * @param consecutiveFailures the failure streak
   * @param lastSuccess the last success instant
   * @param lastFailureReason the last failure kind
   */
  public EmbeddingHealth {
    Preconditions.requireNonNull(state, "state");
    Preconditions.requireNonNull(lastSuccess, "lastSuccess");
    Preconditions.requireNonNull(lastFailureReason, "lastFailureReason");
    if (consecutiveFailures < 0) {
      throw new IllegalArgumentException("consecutiveFailures must not be negative");
    }
  }

  /**
   * A provider that has never failed.
   *
   * @param at when it last succeeded
   * @return a healthy view
   */
  public static EmbeddingHealth healthy(final Instant at) {
    return new EmbeddingHealth(State.HEALTHY, 0, at, java.util.Optional.empty());
  }

  /**
   * Whether this provider should be called at all.
   *
   * @return true unless it is consistently failing
   */
  public boolean usable() {
    return state != State.UNAVAILABLE;
  }
}

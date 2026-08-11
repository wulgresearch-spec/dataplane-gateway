package io.reliabilityai.gateway.dataplane.embedding.domain;

import io.reliabilityai.gateway.common.Preconditions;
import io.reliabilityai.gateway.dataplane.embedding.api.EmbeddingFailure;
import io.reliabilityai.gateway.dataplane.embedding.api.EmbeddingHealth;
import java.time.Instant;

/**
 * What to do once retrying has stopped helping (AD-030 §7.3).
 *
 * <p>Retry decides whether to try again. This decides what the caller is told when trying again is
 * over, and it tracks the health that {@link EmbeddingProviderRegistry} uses to stop sending
 * traffic to something that is plainly down.
 *
 * <p><b>An exhausted failure is reported per input, never as an exception.</b> The write pipeline
 * above can then decide for itself whether a memory without an embedding is worth storing — which
 * is a policy question belonging to C17, not a question this module should pre-empt by throwing.
 *
 * <p>Health is a plain consecutive-failure count rather than a circuit breaker with half-open
 * probing. A breaker here would need a probe request, and a synthetic probe against a paid endpoint
 * is a bill that arrives whether or not anyone is using the feature. The registry simply prefers a
 * healthier provider when one exists. The limitation is real and recorded as B59: with a single
 * provider configured, an unavailable one is still called, because refusing without trying would
 * turn a transient outage into a permanent one.
 */
public final class EmbeddingFailurePolicy {

  /** After this many consecutive failures a provider is considered unavailable. */
  private final int unavailableAfter;

  /** After this many consecutive failures a provider is considered degraded. */
  private final int degradedAfter;

  /**
   * Creates a policy.
   *
   * @param degradedAfter consecutive failures before the provider is called degraded
   * @param unavailableAfter consecutive failures before it is called unavailable
   */
  public EmbeddingFailurePolicy(final int degradedAfter, final int unavailableAfter) {
    if (degradedAfter < 1 || unavailableAfter < degradedAfter) {
      throw new IllegalArgumentException("thresholds must be positive and ordered");
    }
    this.degradedAfter = degradedAfter;
    this.unavailableAfter = unavailableAfter;
  }

  /**
   * Degraded after three consecutive failures, unavailable after ten.
   *
   * @return the default policy
   */
  public static EmbeddingFailurePolicy defaults() {
    return new EmbeddingFailurePolicy(3, 10);
  }

  /**
   * The health view after a success.
   *
   * @param at when the success happened
   * @return a healthy view with the streak reset
   */
  public EmbeddingHealth onSuccess(final Instant at) {
    Preconditions.requireNonNull(at, "at");
    return EmbeddingHealth.healthy(at);
  }

  /**
   * The health view after a failure.
   *
   * @param previous the health before this failure
   * @param reason why it failed
   * @return the updated view
   */
  public EmbeddingHealth onFailure(final EmbeddingHealth previous, final EmbeddingFailure reason) {
    Preconditions.requireNonNull(previous, "previous");
    Preconditions.requireNonNull(reason, "reason");
    final int streak = previous.consecutiveFailures() + 1;
    final EmbeddingHealth.State state;
    if (streak >= unavailableAfter) {
      state = EmbeddingHealth.State.UNAVAILABLE;
    } else if (streak >= degradedAfter) {
      state = EmbeddingHealth.State.DEGRADED;
    } else {
      state = EmbeddingHealth.State.HEALTHY;
    }
    return new EmbeddingHealth(
        state, streak, previous.lastSuccess(), java.util.Optional.of(reason));
  }
}

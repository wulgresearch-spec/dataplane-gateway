package io.reliabilityai.gateway.dataplane.embedding.domain;

import io.reliabilityai.gateway.common.Preconditions;
import io.reliabilityai.gateway.dataplane.embedding.api.EmbeddingFailure;
import java.util.concurrent.ThreadLocalRandom;

/**
 * When to try again, and how long to wait (AD-030 §7).
 *
 * <p>What is retryable is decided by {@link EmbeddingFailure} and nothing else, so the rule lives
 * in one place rather than in a condition each adapter writes for itself. Rate limits, timeouts,
 * unavailability and network faults are repeated; rejections, authentication failures, oversized
 * inputs and dimension mismatches are not, because repeating them repeats the same mistake at the
 * same cost — and repeating a rejected credential is an authentication attack against your own
 * provider.
 *
 * <p>Backoff is exponential with <b>full</b> jitter. Equal backoff across many callers reconverges
 * them on the same instant and turns one rate limit into a standing wave of them; full jitter
 * spreads the retries across the whole window rather than the top half of it.
 *
 * <p>The jitter source is injectable. That is solely so tests are deterministic.
 */
public final class EmbeddingRetryPolicy {

  private final int maxAttempts;

  private final long baseDelayMillis;

  private final long maxDelayMillis;

  private final Jitter jitter;

  /** Supplies a fraction between zero and one. */
  @FunctionalInterface
  public interface Jitter {

    /**
     * The fraction of the computed ceiling to actually wait.
     *
     * @return a value within zero and one
     */
    double fraction();
  }

  /**
   * Creates a policy.
   *
   * @param maxAttempts total attempts including the first
   * @param baseDelayMillis the first backoff ceiling
   * @param maxDelayMillis the largest backoff ceiling
   * @param jitter the randomness source
   */
  public EmbeddingRetryPolicy(
      final int maxAttempts,
      final long baseDelayMillis,
      final long maxDelayMillis,
      final Jitter jitter) {
    this.jitter = Preconditions.requireNonNull(jitter, "jitter");
    if (maxAttempts < 1) {
      throw new IllegalArgumentException("maxAttempts must be at least one");
    }
    if (baseDelayMillis < 0 || maxDelayMillis < baseDelayMillis) {
      throw new IllegalArgumentException("delays must be non-negative and ordered");
    }
    this.maxAttempts = maxAttempts;
    this.baseDelayMillis = baseDelayMillis;
    this.maxDelayMillis = maxDelayMillis;
  }

  /**
   * Three attempts, 100 ms base, 2 s ceiling, real jitter.
   *
   * @return the default policy
   */
  public static EmbeddingRetryPolicy defaults() {
    return new EmbeddingRetryPolicy(
        3, 100L, 2_000L, () -> ThreadLocalRandom.current().nextDouble());
  }

  /**
   * A policy that never waits, for tests that care about counts rather than timing.
   *
   * @param maxAttempts total attempts
   * @return an immediate-retry policy
   */
  public static EmbeddingRetryPolicy immediate(final int maxAttempts) {
    return new EmbeddingRetryPolicy(maxAttempts, 0L, 0L, () -> 0.0);
  }

  /**
   * Whether another attempt should be made.
   *
   * @param reason why the last attempt failed
   * @param attemptsSoFar how many attempts have already been made
   * @return true when the failure is transient and attempts remain
   */
  public boolean shouldRetry(final EmbeddingFailure reason, final int attemptsSoFar) {
    return reason.retryable() && attemptsSoFar < maxAttempts;
  }

  /**
   * How long to wait before the next attempt.
   *
   * @param attemptsSoFar how many attempts have already been made
   * @return the delay in milliseconds
   */
  public long delayMillis(final int attemptsSoFar) {
    final int exponent = Math.max(0, Math.min(attemptsSoFar - 1, 20));
    final long ceiling = Math.min(maxDelayMillis, baseDelayMillis << exponent);
    return (long) (ceiling * Math.max(0.0, Math.min(1.0, jitter.fraction())));
  }

  /**
   * The total attempts this policy permits.
   *
   * @return the attempt limit
   */
  public int maxAttempts() {
    return maxAttempts;
  }
}

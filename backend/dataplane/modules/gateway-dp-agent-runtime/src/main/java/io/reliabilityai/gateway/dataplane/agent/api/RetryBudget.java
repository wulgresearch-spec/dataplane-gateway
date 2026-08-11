package io.reliabilityai.gateway.dataplane.agent.api;

/**
 * How many attempts one step has left.
 *
 * <p>Scoped to a single step, and therefore <em>not</em> a substitute for {@link RestartIntensity},
 * which is scoped to the run. Both are required: the budget stops one step spinning, the intensity
 * stops a loop spinning over many steps. Either alone leaves a hole.
 *
 * @param maxAttempts total attempts permitted, including the first; must be at least 1
 * @param consumed attempts already made; never above {@code maxAttempts}
 */
public record RetryBudget(int maxAttempts, int consumed) {

  /** One attempt and no retry. */
  public static final RetryBudget SINGLE_ATTEMPT = new RetryBudget(1, 0);

  /**
   * Validates the budget.
   *
   * @param maxAttempts total attempts permitted
   * @param consumed attempts already made
   */
  public RetryBudget {
    if (maxAttempts < 1) {
      throw new IllegalArgumentException("maxAttempts must be >= 1, was " + maxAttempts);
    }
    if (consumed < 0) {
      throw new IllegalArgumentException("consumed must be non-negative, was " + consumed);
    }
    if (consumed > maxAttempts) {
      throw new IllegalArgumentException(
          "consumed " + consumed + " exceeds maxAttempts " + maxAttempts);
    }
  }

  /**
   * Creates a fresh budget.
   *
   * @param maxAttempts total attempts permitted, including the first
   * @return an unconsumed budget
   */
  public static RetryBudget of(final int maxAttempts) {
    return new RetryBudget(maxAttempts, 0);
  }

  /**
   * Returns the budget after one more attempt.
   *
   * @return a new budget with one more attempt consumed
   * @throws IllegalStateException when the budget is already exhausted; the caller must check
   *     {@link #exhausted()} first, and a silent no-op here would let a step retry forever
   */
  public RetryBudget consume() {
    if (exhausted()) {
      throw new IllegalStateException("retry budget exhausted: " + consumed + "/" + maxAttempts);
    }
    return new RetryBudget(maxAttempts, consumed + 1);
  }

  /**
   * Reports whether any attempt remains.
   *
   * @return true when no further attempt is permitted
   */
  public boolean exhausted() {
    return consumed >= maxAttempts;
  }

  /**
   * Returns the attempts still available.
   *
   * @return the remaining attempt count, never negative
   */
  public int remaining() {
    return maxAttempts - consumed;
  }

  /**
   * Returns the 1-based number of the next attempt.
   *
   * @return the attempt number a scheduler would use for retry-delay computation
   */
  public int nextAttempt() {
    return consumed + 1;
  }
}

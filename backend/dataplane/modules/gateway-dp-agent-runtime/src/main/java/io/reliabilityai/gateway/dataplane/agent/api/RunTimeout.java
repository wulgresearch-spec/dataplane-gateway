package io.reliabilityai.gateway.dataplane.agent.api;

import io.reliabilityai.gateway.common.Preconditions;
import java.time.Duration;
import java.time.Instant;

/**
 * A run's wall-clock deadline (AD-025 §58, SM-8).
 *
 * <p>Enforced by a sweep over the store rather than by a per-run timer. A sweep is stateless and
 * idempotent and survives node loss; a million per-run timers is a scheduler nobody asked for, held
 * in a process that can die.
 *
 * <p><b>Recorded waiting does not consume the deadline.</b> A run parked on a durable timer or an
 * approval accrues no wall-clock, which is why {@code waitedNanos} is subtracted. Without that
 * carve-out every plan containing a pause would need a deadline longer than the slowest human,
 * which would defeat the bound for the rest of the run. The pause is bounded separately, by its own
 * expiry.
 *
 * @param startedAt when the run began
 * @param budget the wall-clock allowance
 * @param waitedNanos recorded time spent parked, excluded from the allowance
 */
public record RunTimeout(Instant startedAt, Duration budget, long waitedNanos) {

  /**
   * Validates the timeout.
   *
   * @param startedAt when the run began
   * @param budget the wall-clock allowance
   * @param waitedNanos recorded parked time
   */
  public RunTimeout {
    Preconditions.requireNonNull(startedAt, "startedAt");
    Preconditions.requireNonNull(budget, "budget");
    Preconditions.requireNonNegative(waitedNanos, "waitedNanos");
    if (budget.isZero() || budget.isNegative()) {
      throw new IllegalArgumentException("budget must be positive, was " + budget);
    }
  }

  /**
   * Creates a timeout that has not yet waited.
   *
   * @param startedAt when the run began
   * @param budget the wall-clock allowance
   * @return the timeout
   */
  public static RunTimeout starting(final Instant startedAt, final Duration budget) {
    return new RunTimeout(startedAt, budget, 0L);
  }

  /**
   * Returns the timeout with more parked time recorded.
   *
   * @param waited the additional parked duration
   * @return the updated timeout
   */
  public RunTimeout plusWaited(final Duration waited) {
    Preconditions.requireNonNull(waited, "waited");
    if (waited.isNegative()) {
      throw new IllegalArgumentException("waited must be non-negative, was " + waited);
    }
    return new RunTimeout(startedAt, budget, waitedNanos + waited.toNanos());
  }

  /**
   * Returns the wall-clock actually consumed at a given instant.
   *
   * @param now the instant to evaluate at, supplied by the caller from the injected clock
   * @return elapsed time minus recorded waiting, floored at zero
   */
  public Duration consumedAt(final Instant now) {
    Preconditions.requireNonNull(now, "now");
    final Duration elapsed = Duration.between(startedAt, now);
    final Duration counted = elapsed.minusNanos(waitedNanos);
    return counted.isNegative() ? Duration.ZERO : counted;
  }

  /**
   * Reports whether the deadline has passed.
   *
   * @param now the instant to evaluate at
   * @return true when the run must be terminated for timeout
   */
  public boolean expired(final Instant now) {
    return consumedAt(now).compareTo(budget) >= 0;
  }

  /**
   * Returns the wall-clock still available.
   *
   * @param now the instant to evaluate at
   * @return the remaining allowance, floored at zero
   */
  public Duration remainingAt(final Instant now) {
    final Duration left = budget.minus(consumedAt(now));
    return left.isNegative() ? Duration.ZERO : left;
  }

  /**
   * Returns the absolute deadline given the waiting recorded so far.
   *
   * <p>Moves later as the run parks, which is correct: the deadline bounds <em>work</em>, not
   * elapsed calendar time.
   *
   * @return the instant at which the run expires if it never parks again
   */
  public Instant deadline() {
    return startedAt.plus(budget).plusNanos(waitedNanos);
  }
}

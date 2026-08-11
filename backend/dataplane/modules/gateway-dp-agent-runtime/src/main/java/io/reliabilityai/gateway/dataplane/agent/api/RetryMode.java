package io.reliabilityai.gateway.dataplane.agent.api;

import java.time.Duration;

/**
 * How a retryable step's next attempt is delayed.
 *
 * <p>The schedule is computed here, as a pure function of the attempt number, rather than by a
 * timer the executor holds. A run waiting to retry is parked in the store with a recorded wake time
 * (AD-025 §37) — no thread, no memory, and any node may pick it up.
 */
public enum RetryMode {

  /** Retry with no delay. For failures where waiting cannot help. */
  IMMEDIATE,

  /** Delay grows as {@code base * 2^(attempt-1)}, clamped to the policy's ceiling. */
  EXPONENTIAL,

  /** Delay grows as {@code base * attempt}, clamped to the policy's ceiling. */
  LINEAR,

  /** No retry at any delay. The supervision strategy decides what happens instead. */
  NONE,

  /**
   * The circuit is open: retry is refused for now and the run parks until the circuit's cool-down.
   *
   * <p>Distinct from {@link #NONE} on purpose. {@code NONE} means "this will never be retried";
   * {@code CIRCUIT_OPEN} means "not now" — the difference decides whether the run terminates or
   * waits, and collapsing them would either strand recoverable runs or hammer a failing dependency.
   */
  CIRCUIT_OPEN;

  /**
   * Computes the delay before the given attempt.
   *
   * <p>Deterministic and jitter-free by design. Jitter is the right answer for a fleet of clients
   * hitting one dependency, but it is wall-clock randomness inside a replayable interpreter (AD-025
   * §45.2), so it belongs in the scheduler's claim timing rather than in a recorded step decision.
   *
   * @param attempt the 1-based attempt number being scheduled
   * @param base the policy's base delay
   * @param ceiling the policy's maximum delay
   * @return the delay to apply, never negative and never above {@code ceiling}
   */
  public Duration delayFor(final int attempt, final Duration base, final Duration ceiling) {
    if (attempt < 1) {
      throw new IllegalArgumentException("attempt must be >= 1, was " + attempt);
    }
    final Duration raw =
        switch (this) {
          case IMMEDIATE, NONE -> Duration.ZERO;
          case LINEAR -> base.multipliedBy(attempt);
          case CIRCUIT_OPEN -> ceiling;
          case EXPONENTIAL -> {
            // Shift rather than pow, and clamp the exponent: attempt is bounded by the retry
            // budget,
            // but a misconfigured budget must not overflow a long into a negative duration.
            final int shift = Math.min(attempt - 1, EXPONENT_CEILING);
            yield base.multipliedBy(1L << shift);
          }
        };
    return raw.compareTo(ceiling) > 0 ? ceiling : raw;
  }

  /** The largest doubling applied before clamping; 2^32 base delays already exceeds any ceiling. */
  private static final int EXPONENT_CEILING = 32;

  /**
   * Reports whether this mode ever produces another attempt.
   *
   * @return false only for {@link #NONE}
   */
  public boolean retries() {
    return this != NONE;
  }
}

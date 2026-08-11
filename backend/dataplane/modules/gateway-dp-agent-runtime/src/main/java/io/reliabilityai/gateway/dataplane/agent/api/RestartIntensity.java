package io.reliabilityai.gateway.dataplane.agent.api;

import io.reliabilityai.gateway.common.Preconditions;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

/**
 * At most {@code maxRestarts} restarts within {@code period} (AD-025 §44.3).
 *
 * <p><b>This is the bound that a per-step retry counter cannot provide, and no agent framework
 * studied has it.</b> Consider a plan that loops over a flaky step: the step fails, retries three
 * times, succeeds; the loop comes round; the step fails again. A per-step counter resets on each
 * iteration, so the run restarts forever and bills forever. A window over the whole run does not
 * reset, so it terminates.
 *
 * <p>The OTP documentation warns about the same failure from the other direction — a badly chosen
 * intensity lets children "keep restarting forever, filling your logs with crash reports until
 * someone intervenes manually". Here the consequence is a bill rather than a log file, which makes
 * the bound more important, not less.
 *
 * @param maxRestarts the number of restarts tolerated inside the window; must be non-negative
 * @param period the sliding window; must be positive
 */
public record RestartIntensity(int maxRestarts, Duration period) {

  /** Conservative default: three restarts a minute is a working step, four is a crash loop. */
  public static final RestartIntensity DEFAULT = new RestartIntensity(3, Duration.ofMinutes(1));

  /** Refuses every restart. Used by plans that must not retry anything. */
  public static final RestartIntensity NONE = new RestartIntensity(0, Duration.ofMinutes(1));

  /**
   * Validates the intensity.
   *
   * @param maxRestarts the restarts tolerated in the window
   * @param period the sliding window
   */
  public RestartIntensity {
    Preconditions.requireNonNull(period, "period");
    if (maxRestarts < 0) {
      throw new IllegalArgumentException("maxRestarts must be non-negative, was " + maxRestarts);
    }
    if (period.isZero() || period.isNegative()) {
      throw new IllegalArgumentException("period must be positive, was " + period);
    }
  }

  /**
   * Reports whether one more restart would exceed the intensity.
   *
   * <p>Counts the restarts that fall inside the window ending at {@code now}, then asks whether
   * adding one more would exceed the allowance. Restarts outside the window are ignored rather than
   * pruned — pruning is the caller's business, and a pure predicate is easier to reason about under
   * replay.
   *
   * @param restartTimes the recorded restart instants, in any order
   * @param now the instant the decision is being made at, supplied by the caller and never read
   *     from a clock inside this type (AD-025 §45.2)
   * @return true when the next restart must be refused
   */
  public boolean wouldExceed(final List<Instant> restartTimes, final Instant now) {
    Preconditions.requireNonNull(restartTimes, "restartTimes");
    Preconditions.requireNonNull(now, "now");
    return countWithin(restartTimes, now) >= maxRestarts;
  }

  /**
   * Counts the restarts inside the window ending at {@code now}.
   *
   * <p>The window is half-open at its start: a restart exactly {@code period} old has aged out.
   * This makes the count monotonically non-increasing as time passes with no new restarts, which is
   * what a sliding window must do.
   *
   * @param restartTimes the recorded restart instants
   * @param now the end of the window
   * @return how many restarts fall inside the window
   */
  public int countWithin(final List<Instant> restartTimes, final Instant now) {
    Preconditions.requireNonNull(restartTimes, "restartTimes");
    Preconditions.requireNonNull(now, "now");
    final Instant floor = now.minus(period);
    int count = 0;
    for (final Instant restart : restartTimes) {
      if (restart.isAfter(floor)) {
        count++;
      }
    }
    return count;
  }
}

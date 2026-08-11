package io.reliabilityai.gateway.dataplane.agent.api;

import io.reliabilityai.gateway.common.Preconditions;
import java.time.Duration;

/**
 * The five mandatory bounds (AD-025 §58, AGT-17).
 *
 * <p>All five, always, none infinite. Any subset leaves a hole: step count alone treats fifty cheap
 * steps and fifty expensive ones as the same; spend alone lets a cheap infinite loop run for days;
 * wall-clock alone lets a parallel fan-out spend a fortune in ninety seconds; depth alone bounds no
 * breadth; fan-out alone bounds nothing sequential. The tightest binds, and which one that is
 * varies by workload — which is itself the argument for having all five.
 *
 * <p>These are the premises of the termination proof (AD-025 §66). A run that could widen its own
 * bounds would have none, so nothing in this runtime exposes a way to raise them mid-run; only
 * {@link #tighten} exists, and it can only bring termination closer.
 *
 * @param maxSteps total steps across the run
 * @param maxDepth nested-run levels below this run
 * @param maxFanout concurrent branches per parallel construct
 * @param wallClock elapsed time, excluding recorded waiting periods
 * @param spendMicros total cost across the run tree, in micros of the tenant's currency
 */
public record RunBounds(
    int maxSteps, int maxDepth, int maxFanout, Duration wallClock, long spendMicros) {

  /**
   * The conservative defaults from AD-025 §58.1.
   *
   * <p>Real agentic work will hit these, and that is intended: an operator who raises them is
   * making a visible, audited, governed choice, while an operator who never thinks about them gets
   * a run that cannot hurt them.
   */
  public static final RunBounds DEFAULT = new RunBounds(50, 3, 5, Duration.ofHours(1), 5_000_000L);

  /** The hard ceiling no configured value may exceed (AD-025 §59). */
  public static final int ABSOLUTE_MAX_DEPTH = 8;

  /** The hard ceiling on step count, so a misconfiguration cannot produce an unbounded run. */
  public static final int ABSOLUTE_MAX_STEPS = 10_000;

  /**
   * Validates the bounds, refusing anything unbounded or above the absolute caps.
   *
   * @param maxSteps total steps across the run
   * @param maxDepth nested-run levels
   * @param maxFanout concurrent branches
   * @param wallClock elapsed-time ceiling
   * @param spendMicros spend ceiling
   */
  public RunBounds {
    Preconditions.requireNonNull(wallClock, "wallClock");
    if (maxSteps < 1 || maxSteps > ABSOLUTE_MAX_STEPS) {
      throw new IllegalArgumentException(
          "maxSteps must be in 1.." + ABSOLUTE_MAX_STEPS + ", was " + maxSteps);
    }
    if (maxDepth < 0 || maxDepth > ABSOLUTE_MAX_DEPTH) {
      throw new IllegalArgumentException(
          "maxDepth must be in 0.." + ABSOLUTE_MAX_DEPTH + ", was " + maxDepth);
    }
    if (maxFanout < 1) {
      throw new IllegalArgumentException("maxFanout must be >= 1, was " + maxFanout);
    }
    if (wallClock.isZero() || wallClock.isNegative()) {
      throw new IllegalArgumentException("wallClock must be positive, was " + wallClock);
    }
    Preconditions.requireNonNegative(spendMicros, "spendMicros");
  }

  /**
   * Returns bounds no looser than these, taking the stricter of each dimension.
   *
   * <p>This is the only mutation offered, and it is monotone: a child run's bounds are its parent's
   * tightened, never its parent's widened (AD-025 §30.2, §58.3).
   *
   * @param other the bounds to intersect with
   * @return the pointwise-stricter bounds
   */
  public RunBounds tighten(final RunBounds other) {
    Preconditions.requireNonNull(other, "other");
    return new RunBounds(
        Math.min(maxSteps, other.maxSteps),
        Math.min(maxDepth, other.maxDepth),
        Math.min(maxFanout, other.maxFanout),
        wallClock.compareTo(other.wallClock) <= 0 ? wallClock : other.wallClock,
        Math.min(spendMicros, other.spendMicros));
  }

  /**
   * Returns the bounds a child run at the next depth receives.
   *
   * <p>Depth, spend and wall-clock are tree-wide and so are inherited; step count is per-run,
   * because making it tree-wide would mean a parent's allowance shrinks every time it spawns a
   * child — so adding a child would silently starve the parent.
   *
   * @return the child's bounds
   * @throws IllegalStateException when this run may not delegate further
   */
  public RunBounds forChild() {
    if (maxDepth < 1) {
      throw new IllegalStateException("depth bound reached; this run may not delegate");
    }
    return new RunBounds(maxSteps, maxDepth - 1, maxFanout, wallClock, spendMicros);
  }

  /**
   * Reports whether a run at this depth may still delegate.
   *
   * @return true when a child run is permitted
   */
  public boolean mayDelegate() {
    return maxDepth >= 1;
  }
}

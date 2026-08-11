package io.reliabilityai.gateway.dataplane.agent.api;

import io.reliabilityai.gateway.common.Preconditions;

/**
 * A run's spend grant and what it has consumed (AD-025 §39).
 *
 * <p><b>The runtime prices nothing.</b> AD-025 AGT-7 puts pricing in the Cost Engine and metering
 * in the Usage Metering Engine; this type sums figures it is <em>told</em> and refuses admission
 * when the remainder will not cover the next step. The moment it computed a price of its own there
 * would be two cost figures for one run and a reconciliation problem.
 *
 * <p><b>The reserve</b> is the part of the grant no step may spend. Without it, a run that exhausts
 * its budget cannot afford the final audit, metering and notification that record the exhaustion —
 * so the run that most needs to explain itself is exactly the one that cannot.
 *
 * @param grantMicros the total grant, fixed at admission and never raised (AD-025 §39.3)
 * @param consumedMicros what has actually been spent, as reported by the layers that measured it
 * @param reserveMicros the unspendable tail held back for termination bookkeeping
 */
public record RunBudget(long grantMicros, long consumedMicros, long reserveMicros) {

  /** The fraction of a grant held back by {@link #of}, in percent. */
  private static final long RESERVE_PERCENT = 2;

  /**
   * Validates the budget.
   *
   * @param grantMicros the total grant
   * @param consumedMicros the amount spent
   * @param reserveMicros the unspendable tail
   */
  public RunBudget {
    Preconditions.requireNonNegative(grantMicros, "grantMicros");
    Preconditions.requireNonNegative(consumedMicros, "consumedMicros");
    Preconditions.requireNonNegative(reserveMicros, "reserveMicros");
    if (reserveMicros > grantMicros) {
      throw new IllegalArgumentException(
          "reserve " + reserveMicros + " exceeds grant " + grantMicros);
    }
  }

  /**
   * Creates a budget with the standard reserve held back.
   *
   * @param grantMicros the total grant
   * @return an unconsumed budget
   */
  public static RunBudget of(final long grantMicros) {
    return new RunBudget(grantMicros, 0L, grantMicros * RESERVE_PERCENT / 100L);
  }

  /**
   * Returns the budget after recording reported spend.
   *
   * <p>Consumption is <em>not</em> clamped at the grant. A step can overrun its allotment — the
   * layer below reports what it actually cost, and recording less than that would make the ledger a
   * comfortable fiction. {@link #overspent()} exists precisely so an overrun is visible rather than
   * rounded away.
   *
   * @param micros the spend to record; must be non-negative
   * @return the updated budget
   */
  public RunBudget consume(final long micros) {
    Preconditions.requireNonNegative(micros, "micros");
    return new RunBudget(grantMicros, consumedMicros + micros, reserveMicros);
  }

  /**
   * Returns what a step may still be allotted.
   *
   * @return the grant minus the reserve minus what is spent, floored at zero
   */
  public long spendableMicros() {
    return Math.max(0L, grantMicros - reserveMicros - consumedMicros);
  }

  /**
   * Returns what remains including the reserve.
   *
   * @return the grant minus what is spent, floored at zero
   */
  public long remainingMicros() {
    return Math.max(0L, grantMicros - consumedMicros);
  }

  /**
   * Reports whether a step of the given cost can be admitted.
   *
   * @param projectedMicros the projected cost of the next step
   * @return true when the spendable remainder covers it
   */
  public boolean admits(final long projectedMicros) {
    Preconditions.requireNonNegative(projectedMicros, "projectedMicros");
    return projectedMicros <= spendableMicros();
  }

  /**
   * Reports whether the spendable portion is gone.
   *
   * @return true when no further step may be admitted
   */
  public boolean exhausted() {
    return spendableMicros() == 0L;
  }

  /**
   * Reports whether actual spend has passed the whole grant, reserve included.
   *
   * @return true when the run cost more than it was granted
   */
  public boolean overspent() {
    return consumedMicros > grantMicros;
  }

  /**
   * Carves a child run's grant out of this run's remainder (AD-025 AGT-19).
   *
   * <p>Deduction, never addition. This is what bounds a whole run tree by its root's grant
   * regardless of the tree's shape: depth 3 with fan-out 5 is 125 leaf runs, and they collectively
   * cannot spend more than the root was given.
   *
   * @param requestedMicros the grant the child asked for
   * @return the child's budget, capped at this run's spendable remainder
   */
  public RunBudget carveChild(final long requestedMicros) {
    Preconditions.requireNonNegative(requestedMicros, "requestedMicros");
    return RunBudget.of(Math.min(requestedMicros, spendableMicros()));
  }
}

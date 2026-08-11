package io.reliabilityai.gateway.dataplane.agent.domain;

import io.reliabilityai.gateway.common.Preconditions;
import io.reliabilityai.gateway.dataplane.agent.api.FailureClass;
import io.reliabilityai.gateway.dataplane.agent.api.RunSnapshot;
import io.reliabilityai.gateway.dataplane.agent.api.Step;
import java.time.Instant;
import java.util.Optional;

/**
 * Checks a run against its five bounds before each step (AD-025 §58, §66).
 *
 * <p>This class is where the termination guarantee is actually enforced. The proof in AD-025 §66
 * rests on the bounds being finite, fixed at admission and checked before every step; the first two
 * are properties of {@code RunBounds}, and the third is this.
 *
 * <p><b>Checked before the step, never after.</b> Refusing after the work is done means paying for
 * the violation and then reporting it.
 */
public final class BoundsEvaluator {

  /**
   * Which bound refused, if any.
   *
   * @param bound the bound's name, used as a metrics dimension
   * @param failure the classified failure
   * @param detail the operator-facing explanation
   */
  public record Refusal(String bound, FailureClass failure, String detail) {

    /**
     * Validates the refusal.
     *
     * @param bound the bound's name, used as a metrics dimension
     * @param failure the classified failure
     * @param detail the operator-facing explanation
     */
    public Refusal {
      Preconditions.requireNonBlank(bound, "bound");
      Preconditions.requireNonNull(failure, "failure");
      Preconditions.requireNonBlank(detail, "detail");
    }
  }

  private BoundsEvaluator() {
    throw new AssertionError("no instances");
  }

  /**
   * Checks every bound before admitting a step.
   *
   * <p>Order matters only for which reason is reported when several are exhausted at once.
   * Wall-clock is checked first because a run past its deadline should say so rather than reporting
   * the budget it also happened to exhaust — the deadline is the bound the operator set expecting
   * it to bind.
   *
   * @param snapshot the run's folded state
   * @param step the step about to be admitted
   * @param now the instant to evaluate at, supplied from the injected clock
   * @return the refusal, or empty when every bound permits the step
   */
  public static Optional<Refusal> admit(
      final RunSnapshot snapshot, final Step step, final Instant now) {
    Preconditions.requireNonNull(snapshot, "snapshot");
    Preconditions.requireNonNull(step, "step");
    Preconditions.requireNonNull(now, "now");

    if (snapshot.timeout().expired(now)) {
      return Optional.of(
          new Refusal(
              "deadline",
              FailureClass.STEP_TIMEOUT,
              "wall-clock bound "
                  + snapshot.bounds().wallClock()
                  + " exhausted; consumed "
                  + snapshot.timeout().consumedAt(now)));
    }

    if (!snapshot.stepsRemain()) {
      return Optional.of(
          new Refusal(
              "steps",
              FailureClass.BOUND_EXCEEDED,
              "step bound " + snapshot.bounds().maxSteps() + " reached"));
    }

    if (!snapshot.budget().admits(step.budgetMicros())) {
      return Optional.of(
          new Refusal(
              "budget",
              FailureClass.BUDGET_EXHAUSTED,
              "step needs "
                  + step.budgetMicros()
                  + " micros, "
                  + snapshot.budget().spendableMicros()
                  + " spendable"));
    }

    return Optional.empty();
  }

  /**
   * Checks whether a run may start a child at the next depth.
   *
   * @param snapshot the parent run's state
   * @return the refusal, or empty when delegation is permitted
   */
  public static Optional<Refusal> admitChild(final RunSnapshot snapshot) {
    Preconditions.requireNonNull(snapshot, "snapshot");
    if (!snapshot.bounds().mayDelegate()) {
      return Optional.of(
          new Refusal(
              "depth",
              FailureClass.BOUND_EXCEEDED,
              "depth bound reached at depth " + snapshot.depth()));
    }
    if (snapshot.budget().exhausted()) {
      return Optional.of(
          new Refusal(
              "budget",
              FailureClass.BUDGET_EXHAUSTED,
              "no spendable budget to carve a child from"));
    }
    return Optional.empty();
  }

  /**
   * Checks whether a parallel construct's fan-out is permitted.
   *
   * @param snapshot the run's state
   * @param requestedBranches how many branches the plan asks to run at once
   * @return the refusal, or empty when the fan-out fits
   */
  public static Optional<Refusal> admitFanout(
      final RunSnapshot snapshot, final int requestedBranches) {
    Preconditions.requireNonNull(snapshot, "snapshot");
    if (requestedBranches > snapshot.bounds().maxFanout()) {
      return Optional.of(
          new Refusal(
              "fanout",
              FailureClass.BOUND_EXCEEDED,
              "fan-out " + requestedBranches + " exceeds bound " + snapshot.bounds().maxFanout()));
    }
    return Optional.empty();
  }

  /**
   * Checks the wall-clock bound alone, for the sweep that terminates parked runs.
   *
   * <p>AD-025 SM-8: a bound may terminate a run from any non-terminal state, the waiting ones
   * included. Without this a run parked on a timer would outlive its deadline indefinitely, and the
   * termination proof's step 8 would not hold.
   *
   * @param snapshot the run's state
   * @param now the instant to evaluate at
   * @return the refusal, or empty when the run still has time
   */
  public static Optional<Refusal> checkDeadline(final RunSnapshot snapshot, final Instant now) {
    Preconditions.requireNonNull(snapshot, "snapshot");
    Preconditions.requireNonNull(now, "now");
    if (snapshot.timeout().expired(now)) {
      return Optional.of(
          new Refusal("deadline", FailureClass.STEP_TIMEOUT, "wall-clock bound exhausted"));
    }
    return Optional.empty();
  }
}

package io.reliabilityai.gateway.dataplane.agent.api;

import io.reliabilityai.gateway.common.Preconditions;
import java.util.Optional;

/**
 * A run: its pinned plan and its folded state, together (AD-025 §24).
 *
 * <p>The pairing is the point. A snapshot alone cannot say what happens next, and a plan alone
 * cannot say what has happened; an executor needs exactly these two and nothing else, which is why
 * it can be stateless and why any node can advance any run.
 *
 * <p>The plan here is always the <em>pinned</em> version — resolved through {@code RunVersion},
 * never "the current plan" (AD-025 AGT-25). Publishing a new version while this run is in flight
 * cannot reach it.
 *
 * @param id the run identity
 * @param plan the pinned plan version
 * @param snapshot the state folded from the run's history
 */
public record Run(RunId id, Plan plan, RunSnapshot snapshot) {

  /**
   * Validates the run, including that the snapshot's pinned version matches the supplied plan.
   *
   * @param id the run identity
   * @param plan the pinned plan
   * @param snapshot the folded state
   */
  public Run {
    Preconditions.requireNonNull(id, "id");
    Preconditions.requireNonNull(plan, "plan");
    Preconditions.requireNonNull(snapshot, "snapshot");
    if (!id.equals(snapshot.runId())) {
      throw new IllegalArgumentException(
          "snapshot belongs to run " + snapshot.runId() + ", not " + id);
    }
    // Handing an executor the wrong plan version is the exact failure AGT-25 exists to prevent, and
    // it
    // would present as a replay divergence far from its cause. Refuse it where it is cheap to see.
    if (!plan.id().equals(snapshot.version().planId())
        || plan.version() != snapshot.version().planVersion()) {
      throw new IllegalArgumentException(
          "run pinned "
              + snapshot.version().planId()
              + " v"
              + snapshot.version().planVersion()
              + " but was given "
              + plan.id()
              + " v"
              + plan.version());
    }
  }

  /**
   * Returns the run's lifecycle state.
   *
   * @return the folded state
   */
  public RunState state() {
    return snapshot.state();
  }

  /**
   * Returns the step the plan says comes next.
   *
   * @return the next step, or empty when the cursor has run off the end of the plan
   */
  public Optional<Step> nextStep() {
    final int index = snapshot.cursor().planStepIndex();
    return index >= 0 && index < plan.size() ? Optional.of(plan.stepAt(index)) : Optional.empty();
  }

  /**
   * Reports whether the plan has been executed to its end.
   *
   * @return true when no step remains
   */
  public boolean planExhausted() {
    return snapshot.cursor().planStepIndex() >= plan.size();
  }

  /**
   * Returns the identity of the next step to execute.
   *
   * @return the derived step identity for the current cursor position
   */
  public StepId nextStepId() {
    return StepId.of(id, snapshot.cursor().executionIndex());
  }
}

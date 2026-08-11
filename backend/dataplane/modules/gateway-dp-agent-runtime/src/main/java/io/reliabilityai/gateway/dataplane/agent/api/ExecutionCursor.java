package io.reliabilityai.gateway.dataplane.agent.api;

import io.reliabilityai.gateway.common.Preconditions;

/**
 * Where a run has got to (AD-025 §25).
 *
 * <p>A cursor is never stored as the authority on a run's position — it is <em>derived</em> by
 * folding the history (AD-025 AGT-15). Keeping it as a separate durable field would create a second
 * source of truth that can drift from the first, and the drift would be silent.
 *
 * @param planStepIndex the index into the plan's step list of the next step to consider
 * @param executionIndex the number of steps already placed in the execution graph; this, not the
 *     plan index, is what {@link StepId} is derived from, so a loop that revisits a plan node
 *     produces distinct step ids (AD-025 §52.2)
 * @param attempt the 1-based attempt number of the step at {@code planStepIndex}
 * @param historyOffset the number of events folded to produce this cursor
 */
public record ExecutionCursor(
    int planStepIndex, int executionIndex, int attempt, long historyOffset) {

  /** The position of a run that has been created and has executed nothing. */
  public static final ExecutionCursor START = new ExecutionCursor(0, 0, 1, 0L);

  /**
   * Validates the cursor.
   *
   * @param planStepIndex the plan position
   * @param executionIndex the execution-graph position
   * @param attempt the 1-based attempt number
   * @param historyOffset the folded event count
   */
  public ExecutionCursor {
    if (planStepIndex < 0) {
      throw new IllegalArgumentException("planStepIndex must be non-negative");
    }
    if (executionIndex < 0) {
      throw new IllegalArgumentException("executionIndex must be non-negative");
    }
    if (attempt < 1) {
      throw new IllegalArgumentException("attempt must be >= 1, was " + attempt);
    }
    Preconditions.requireNonNegative(historyOffset, "historyOffset");
  }

  /**
   * Returns the cursor after a step succeeded and the plan moves on.
   *
   * @return a cursor at the next plan step, first attempt
   */
  public ExecutionCursor advance() {
    return new ExecutionCursor(planStepIndex + 1, executionIndex + 1, 1, historyOffset);
  }

  /**
   * Returns the cursor after a step failed and will be retried.
   *
   * <p>The plan index stays put and the execution index advances, so the retry is a distinct node
   * in the execution graph with its own step id — which is what makes a retried step visible in the
   * history as its own entry rather than overwriting the failure.
   *
   * @return a cursor at the same plan step, next attempt
   */
  public ExecutionCursor retry() {
    return new ExecutionCursor(planStepIndex, executionIndex + 1, attempt + 1, historyOffset);
  }

  /**
   * Returns the cursor after a branch or restart jumps to another plan step.
   *
   * @param targetPlanIndex the plan index to jump to
   * @return a cursor at the target, first attempt
   */
  public ExecutionCursor jumpTo(final int targetPlanIndex) {
    if (targetPlanIndex < 0) {
      throw new IllegalArgumentException("targetPlanIndex must be non-negative");
    }
    return new ExecutionCursor(targetPlanIndex, executionIndex + 1, 1, historyOffset);
  }

  /**
   * Returns the cursor with its history offset moved on.
   *
   * @param offset the new folded event count; must not go backwards
   * @return the updated cursor
   */
  public ExecutionCursor atOffset(final long offset) {
    if (offset < historyOffset) {
      throw new IllegalArgumentException(
          "history offset must not go backwards: " + historyOffset + " -> " + offset);
    }
    return new ExecutionCursor(planStepIndex, executionIndex, attempt, offset);
  }
}

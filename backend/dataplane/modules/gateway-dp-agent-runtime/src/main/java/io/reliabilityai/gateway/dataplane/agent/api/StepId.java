package io.reliabilityai.gateway.dataplane.agent.api;

import io.reliabilityai.gateway.common.Preconditions;

/**
 * Identifies one step of one run (AD-025 §51).
 *
 * <p><b>Derived, never generated.</b> AD-025 §45.2 forbids the plan interpreter from generating
 * identifiers, because a random id would make two replays of the same history produce different
 * identifiers and so break {@code REPLAY_DIVERGENCE} detection. A step id is therefore a pure
 * function of the run id and the step's index in the execution graph.
 *
 * @param runId the owning run
 * @param index the zero-based position of this step in the execution graph, not in the plan; a loop
 *     that runs a plan node three times produces three distinct indices (AD-025 §52.2)
 */
public record StepId(RunId runId, int index) {

  /**
   * Validates the identifier.
   *
   * @param runId the owning run
   * @param index the execution-graph position
   */
  public StepId {
    Preconditions.requireNonNull(runId, "runId");
    if (index < 0) {
      throw new IllegalArgumentException("index must be non-negative, was " + index);
    }
  }

  /**
   * Derives a step identifier.
   *
   * @param runId the owning run
   * @param index the execution-graph position
   * @return the identifier
   */
  public static StepId of(final RunId runId, final int index) {
    return new StepId(runId, index);
  }

  /**
   * Returns the flat rendering used in logs, traces and the durable journal.
   *
   * @return {@code <runId>#<index>}
   */
  public String value() {
    return runId.value() + "#" + index;
  }

  @Override
  public String toString() {
    return value();
  }
}

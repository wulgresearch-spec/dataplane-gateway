package io.reliabilityai.gateway.dataplane.agent.api;

import io.reliabilityai.gateway.common.Preconditions;
import java.time.Instant;

/**
 * A recorded step boundary (AD-025 §34).
 *
 * <p><b>There is no separate checkpointing mechanism.</b> Recording a step's terminal event
 * <em>is</em> the checkpoint; this type is the addressable name for such a boundary, not a second
 * artifact written beside the history. That collapse removes a whole category of question — how
 * often to checkpoint, what to do when a checkpoint and the log disagree — because there is nothing
 * to disagree with.
 *
 * <p>Checkpoints exist only at step boundaries, never inside a step. A partially-executed session
 * is not a resumable state (AD-024 F-24 makes sessions node-bound), so a mid-step checkpoint would
 * record a position nobody can resume from — and recovery would trust it.
 *
 * @param runId the run this boundary belongs to
 * @param historyOffset the number of events durably recorded at this boundary
 * @param cursor the run's position at this boundary
 * @param state the run's state at this boundary
 * @param at the recorded instant, taken from the injected clock at the time of writing
 */
public record Checkpoint(
    RunId runId, long historyOffset, ExecutionCursor cursor, RunState state, Instant at) {

  /**
   * Validates the checkpoint.
   *
   * @param runId the owning run
   * @param historyOffset the durable event count
   * @param cursor the run position
   * @param state the run state
   * @param at the recorded instant
   */
  public Checkpoint {
    Preconditions.requireNonNull(runId, "runId");
    Preconditions.requireNonNull(cursor, "cursor");
    Preconditions.requireNonNull(state, "state");
    Preconditions.requireNonNull(at, "at");
    Preconditions.requireNonNegative(historyOffset, "historyOffset");
  }

  /**
   * Reports whether this boundary is one a recovering node can resume from.
   *
   * <p>A checkpoint in a terminal state is a record of an ending, not a place to restart.
   *
   * @return true when the run can be advanced from here
   */
  public boolean resumable() {
    return !state.terminal();
  }
}

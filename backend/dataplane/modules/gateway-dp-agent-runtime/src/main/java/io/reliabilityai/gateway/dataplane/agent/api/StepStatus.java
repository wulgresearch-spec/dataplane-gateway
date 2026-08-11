package io.reliabilityai.gateway.dataplane.agent.api;

/**
 * The status of one step of one run.
 *
 * <p>{@link #SCHEDULED} is the load-bearing one. AD-025 §14 requires a step to be recorded as
 * scheduled <em>before</em> it executes and recorded as terminal <em>after</em>; a history whose
 * tail is {@code SCHEDULED} therefore means the executor died mid-step and the outcome is unknown.
 * That ambiguity is not a defect of the record — it is the honest state of the world, and AD-025
 * §35.2 defines how it is resolved.
 */
public enum StepStatus {

  /** In the plan, not yet reached. */
  PENDING,

  /** Durably recorded as about to execute. An interrupted step is one that stayed here. */
  SCHEDULED,

  /** Executing in this process right now. Never durable — a crash cannot leave this on disk. */
  RUNNING,

  /** Executed and produced a result. */
  SUCCEEDED,

  /** Executed and failed. */
  FAILED,

  /** Cancelled before or during execution. */
  CANCELLED,

  /** Not executed because supervision chose {@code SKIP_STEP}. */
  SKIPPED,

  /** Exceeded its own deadline. */
  TIMED_OUT;

  /**
   * Reports whether this status ends the step.
   *
   * @return true when no further transition of this step is possible
   */
  public boolean terminal() {
    return this == SUCCEEDED
        || this == FAILED
        || this == CANCELLED
        || this == SKIPPED
        || this == TIMED_OUT;
  }
}

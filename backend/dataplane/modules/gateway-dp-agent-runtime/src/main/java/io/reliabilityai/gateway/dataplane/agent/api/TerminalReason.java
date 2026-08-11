package io.reliabilityai.gateway.dataplane.agent.api;

/**
 * The closed set of ways a run can end (AD-025 §67.3).
 *
 * <p>Every {@link FailureClass} maps to exactly one of these, so a run's terminal reason is always
 * explicable without reading its history. AD-024 §46.1 established the closed-terminal-reason
 * discipline at the session level; this is its run-level analogue.
 */
public enum TerminalReason {

  /** The plan finished and every step succeeded. */
  COMPLETED_SUCCESS(RunState.COMPLETED),

  /** The plan finished, but supervision skipped at least one failed step. */
  COMPLETED_PARTIAL(RunState.COMPLETED),

  /** Governance refused the run before any step ran. */
  FAILED_ADMISSION(RunState.FAILED),

  /** The plan was missing or invalid. */
  FAILED_PLAN(RunState.FAILED),

  /** A step failed and supervision did not absorb it. */
  FAILED_STEP(RunState.FAILED),

  /** A child run failed and escalated. */
  FAILED_CHILD(RunState.FAILED),

  /** Trifecta violation or replay divergence. Parked, not merely failed. */
  FAILED_INTEGRITY(RunState.DEAD_LETTER),

  /** Restart intensity exhausted. Parked for human attention rather than retried again. */
  FAILED_RESTART_INTENSITY(RunState.DEAD_LETTER),

  /** A bound other than budget or wall-clock was exhausted. */
  TERMINATED_BOUND(RunState.FAILED),

  /** The budget grant was consumed. */
  TERMINATED_BUDGET(RunState.FAILED),

  /** Policy revoked the run's authority mid-flight. */
  TERMINATED_POLICY(RunState.FAILED),

  /** An approval expired or was denied. */
  TERMINATED_APPROVAL(RunState.FAILED),

  /** The wall-clock bound expired. */
  TERMINATED_TIMEOUT(RunState.TIMED_OUT),

  /** The caller cancelled. */
  CANCELLED_CALLER(RunState.CANCELLED),

  /** A parent run cancelled or terminated. */
  CANCELLED_PARENT(RunState.CANCELLED),

  /** An operator cancelled. */
  CANCELLED_OPERATOR(RunState.CANCELLED),

  /** The tenant was suspended. */
  CANCELLED_TENANT(RunState.CANCELLED);

  private final RunState state;

  TerminalReason(final RunState state) {
    this.state = state;
  }

  /**
   * Returns the terminal state a run reaches for this reason.
   *
   * <p>The mapping is fixed here rather than decided by callers so that two code paths cannot
   * record the same reason under different states.
   *
   * @return the absorbing state, always one of {@link RunState#TERMINAL_STATES}
   */
  public RunState state() {
    return state;
  }

  /**
   * Reports whether the run produced a usable result.
   *
   * @return true for the two completed reasons
   */
  public boolean successful() {
    return this == COMPLETED_SUCCESS || this == COMPLETED_PARTIAL;
  }

  /**
   * Maps a failure class to the reason a run terminating on it must record.
   *
   * @param failure the classified failure
   * @return the single terminal reason for that class
   */
  public static TerminalReason forFailure(final FailureClass failure) {
    return switch (failure) {
      case ADMISSION_DENIED -> FAILED_ADMISSION;
      case PLAN_INVALID -> FAILED_PLAN;
      case BOUND_EXCEEDED -> TERMINATED_BOUND;
      case BUDGET_EXHAUSTED -> TERMINATED_BUDGET;
      case STEP_TIMEOUT -> TERMINATED_TIMEOUT;
      case CHILD_FAILED -> FAILED_CHILD;
      case APPROVAL_EXPIRED, APPROVAL_DENIED -> TERMINATED_APPROVAL;
      case TRIFECTA_VIOLATION, REPLAY_DIVERGENCE -> FAILED_INTEGRITY;
      case RESTART_INTENSITY_EXCEEDED -> FAILED_RESTART_INTENSITY;
      case POLICY_REVOKED -> TERMINATED_POLICY;
      case TENANT_SUSPENDED -> CANCELLED_TENANT;
      case CANCELLED -> CANCELLED_CALLER;
      case STEP_TRANSIENT,
              STEP_PERMANENT,
              STEP_DENIED,
              TOOL_FAILURE,
              STORE_UNAVAILABLE,
              INTERRUPTED_UNRESOLVED ->
          FAILED_STEP;
    };
  }
}

package io.reliabilityai.gateway.dataplane.agent.api;

/**
 * The closed failure taxonomy (AD-025 §67.1).
 *
 * <p><b>Closed on purpose.</b> An open taxonomy degenerates into a free-text reason field, and free
 * text cannot be alerted on, aggregated or tested. Adding a class is an amendment to AD-025, not a
 * code change someone makes in passing.
 *
 * <p>Retryability is a property of the class, not a judgement made at each call site — otherwise
 * the same failure is retried in one place and not in another, and no operator can predict
 * behaviour.
 */
public enum FailureClass {

  /** Governance refused the run at admission. */
  ADMISSION_DENIED(false),

  /** The plan version is missing, unreadable, or failed validation. */
  PLAN_INVALID(false),

  /** One of the five mandatory bounds was exhausted (AD-025 §58). */
  BOUND_EXCEEDED(false),

  /** The run's budget grant was consumed. */
  BUDGET_EXHAUSTED(false),

  /** A transient failure that survived the reliability engine's own attempt retries. */
  STEP_TRANSIENT(true),

  /** A deterministic step failure: malformed output, schema violation, bad arguments. */
  STEP_PERMANENT(false),

  /** Governance denied a turn inside the step. Retrying would burn budget to be denied again. */
  STEP_DENIED(false),

  /** The step exceeded its own deadline. Retryable only when the step is declared idempotent. */
  STEP_TIMEOUT(true),

  /** A tool errored. Retryability is refined by the tool's own idempotence declaration. */
  TOOL_FAILURE(true),

  /** A child run failed and escalated to this run. */
  CHILD_FAILED(false),

  /** A human approval expired unanswered. Silence is never consent (AD-025 APC-1). */
  APPROVAL_EXPIRED(false),

  /** A human refused the approval. */
  APPROVAL_DENIED(false),

  /** The run-level Rule of Two was violated across sessions (AD-025 §60.2). Never retryable. */
  TRIFECTA_VIOLATION(false),

  /** Policy changed mid-run and revoked the run's authority (AD-025 §40.3). */
  POLICY_REVOKED(false),

  /** The interpreter and the history disagree. Never retryable — the run's integrity is gone. */
  REPLAY_DIVERGENCE(false),

  /** Restart intensity exhausted: N restarts inside period P (AD-025 §44.3). */
  RESTART_INTENSITY_EXCEEDED(false),

  /** The run store was unreachable. The run stalls rather than proceeding unrecorded. */
  STORE_UNAVAILABLE(true),

  /** An interrupted step whose real outcome could not be determined (AD-025 §35.2). */
  INTERRUPTED_UNRESOLVED(false),

  /** Cancelled from outside. */
  CANCELLED(false),

  /** The tenant was suspended. */
  TENANT_SUSPENDED(false);

  private final boolean retryable;

  FailureClass(final boolean retryable) {
    this.retryable = retryable;
  }

  /**
   * Reports whether a step that failed this way may be retried at all.
   *
   * <p>A {@code true} here is necessary but not sufficient: the supervisor additionally refuses to
   * retry a step holding a non-idempotent capability (AD-025 §33.3), because a duplicated
   * irreversible action is a customer incident while a non-retried transient failure is an
   * inconvenience.
   *
   * @return true when retry is permitted for this class
   */
  public boolean retryable() {
    return retryable;
  }

  /**
   * Reports whether this failure means the run's integrity assumptions are broken.
   *
   * <p>These are never retried and never absorbed by a supervision strategy: retrying into a
   * known-corrupt state produces a run whose history no longer explains its behaviour.
   *
   * @return true for the integrity classes
   */
  public boolean integrity() {
    return this == TRIFECTA_VIOLATION || this == REPLAY_DIVERGENCE;
  }
}

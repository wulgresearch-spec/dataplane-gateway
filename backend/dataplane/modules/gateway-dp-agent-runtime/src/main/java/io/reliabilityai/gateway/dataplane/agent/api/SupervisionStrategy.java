package io.reliabilityai.gateway.dataplane.agent.api;

/**
 * What a supervisor does when a step fails (AD-025 §44.2).
 *
 * <p>Adapted from Erlang/OTP, which is the only mature system studied that treats failure handling
 * as a <em>declared policy</em> rather than as defensive code scattered through the workers. The
 * correspondence: {@link #RETRY_STEP} is {@code one_for_one}, {@link #RESTART_FROM} is {@code
 * rest_for_one}, {@link #ESCALATE} is a supervisor failing upward.
 *
 * <p><b>There is deliberately no {@code one_for_all} analogue.</b> Restarting every completed step
 * because a later one failed is cheap in Erlang, where a restarted process has no side effects and
 * costs nothing. Here every completed step has already called a model, spent money and possibly
 * changed the world. Restarting them is not a restart; it is doing it all again and paying twice.
 */
public enum SupervisionStrategy {

  /**
   * Terminate the run on any step failure.
   *
   * <p>The default, and deliberately the strictest. A plan author who wants a softer policy states
   * so; a plan author who says nothing gets the behaviour that cannot silently produce a wrong
   * result.
   */
  FAIL_RUN,

  /**
   * Re-execute the failed step as a new session, bounded by the retry budget and restart intensity.
   */
  RETRY_STEP,

  /** Record the failure and continue. The run can still complete, as {@code COMPLETED_PARTIAL}. */
  SKIP_STEP,

  /** Return to a declared earlier step and re-run forward from there. */
  RESTART_FROM,

  /** Fail this run and let the parent's supervisor decide. At the root this terminates the run. */
  ESCALATE,

  /** Run a declared compensation step, then fail. For steps with reversible external effects. */
  COMPENSATE;

  /**
   * Reports whether this strategy can keep the run alive.
   *
   * @return true for the strategies that may produce another step
   */
  public boolean survivable() {
    return this == RETRY_STEP || this == SKIP_STEP || this == RESTART_FROM;
  }
}

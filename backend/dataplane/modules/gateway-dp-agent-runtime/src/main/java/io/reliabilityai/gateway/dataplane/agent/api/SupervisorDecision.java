package io.reliabilityai.gateway.dataplane.agent.api;

import io.reliabilityai.gateway.common.Preconditions;
import java.time.Duration;
import java.time.Instant;

/**
 * What the supervisor decided to do about a step outcome (AD-025 §44).
 *
 * <p><b>Every decision is recorded</b> (AD-025 AGT-20, SVC-4). There is no variant meaning "quietly
 * ignore": even {@link Skip} produces a history event, because a child failure that silently
 * becomes a parent success is the failure mode a supervision model exists to prevent.
 */
public sealed interface SupervisorDecision
    permits SupervisorDecision.Advance,
        SupervisorDecision.RetryStep,
        SupervisorDecision.Skip,
        SupervisorDecision.JumpTo,
        SupervisorDecision.Park,
        SupervisorDecision.Compensate,
        SupervisorDecision.Terminate {

  /**
   * Returns a short, stable label for metrics and audit.
   *
   * @return the decision's kind label
   */
  String label();

  /**
   * Reports whether the run continues.
   *
   * @return true when the run remains non-terminal after this decision
   */
  default boolean continues() {
    return !(this instanceof Terminate);
  }

  /** The step succeeded; move on. */
  record Advance() implements SupervisorDecision {
    @Override
    public String label() {
      return "advance";
    }
  }

  /**
   * Re-execute the failed step after a delay, as a brand-new session.
   *
   * <p>A retry is never a resumption. AD-025 AGT-10: the retried step opens a new C13 session and
   * therefore produces new pipeline executions, each fully governed. Nothing is reused from the
   * failed attempt — not the authorization, not the route, not the credential.
   *
   * @param attempt the 1-based number of the attempt being scheduled
   * @param delay how long to park before it
   */
  record RetryStep(int attempt, Duration delay) implements SupervisorDecision {

    /**
     * Validates the decision.
     *
     * @param attempt the attempt number
     * @param delay the pre-attempt delay
     */
    public RetryStep {
      Preconditions.requireNonNull(delay, "delay");
      if (attempt < 2) {
        throw new IllegalArgumentException("a retry is attempt >= 2, was " + attempt);
      }
      if (delay.isNegative()) {
        throw new IllegalArgumentException("delay must be non-negative, was " + delay);
      }
    }

    @Override
    public String label() {
      return "retry";
    }
  }

  /**
   * Record the failure and continue. The run can still complete, as {@code COMPLETED_PARTIAL}.
   *
   * @param failure the failure being absorbed, recorded so the skip is explicable
   */
  record Skip(FailureClass failure) implements SupervisorDecision {

    /**
     * Validates the decision.
     *
     * @param failure the absorbed failure
     */
    public Skip {
      Preconditions.requireNonNull(failure, "failure");
    }

    @Override
    public String label() {
      return "skip";
    }
  }

  /**
   * Continue at a named step: a branch taken, or a {@code RESTART_FROM} rewind.
   *
   * @param targetStep the plan-local step name to continue at
   * @param rewind true when this is a supervision rewind rather than a plan branch
   */
  record JumpTo(String targetStep, boolean rewind) implements SupervisorDecision {

    /**
     * Validates the decision.
     *
     * @param targetStep the target step name
     * @param rewind whether this is a supervision rewind
     */
    public JumpTo {
      Preconditions.requireNonBlank(targetStep, "targetStep");
    }

    @Override
    public String label() {
      return rewind ? "restart-from" : "branch";
    }
  }

  /**
   * Park the run until a recorded instant, holding nothing.
   *
   * @param until the instant the run becomes claimable again
   */
  record Park(Instant until) implements SupervisorDecision {

    /**
     * Validates the decision.
     *
     * @param until the wake instant
     */
    public Park {
      Preconditions.requireNonNull(until, "until");
    }

    @Override
    public String label() {
      return "park";
    }
  }

  /**
   * Run a declared compensation step, then terminate.
   *
   * @param compensationStep the plan-local name of the compensating step
   * @param failure the failure being compensated for
   */
  record Compensate(String compensationStep, FailureClass failure) implements SupervisorDecision {

    /**
     * Validates the decision.
     *
     * @param compensationStep the compensating step name
     * @param failure the failure being compensated
     */
    public Compensate {
      Preconditions.requireNonBlank(compensationStep, "compensationStep");
      Preconditions.requireNonNull(failure, "failure");
    }

    @Override
    public String label() {
      return "compensate";
    }
  }

  /**
   * End the run.
   *
   * @param reason the terminal reason, which fixes the terminal state
   * @param failure the failure that caused it, absent for a successful completion
   * @param detail a short operator-facing explanation
   */
  record Terminate(TerminalReason reason, java.util.Optional<FailureClass> failure, String detail)
      implements SupervisorDecision {

    /**
     * Validates the decision.
     *
     * @param reason the terminal reason
     * @param failure the causing failure, if any
     * @param detail the explanation
     */
    public Terminate {
      Preconditions.requireNonNull(reason, "reason");
      Preconditions.requireNonNull(failure, "failure");
      Preconditions.requireNonBlank(detail, "detail");
    }

    /**
     * Terminates successfully.
     *
     * @param reason the completion reason
     * @param detail the explanation
     * @return the decision
     */
    public static Terminate success(final TerminalReason reason, final String detail) {
      return new Terminate(reason, java.util.Optional.empty(), detail);
    }

    /**
     * Terminates on a failure, deriving the reason from the class so the mapping stays
     * single-sourced.
     *
     * @param failure the causing failure
     * @param detail the explanation
     * @return the decision
     */
    public static Terminate failure(final FailureClass failure, final String detail) {
      return new Terminate(
          TerminalReason.forFailure(failure), java.util.Optional.of(failure), detail);
    }

    @Override
    public String label() {
      return "terminate";
    }
  }
}

package io.reliabilityai.gateway.dataplane.agent.application;

import io.reliabilityai.gateway.common.Preconditions;
import io.reliabilityai.gateway.dataplane.agent.api.FailureClass;
import io.reliabilityai.gateway.dataplane.agent.api.RestartPolicy;
import io.reliabilityai.gateway.dataplane.agent.api.RunSnapshot;
import io.reliabilityai.gateway.dataplane.agent.api.Step;
import io.reliabilityai.gateway.dataplane.agent.api.StepResult;
import io.reliabilityai.gateway.dataplane.agent.api.SupervisionStrategy;
import io.reliabilityai.gateway.dataplane.agent.api.SupervisorDecision;
import io.reliabilityai.gateway.dataplane.agent.api.TerminalReason;
import java.time.Instant;

/**
 * Decides what happens after a step (AD-025 §44).
 *
 * <p>Owns orchestration policy and nothing else. No business logic, no provider code, no plugin
 * code, no I/O — every method is a pure function of a snapshot, a step, an outcome and an instant,
 * which is what lets the whole supervision model be tested by enumeration rather than by
 * simulation.
 *
 * <p>The design is Erlang/OTP's, adapted for a world where restarting is not free. Its three ideas
 * carry over intact: failure handling is a <em>declared policy</em> rather than defensive code in
 * the workers; a supervisor may restart, skip, rewind or escalate; and — the part everyone else
 * omits — the number of restarts inside a time window is bounded, so a crash loop terminates
 * instead of billing.
 */
public final class Supervisor {

  /**
   * The order the guards are applied in.
   *
   * <p>Stated once here because the sequence is the policy, and reading it out of nested
   * conditionals is how a reviewer misses that, say, idempotence is checked before the retry
   * budget:
   *
   * <ol>
   *   <li>Integrity failures terminate. Nothing absorbs them.
   *   <li>Restart intensity is checked before anything is retried.
   *   <li>A non-idempotent step is never auto-retried, whatever the class says.
   *   <li>A non-retryable class is never retried, whatever the strategy says.
   *   <li>Only then does the declared strategy choose.
   * </ol>
   */
  private Supervisor() {
    throw new AssertionError("no instances");
  }

  /**
   * Decides what to do about a step's outcome.
   *
   * @param snapshot the run's folded state
   * @param policy the run's declared supervision policy
   * @param step the step that produced the outcome
   * @param result what the step produced
   * @param now the instant to evaluate at, from the injected clock
   * @return the decision, always one that is then recorded
   */
  public static SupervisorDecision decide(
      final RunSnapshot snapshot,
      final RestartPolicy policy,
      final Step step,
      final StepResult result,
      final Instant now) {

    Preconditions.requireNonNull(snapshot, "snapshot");
    Preconditions.requireNonNull(policy, "policy");
    Preconditions.requireNonNull(step, "step");
    Preconditions.requireNonNull(result, "result");
    Preconditions.requireNonNull(now, "now");

    return switch (result) {
      case StepResult.Succeeded ignored -> new SupervisorDecision.Advance();
      case StepResult.Deferred deferred -> new SupervisorDecision.Park(deferred.wakeAt());
      case StepResult.Cancelled cancelled ->
          SupervisorDecision.Terminate.failure(
              FailureClass.CANCELLED, "cancelled: " + cancelled.cause());
      case StepResult.Failed failed -> onFailure(snapshot, policy, step, failed, now);
    };
  }

  private static SupervisorDecision onFailure(
      final RunSnapshot snapshot,
      final RestartPolicy policy,
      final Step step,
      final StepResult.Failed failed,
      final Instant now) {

    // (1) Integrity. A trifecta violation or a replay divergence means the run's assumptions are
    // already broken; retrying, skipping or rewinding would all be operating on a known-corrupt
    // run.
    if (failed.failure().integrity()) {
      return SupervisorDecision.Terminate.failure(failed.failure(), failed.reason());
    }

    final boolean wantsRetry = policy.strategy() == SupervisionStrategy.RETRY_STEP;

    if (wantsRetry && retryable(policy, step, failed)) {
      // (2) Restart intensity, before the retry budget. The budget is per-step and resets when a
      // loop
      // revisits the step; the intensity is per-run and does not. Checking the budget first would
      // let
      // a loop over a flaky step restart forever, which is the exact hole this bound exists to
      // close.
      if (policy.intensity().wouldExceed(snapshot.restartTimes(), now)) {
        return SupervisorDecision.Terminate.failure(
            FailureClass.RESTART_INTENSITY_EXCEEDED,
            "restart intensity "
                + policy.intensity().maxRestarts()
                + " per "
                + policy.intensity().period()
                + " exhausted");
      }
      final int nextAttempt = snapshot.cursor().attempt() + 1;
      if (nextAttempt <= policy.maxAttemptsPerStep()) {
        return new SupervisorDecision.RetryStep(nextAttempt, policy.delayFor(nextAttempt));
      }
    }

    // (5) Whatever the strategy declares, applied to a failure that will not be retried.
    return applyStrategy(policy, failed);
  }

  /**
   * Reports whether a failed step may be retried at all.
   *
   * <p>Two independent vetoes, and the second is stricter than any framework studied. A retryable
   * failure class is necessary; a step whose capabilities are declared non-idempotent is refused
   * anyway (AD-025 §33.3). Sending a second email because the first attempt's acknowledgement was
   * lost is a customer incident; not retrying a transient failure is an inconvenience.
   */
  private static boolean retryable(
      final RestartPolicy policy, final Step step, final StepResult.Failed failed) {
    return policy.retryMode().retries() && failed.failure().retryable() && step.idempotent();
  }

  private static SupervisorDecision applyStrategy(
      final RestartPolicy policy, final StepResult.Failed failed) {
    return switch (policy.strategy()) {
      case SKIP_STEP -> new SupervisorDecision.Skip(failed.failure());
      case RESTART_FROM -> new SupervisorDecision.JumpTo(policy.restartFromStep(), true);
      case COMPENSATE ->
          new SupervisorDecision.Compensate(policy.compensationStep(), failed.failure());
      // ESCALATE at the root is indistinguishable from FAIL_RUN: there is no parent supervisor to
      // hand the decision to, so it terminates here rather than silently doing nothing. Nested runs
      // give it its distinct meaning, which is why the two are not collapsed.
      case ESCALATE, RETRY_STEP, FAIL_RUN ->
          SupervisorDecision.Terminate.failure(failed.failure(), failed.reason());
    };
  }

  /**
   * Decides what to do when the plan has no more steps.
   *
   * @param anySkipped whether supervision absorbed at least one failure along the way
   * @return the completion decision, partial when anything was skipped
   */
  public static SupervisorDecision.Terminate onPlanExhausted(final boolean anySkipped) {
    return anySkipped
        ? SupervisorDecision.Terminate.success(
            TerminalReason.COMPLETED_PARTIAL, "plan complete with skipped steps")
        : SupervisorDecision.Terminate.success(TerminalReason.COMPLETED_SUCCESS, "plan complete");
  }

  /**
   * Decides what to do when a bound refused the next step.
   *
   * @param refusal which bound refused
   * @return a termination carrying the bound's own failure class, so the terminal reason
   *     distinguishes a budget exhaustion from a deadline from a step-count overrun
   */
  public static SupervisorDecision.Terminate onBoundRefusal(
      final io.reliabilityai.gateway.dataplane.agent.domain.BoundsEvaluator.Refusal refusal) {
    Preconditions.requireNonNull(refusal, "refusal");
    return SupervisorDecision.Terminate.failure(refusal.failure(), refusal.detail());
  }
}

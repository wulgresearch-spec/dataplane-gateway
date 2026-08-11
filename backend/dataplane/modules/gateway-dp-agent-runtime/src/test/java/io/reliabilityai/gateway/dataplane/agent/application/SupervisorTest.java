package io.reliabilityai.gateway.dataplane.agent.application;

import static io.reliabilityai.gateway.dataplane.agent.AgentFixtures.T0;
import static io.reliabilityai.gateway.dataplane.agent.AgentFixtures.created;
import static io.reliabilityai.gateway.dataplane.agent.AgentFixtures.irreversibleModel;
import static io.reliabilityai.gateway.dataplane.agent.AgentFixtures.model;
import static io.reliabilityai.gateway.dataplane.agent.AgentFixtures.plan;
import static io.reliabilityai.gateway.dataplane.agent.AgentFixtures.retrying;
import static io.reliabilityai.gateway.dataplane.agent.AgentFixtures.security;
import static org.assertj.core.api.Assertions.assertThat;

import io.reliabilityai.gateway.dataplane.agent.api.CancellationCause;
import io.reliabilityai.gateway.dataplane.agent.api.FailureClass;
import io.reliabilityai.gateway.dataplane.agent.api.Plan;
import io.reliabilityai.gateway.dataplane.agent.api.RestartIntensity;
import io.reliabilityai.gateway.dataplane.agent.api.RestartPolicy;
import io.reliabilityai.gateway.dataplane.agent.api.RetryMode;
import io.reliabilityai.gateway.dataplane.agent.api.RunEvent;
import io.reliabilityai.gateway.dataplane.agent.api.RunHistory;
import io.reliabilityai.gateway.dataplane.agent.api.RunId;
import io.reliabilityai.gateway.dataplane.agent.api.RunSnapshot;
import io.reliabilityai.gateway.dataplane.agent.api.StepId;
import io.reliabilityai.gateway.dataplane.agent.api.StepKind;
import io.reliabilityai.gateway.dataplane.agent.api.StepResult;
import io.reliabilityai.gateway.dataplane.agent.api.SupervisionStrategy;
import io.reliabilityai.gateway.dataplane.agent.api.SupervisorDecision;
import io.reliabilityai.gateway.dataplane.agent.api.TerminalReason;
import io.reliabilityai.gateway.dataplane.agent.domain.ReplayEngine;
import java.time.Duration;
import java.util.Optional;
import org.junit.jupiter.api.DisplayNameGeneration;
import org.junit.jupiter.api.DisplayNameGenerator;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/**
 * The supervision policy, which is the whole of this runtime's failure handling.
 *
 * <p>Every case here is a pure function of a snapshot, a policy, a step, an outcome and an instant,
 * so the model can be tested by enumeration rather than by simulating a failing dependency. The
 * order the guards apply in is itself under test: integrity before everything, restart intensity
 * before the retry budget, idempotence before the failure class, and the declared strategy last.
 */
@DisplayNameGeneration(DisplayNameGenerator.ReplaceUnderscores.class)
class SupervisorTest {

  private static final RunId RUN = RunId.of("r-1");
  private static final StepId STEP = StepId.of(RUN, 0);

  private static RunSnapshot snapshotOf(final Plan plan, final RunEvent... tail) {
    RunHistory history = RunHistory.empty(RUN).append(created(plan, security("chat")));
    for (final RunEvent event : tail) {
      history = history.append(event);
    }
    return ReplayEngine.fold(plan, history);
  }

  private static StepResult.Failed failed(final FailureClass failure) {
    return new StepResult.Failed(STEP, failure, "because", 1L);
  }

  private static RunEvent retryRecorded(final java.time.Instant at) {
    return new RunEvent.SupervisionApplied(STEP, SupervisionStrategy.RETRY_STEP, "retry", 0, at);
  }

  private static RunEvent failure(final java.time.Instant at) {
    return new RunEvent.StepFailed(STEP, "a", FailureClass.STEP_TRANSIENT, "boom", 0L, at);
  }

  private static RunEvent scheduled() {
    return new RunEvent.StepScheduled(STEP, 0, "a", StepKind.PIPELINE, 1, "ref", T0);
  }

  // ---- The non-failure outcomes ---------------------------------------------------------------

  @Test
  void aSucceededStepAdvancesThePlan() {
    final Plan plan = plan(model("a"));
    final SupervisorDecision decision =
        Supervisor.decide(
            snapshotOf(plan),
            plan.restartPolicy(),
            plan.stepAt(0),
            new StepResult.Succeeded(STEP, "v", "d", Optional.empty(), 1L, false),
            T0);
    assertThat(decision).isInstanceOf(SupervisorDecision.Advance.class);
    assertThat(decision.continues()).isTrue();
    assertThat(decision.label()).isEqualTo("advance");
  }

  @Test
  void aDeferredStepParksTheRunUntilItsRecordedWakeInstant() {
    final Plan plan = plan(model("a"));
    final java.time.Instant wake = T0.plus(Duration.ofHours(6));
    final SupervisorDecision decision =
        Supervisor.decide(
            snapshotOf(plan),
            plan.restartPolicy(),
            plan.stepAt(0),
            new StepResult.Deferred(STEP, wake),
            T0);
    assertThat(decision).isInstanceOf(SupervisorDecision.Park.class);
    assertThat(((SupervisorDecision.Park) decision).until()).isEqualTo(wake);
  }

  @Test
  void aCancelledStepEndsTheRun() {
    final Plan plan = plan(model("a"));
    final SupervisorDecision decision =
        Supervisor.decide(
            snapshotOf(plan),
            plan.restartPolicy(),
            plan.stepAt(0),
            new StepResult.Cancelled(STEP, CancellationCause.USER, 0L),
            T0);
    assertThat(decision).isInstanceOf(SupervisorDecision.Terminate.class);
    assertThat(decision.continues()).isFalse();
  }

  // ---- Guard 1: integrity ---------------------------------------------------------------------

  @ParameterizedTest
  @EnumSource(
      value = FailureClass.class,
      names = {"TRIFECTA_VIOLATION", "REPLAY_DIVERGENCE"})
  void anIntegrityFailureTerminatesWhateverTheStrategySays(final FailureClass integrity) {
    // Retrying, skipping or rewinding would all be operating on a run whose assumptions are already
    // broken. Nothing absorbs these.
    for (final RestartPolicy policy :
        java.util.List.of(retrying(5, 5), RestartPolicy.STRICT, skipPolicy())) {
      final Plan plan =
          plan(
              1,
              io.reliabilityai.gateway.dataplane.agent.api.RunBounds.DEFAULT,
              policy,
              model("a"));
      final SupervisorDecision decision =
          Supervisor.decide(snapshotOf(plan), policy, plan.stepAt(0), failed(integrity), T0);
      assertThat(decision).isInstanceOf(SupervisorDecision.Terminate.class);
      assertThat(((SupervisorDecision.Terminate) decision).reason())
          .isEqualTo(TerminalReason.FAILED_INTEGRITY);
    }
  }

  // ---- Guard 2: restart intensity, checked before the retry budget ----------------------------

  @Test
  void aTransientFailureIsRetriedWhileTheIntensityAllowsIt() {
    final RestartPolicy policy = retrying(5, 3);
    final Plan plan =
        plan(1, io.reliabilityai.gateway.dataplane.agent.api.RunBounds.DEFAULT, policy, model("a"));
    final SupervisorDecision decision =
        Supervisor.decide(
            snapshotOf(plan, scheduled(), failure(T0)),
            policy,
            plan.stepAt(0),
            failed(FailureClass.STEP_TRANSIENT),
            T0);
    assertThat(decision).isInstanceOf(SupervisorDecision.RetryStep.class);
    assertThat(((SupervisorDecision.RetryStep) decision).attempt()).isEqualTo(2);
  }

  @Test
  void aCrashLoopIsStoppedByTheRestartIntensityRatherThanRunningForever() {
    // The bound no other agent framework has. A per-step retry counter resets when a loop revisits
    // the step; this window does not, so the run terminates instead of billing indefinitely.
    final RestartPolicy policy = retrying(99, 2);
    final Plan plan =
        plan(1, io.reliabilityai.gateway.dataplane.agent.api.RunBounds.DEFAULT, policy, model("a"));
    final RunSnapshot afterTwoRestarts =
        snapshotOf(
            plan,
            scheduled(),
            failure(T0),
            retryRecorded(T0.plusSeconds(1)),
            scheduled(),
            failure(T0.plusSeconds(2)),
            retryRecorded(T0.plusSeconds(3)));

    final SupervisorDecision decision =
        Supervisor.decide(
            afterTwoRestarts,
            policy,
            plan.stepAt(0),
            failed(FailureClass.STEP_TRANSIENT),
            T0.plusSeconds(4));

    assertThat(decision).isInstanceOf(SupervisorDecision.Terminate.class);
    final SupervisorDecision.Terminate terminate = (SupervisorDecision.Terminate) decision;
    assertThat(terminate.failure()).contains(FailureClass.RESTART_INTENSITY_EXCEEDED);
    assertThat(terminate.reason()).isEqualTo(TerminalReason.FAILED_RESTART_INTENSITY);
  }

  @Test
  void restartsThatHaveAgedOutOfTheWindowDoNotCountAgainstTheIntensity() {
    final RestartPolicy policy = retrying(99, 2);
    final Plan plan =
        plan(1, io.reliabilityai.gateway.dataplane.agent.api.RunBounds.DEFAULT, policy, model("a"));
    final RunSnapshot old =
        snapshotOf(
            plan,
            scheduled(),
            failure(T0),
            retryRecorded(T0),
            scheduled(),
            failure(T0),
            retryRecorded(T0));

    // An hour later the window is empty again, so a fresh failure is retried rather than
    // terminated.
    final SupervisorDecision decision =
        Supervisor.decide(
            old,
            policy,
            plan.stepAt(0),
            failed(FailureClass.STEP_TRANSIENT),
            T0.plus(Duration.ofHours(1)));
    assertThat(decision).isInstanceOf(SupervisorDecision.RetryStep.class);
  }

  @Test
  void theRetryBudgetStopsRetryingEvenWhenTheIntensityStillAllowsIt() {
    // Two independent limiters: the intensity is per-run, the budget is per-step. Either can bind.
    final RestartPolicy policy = retrying(2, 99);
    final Plan plan =
        plan(1, io.reliabilityai.gateway.dataplane.agent.api.RunBounds.DEFAULT, policy, model("a"));
    final RunSnapshot onAttemptTwo = snapshotOf(plan, scheduled(), failure(T0), retryRecorded(T0));
    assertThat(onAttemptTwo.cursor().attempt()).isEqualTo(2);

    final SupervisorDecision decision =
        Supervisor.decide(
            onAttemptTwo, policy, plan.stepAt(0), failed(FailureClass.STEP_TRANSIENT), T0);
    assertThat(decision).isInstanceOf(SupervisorDecision.Terminate.class);
  }

  // ---- Guard 3: idempotence ------------------------------------------------------------------

  @Test
  void aNonIdempotentStepIsNeverAutoRetriedEvenForARetryableFailure() {
    // Stricter than any framework studied, and deliberately so: a duplicated irreversible action is
    // a customer incident, while a non-retried transient failure is an inconvenience.
    final RestartPolicy policy = retrying(5, 5);
    final Plan plan =
        plan(
            1,
            io.reliabilityai.gateway.dataplane.agent.api.RunBounds.DEFAULT,
            policy,
            irreversibleModel("send"));
    final SupervisorDecision decision =
        Supervisor.decide(
            snapshotOf(plan, scheduled(), failure(T0)),
            policy,
            plan.stepAt(0),
            failed(FailureClass.STEP_TRANSIENT),
            T0);
    assertThat(decision).isInstanceOf(SupervisorDecision.Terminate.class);
  }

  @Test
  void anIdempotentStepWithTheSameFailureIsRetried() {
    final RestartPolicy policy = retrying(5, 5);
    final Plan plan =
        plan(
            1,
            io.reliabilityai.gateway.dataplane.agent.api.RunBounds.DEFAULT,
            policy,
            model("read"));
    assertThat(
            Supervisor.decide(
                snapshotOf(plan, scheduled(), failure(T0)),
                policy,
                plan.stepAt(0),
                failed(FailureClass.STEP_TRANSIENT),
                T0))
        .isInstanceOf(SupervisorDecision.RetryStep.class);
  }

  // ---- Guard 4: retryability of the class -----------------------------------------------------

  @ParameterizedTest
  @EnumSource(
      value = FailureClass.class,
      names = {"STEP_TRANSIENT", "STEP_TIMEOUT", "TOOL_FAILURE", "STORE_UNAVAILABLE"})
  void theRetryableClassesAreRetriedUnderARetryingPolicy(final FailureClass failure) {
    final RestartPolicy policy = retrying(5, 5);
    final Plan plan =
        plan(1, io.reliabilityai.gateway.dataplane.agent.api.RunBounds.DEFAULT, policy, model("a"));
    assertThat(
            Supervisor.decide(
                snapshotOf(plan, scheduled(), failure(T0)),
                policy,
                plan.stepAt(0),
                failed(failure),
                T0))
        .isInstanceOf(SupervisorDecision.RetryStep.class);
  }

  @ParameterizedTest
  @EnumSource(
      value = FailureClass.class,
      names = {
        "STEP_DENIED",
        "STEP_PERMANENT",
        "ADMISSION_DENIED",
        "BUDGET_EXHAUSTED",
        "BOUND_EXCEEDED",
        "PLAN_INVALID",
        "APPROVAL_DENIED",
        "POLICY_REVOKED"
      })
  void aNonRetryableClassIsNotRetriedEvenUnderARetryingPolicy(final FailureClass failure) {
    // Retrying a governance denial burns budget to be denied identically.
    final RestartPolicy policy = retrying(5, 5);
    final Plan plan =
        plan(1, io.reliabilityai.gateway.dataplane.agent.api.RunBounds.DEFAULT, policy, model("a"));
    assertThat(
            Supervisor.decide(
                snapshotOf(plan, scheduled(), failure(T0)),
                policy,
                plan.stepAt(0),
                failed(failure),
                T0))
        .isInstanceOf(SupervisorDecision.Terminate.class);
  }

  // ---- Guard 5: the declared strategy ----------------------------------------------------------

  @Test
  void theDefaultStrategyFailsTheRunOnAnyStepFailure() {
    final Plan plan = plan(model("a"));
    assertThat(
            Supervisor.decide(
                snapshotOf(plan),
                RestartPolicy.STRICT,
                plan.stepAt(0),
                failed(FailureClass.STEP_TRANSIENT),
                T0))
        .isInstanceOf(SupervisorDecision.Terminate.class);
  }

  @Test
  void skipAbsorbsTheFailureAndTheRunCarriesOn() {
    final RestartPolicy policy = skipPolicy();
    final Plan plan =
        plan(1, io.reliabilityai.gateway.dataplane.agent.api.RunBounds.DEFAULT, policy, model("a"));
    final SupervisorDecision decision =
        Supervisor.decide(
            snapshotOf(plan), policy, plan.stepAt(0), failed(FailureClass.TOOL_FAILURE), T0);
    assertThat(decision).isInstanceOf(SupervisorDecision.Skip.class);
    assertThat(decision.continues()).isTrue();
    assertThat(((SupervisorDecision.Skip) decision).failure()).isEqualTo(FailureClass.TOOL_FAILURE);
  }

  @Test
  void restartFromRewindsToTheDeclaredStep() {
    final RestartPolicy policy =
        new RestartPolicy(
            SupervisionStrategy.RESTART_FROM,
            RetryMode.NONE,
            1,
            Duration.ZERO,
            Duration.ZERO,
            RestartIntensity.NONE,
            "a",
            "");
    final Plan plan =
        plan(
            1,
            io.reliabilityai.gateway.dataplane.agent.api.RunBounds.DEFAULT,
            policy,
            model("a"),
            model("b"));
    final SupervisorDecision decision =
        Supervisor.decide(
            snapshotOf(plan), policy, plan.stepAt(1), failed(FailureClass.STEP_PERMANENT), T0);
    assertThat(decision).isInstanceOf(SupervisorDecision.JumpTo.class);
    final SupervisorDecision.JumpTo jump = (SupervisorDecision.JumpTo) decision;
    assertThat(jump.targetStep()).isEqualTo("a");
    assertThat(jump.rewind()).isTrue();
    assertThat(jump.label()).isEqualTo("restart-from");
  }

  @Test
  void compensateRunsTheDeclaredCompensationBeforeFailing() {
    final RestartPolicy policy =
        new RestartPolicy(
            SupervisionStrategy.COMPENSATE,
            RetryMode.NONE,
            1,
            Duration.ZERO,
            Duration.ZERO,
            RestartIntensity.NONE,
            "",
            "undo");
    final Plan plan =
        plan(
            1,
            io.reliabilityai.gateway.dataplane.agent.api.RunBounds.DEFAULT,
            policy,
            model("a"),
            model("undo"));
    final SupervisorDecision decision =
        Supervisor.decide(
            snapshotOf(plan), policy, plan.stepAt(0), failed(FailureClass.STEP_PERMANENT), T0);
    assertThat(decision).isInstanceOf(SupervisorDecision.Compensate.class);
    assertThat(((SupervisorDecision.Compensate) decision).compensationStep()).isEqualTo("undo");
  }

  @Test
  void escalateAtTheRootTerminatesRatherThanSilentlyDoingNothing() {
    // There is no parent supervisor to hand the decision to, and a decision that changes nothing is
    // the worst possible outcome for a failure-handling path.
    final RestartPolicy policy =
        new RestartPolicy(
            SupervisionStrategy.ESCALATE,
            RetryMode.NONE,
            1,
            Duration.ZERO,
            Duration.ZERO,
            RestartIntensity.NONE,
            "",
            "");
    final Plan plan =
        plan(1, io.reliabilityai.gateway.dataplane.agent.api.RunBounds.DEFAULT, policy, model("a"));
    assertThat(
            Supervisor.decide(
                snapshotOf(plan), policy, plan.stepAt(0), failed(FailureClass.STEP_PERMANENT), T0))
        .isInstanceOf(SupervisorDecision.Terminate.class);
  }

  // ---- Plan exhaustion and bound refusal ------------------------------------------------------

  @Test
  void aPlanThatFinishedCleanlyCompletesAsSuccess() {
    assertThat(Supervisor.onPlanExhausted(false).reason())
        .isEqualTo(TerminalReason.COMPLETED_SUCCESS);
  }

  @Test
  void aPlanThatSkippedAFailureCompletesAsPartialRatherThanPretendingItWasClean() {
    assertThat(Supervisor.onPlanExhausted(true).reason())
        .isEqualTo(TerminalReason.COMPLETED_PARTIAL);
  }

  @Test
  void aBoundRefusalTerminatesWithThatBoundsOwnReason() {
    final io.reliabilityai.gateway.dataplane.agent.domain.BoundsEvaluator.Refusal refusal =
        new io.reliabilityai.gateway.dataplane.agent.domain.BoundsEvaluator.Refusal(
            "budget", FailureClass.BUDGET_EXHAUSTED, "no money left");
    final SupervisorDecision.Terminate decision = Supervisor.onBoundRefusal(refusal);
    assertThat(decision.reason()).isEqualTo(TerminalReason.TERMINATED_BUDGET);
    assertThat(decision.detail()).isEqualTo("no money left");
  }

  @Test
  void everyDecisionCarriesALabelForTheAuditRecord() {
    assertThat(new SupervisorDecision.Advance().label()).isEqualTo("advance");
    assertThat(new SupervisorDecision.Skip(FailureClass.TOOL_FAILURE).label()).isEqualTo("skip");
    assertThat(new SupervisorDecision.RetryStep(2, Duration.ZERO).label()).isEqualTo("retry");
    assertThat(new SupervisorDecision.Park(T0).label()).isEqualTo("park");
    assertThat(new SupervisorDecision.JumpTo("x", false).label()).isEqualTo("branch");
    assertThat(new SupervisorDecision.Compensate("u", FailureClass.TOOL_FAILURE).label())
        .isEqualTo("compensate");
    assertThat(SupervisorDecision.Terminate.success(TerminalReason.COMPLETED_SUCCESS, "d").label())
        .isEqualTo("terminate");
  }

  @Test
  void aRetryDecisionBelowAttemptTwoIsUnrepresentable() {
    // Attempt one is the original execution, not a retry; allowing it would let a decision claim a
    // restart that never happened and corrupt the intensity count.
    org.assertj.core.api.Assertions.assertThatThrownBy(
            () -> new SupervisorDecision.RetryStep(1, Duration.ZERO))
        .isInstanceOf(IllegalArgumentException.class);
  }

  private static RestartPolicy skipPolicy() {
    return new RestartPolicy(
        SupervisionStrategy.SKIP_STEP,
        RetryMode.NONE,
        1,
        Duration.ZERO,
        Duration.ZERO,
        RestartIntensity.NONE,
        "",
        "");
  }
}

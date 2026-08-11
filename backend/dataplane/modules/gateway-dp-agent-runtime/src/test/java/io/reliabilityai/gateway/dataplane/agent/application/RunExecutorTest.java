package io.reliabilityai.gateway.dataplane.agent.application;

import static io.reliabilityai.gateway.dataplane.agent.AgentFixtures.irreversibleModel;
import static io.reliabilityai.gateway.dataplane.agent.AgentFixtures.model;
import static io.reliabilityai.gateway.dataplane.agent.AgentFixtures.plan;
import static io.reliabilityai.gateway.dataplane.agent.AgentFixtures.retrying;
import static io.reliabilityai.gateway.dataplane.agent.AgentFixtures.security;
import static io.reliabilityai.gateway.dataplane.agent.AgentFixtures.skipping;
import static io.reliabilityai.gateway.dataplane.agent.AgentFixtures.tool;
import static org.assertj.core.api.Assertions.assertThat;

import io.reliabilityai.gateway.dataplane.agent.AgentFixtures;
import io.reliabilityai.gateway.dataplane.agent.AgentFixtures.FakeSessions;
import io.reliabilityai.gateway.dataplane.agent.AgentFixtures.FakeTools;
import io.reliabilityai.gateway.dataplane.agent.AgentFixtures.TestClock;
import io.reliabilityai.gateway.dataplane.agent.api.AgentToolPort;
import io.reliabilityai.gateway.dataplane.agent.api.CancellationCause;
import io.reliabilityai.gateway.dataplane.agent.api.ConditionOperator;
import io.reliabilityai.gateway.dataplane.agent.api.FailureClass;
import io.reliabilityai.gateway.dataplane.agent.api.Plan;
import io.reliabilityai.gateway.dataplane.agent.api.RunBounds;
import io.reliabilityai.gateway.dataplane.agent.api.RunEvent;
import io.reliabilityai.gateway.dataplane.agent.api.RunId;
import io.reliabilityai.gateway.dataplane.agent.api.RunSecurityContext;
import io.reliabilityai.gateway.dataplane.agent.api.RunState;
import io.reliabilityai.gateway.dataplane.agent.api.Step;
import io.reliabilityai.gateway.dataplane.agent.api.StepKind;
import io.reliabilityai.gateway.dataplane.agent.api.StepStatus;
import io.reliabilityai.gateway.dataplane.agent.api.TerminalReason;
import io.reliabilityai.gateway.dataplane.agent.api.ToolSessionPort;
import io.reliabilityai.gateway.dataplane.agent.internal.HistoryBackedStepRepository;
import io.reliabilityai.gateway.dataplane.agent.internal.InMemoryPlanRepository;
import io.reliabilityai.gateway.dataplane.agent.internal.InMemoryRunRepository;
import io.reliabilityai.gateway.dataplane.agent.internal.InProcessAgentMetrics;
import java.time.Duration;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.DisplayNameGeneration;
import org.junit.jupiter.api.DisplayNameGenerator;
import org.junit.jupiter.api.Test;

/**
 * The step executor: one step, recorded before and after, then yield.
 *
 * <p>The headline assertions here are the two AD-025 rules that this milestone exists to make real
 * — that a run of N model steps performs N complete pipeline executions with nothing reused, and
 * that a tool's output can only reach a model through a subsequent governed turn.
 */
@DisplayNameGeneration(DisplayNameGenerator.ReplaceUnderscores.class)
class RunExecutorTest {

  private final InMemoryRunRepository runs = new InMemoryRunRepository();
  private final InMemoryPlanRepository plans = new InMemoryPlanRepository();
  private final FakeSessions sessions = new FakeSessions();
  private final FakeTools tools = new FakeTools();
  private final TestClock clock = new TestClock();
  private final InProcessAgentMetrics metrics = new InProcessAgentMetrics();
  private final RunId run = RunId.of("r-1");

  private AgentRuntime runtime;
  private RunExecutor executor;

  private AgentRuntime runtimeFor(final Plan plan) {
    plans.publish(plan);
    executor =
        new RunExecutor(
            runs,
            plans,
            new ToolSessionRunner(sessions, tools),
            clock,
            metrics,
            io.reliabilityai.gateway.dataplane.agent.api.RunAuditPort.NOOP);
    runtime =
        new AgentRuntime(
            runs,
            plans,
            executor,
            clock,
            metrics,
            io.reliabilityai.gateway.dataplane.agent.api.RunAuditPort.NOOP);
    return runtime;
  }

  private void start(final Plan plan, final RunSecurityContext security) {
    runtimeFor(plan).start(run, plan, security, plan.bounds(), 1_000_000L);
  }

  private void start(final Plan plan) {
    start(plan, security("chat", "search"));
  }

  private RunExecutor.Advance drain(final int maxSteps) {
    RunExecutor.Advance last = new RunExecutor.Advance.Idle();
    for (int i = 0; i < maxSteps; i++) {
      last = executor.advance(run);
      if (last instanceof RunExecutor.Advance.Terminated
          || last instanceof RunExecutor.Advance.Idle
          || last instanceof RunExecutor.Advance.Stalled) {
        return last;
      }
    }
    return last;
  }

  // ---- The central invariant ------------------------------------------------------------------

  @Test
  void everyModelStepCreatesExactlyOneNewPipelineExecutionAndNoneAreReused() {
    // AD-025 AGT-2 and the milestone's central rule. Three model steps, three sessions, three
    // pipeline executions — and three distinct session references, so nothing was reused.
    final Plan plan = plan(model("a"), model("b"), model("c"));
    start(plan);

    assertThat(drain(20)).isInstanceOf(RunExecutor.Advance.Terminated.class);
    assertThat(sessions.requests()).hasSize(3);
    assertThat(sessions.pipelineExecutions()).isEqualTo(3);
    assertThat(sessions.requests().stream().map(ToolSessionPort.SessionRequest::sessionRef))
        .doesNotHaveDuplicates();
  }

  @Test
  void aRetriedStepCreatesAnotherPipelineExecutionRatherThanResumingTheFailedOne() {
    // AGT-10: a retried step is a new session, so authorization, routing and metering all run
    // again.
    final Plan plan = plan(1, RunBounds.DEFAULT, retrying(3, 5), model("flaky"));
    final int[] attempts = {0};
    sessions.respondWith(
        request ->
            ++attempts[0] < 3
                ? new ToolSessionPort.Failed(FailureClass.STEP_TRANSIENT, "flaky", 1L, true, 1)
                : new ToolSessionPort.Completed("ok", 1L, true, 1, false));
    start(plan);

    assertThat(drain(20)).isInstanceOf(RunExecutor.Advance.Terminated.class);
    assertThat(sessions.requests()).hasSize(3);
    assertThat(sessions.pipelineExecutions()).isEqualTo(3);
    assertThat(sessions.requests().stream().map(ToolSessionPort.SessionRequest::sessionRef))
        .doesNotHaveDuplicates();
  }

  @Test
  void aToolResultReachesAModelOnlyThroughASubsequentPipelineExecution() {
    // AGT-9. The tool step performs no pipeline execution; the model step that consumes its
    // artifact
    // performs one, and the artifact arrives as that session's input.
    final Plan plan = plan(tool("fetch", "search"), model("summarise", "fetch"));
    tools.respondWith(call -> new AgentToolPort.Produced("PAGE CONTENT", 3L, false));
    start(plan);

    assertThat(drain(20)).isInstanceOf(RunExecutor.Advance.Terminated.class);
    assertThat(tools.calls()).hasSize(1);
    assertThat(sessions.requests()).hasSize(1);
    assertThat(sessions.pipelineExecutions()).isEqualTo(1);
    assertThat(sessions.requests().get(0).inputs()).containsExactly("PAGE CONTENT");
  }

  @Test
  void aToolStepRecordsAnArtifactEventSoTheAuditCanFindWhatEnteredTheRun() {
    final Plan plan = plan(tool("fetch", "search"));
    start(plan);
    drain(10);

    assertThat(runs.load(run).orElseThrow().events())
        .anyMatch(event -> event instanceof RunEvent.ToolArtifactProduced);
  }

  @Test
  void aToolArtifactIsTaintedByDefaultAndTaintsTheWholeRun() {
    final Plan plan = plan(tool("fetch", "search"), model("summarise", "fetch"));
    start(plan);
    drain(20);

    assertThat(runtime.snapshot(run).orElseThrow().tainted()).isTrue();
    assertThat(sessions.requests().get(0).inboundTainted()).isTrue();
  }

  @Test
  void aTrustedToolProducesAnUntaintedArtifact() {
    final Plan plan = plan(tool("fetch", "config"), model("use", "fetch"));
    tools.respondWith(call -> new AgentToolPort.Produced("value", 1L, true));
    start(plan, security("chat", "config"));
    drain(20);

    assertThat(runtime.snapshot(run).orElseThrow().tainted()).isFalse();
    assertThat(sessions.requests().get(0).inboundTainted()).isFalse();
  }

  @Test
  void theRunsAccumulatedTaintIsHandedToEverySubsequentSession() {
    // The property C13 structurally cannot provide, because it cannot see across sessions.
    final Plan plan = plan(tool("fetch", "search"), model("one", "fetch"), model("two"));
    start(plan);
    drain(20);

    assertThat(sessions.requests()).hasSize(2);
    assertThat(sessions.requests()).allMatch(ToolSessionPort.SessionRequest::inboundTainted);
  }

  // ---- Recording discipline --------------------------------------------------------------------

  @Test
  void aStepIsRecordedAsScheduledBeforeItRunsAndTerminalAfterwards() {
    final Plan plan = plan(model("a"));
    start(plan);
    executor.advance(run);

    final List<RunEvent> events = runs.load(run).orElseThrow().events();
    final int scheduledAt = indexOfFirst(events, RunEvent.StepScheduled.class);
    final int completedAt = indexOfFirst(events, RunEvent.StepCompleted.class);
    assertThat(scheduledAt).isNotNegative();
    assertThat(completedAt).isGreaterThan(scheduledAt);
  }

  @Test
  void everySupervisionDecisionIsRecordedIncludingTheOnesThatChangeNothing() {
    final Plan plan = plan(model("a"));
    start(plan);
    executor.advance(run);

    assertThat(runs.load(run).orElseThrow().events())
        .anyMatch(
            event ->
                event instanceof RunEvent.SupervisionApplied supervision
                    && "advance".equals(supervision.decision()));
  }

  @Test
  void theHistoryIsTheOnlyPlaceRunStateLivesSoAFreshExecutorSeesTheSameThing() {
    final Plan plan = plan(model("a"), model("b"));
    start(plan);
    executor.advance(run);

    final RunExecutor other =
        new RunExecutor(
            runs,
            plans,
            new ToolSessionRunner(sessions, tools),
            clock,
            metrics,
            io.reliabilityai.gateway.dataplane.agent.api.RunAuditPort.NOOP);
    assertThat(other.advance(run)).isInstanceOf(RunExecutor.Advance.Advanced.class);
    assertThat(sessions.requests()).hasSize(2);
  }

  // ---- Bounds --------------------------------------------------------------------------------

  @Test
  void aRunPastItsWallClockBoundTerminatesEvenBeforeTheNextStepRuns() {
    final RunBounds tight = new RunBounds(50, 3, 5, Duration.ofMinutes(1), 1_000_000L);
    final Plan plan =
        plan(
            1,
            tight,
            io.reliabilityai.gateway.dataplane.agent.api.RestartPolicy.STRICT,
            model("a"),
            model("b"));
    start(plan);
    executor.advance(run);
    clock.advance(Duration.ofMinutes(2));

    final RunExecutor.Advance outcome = executor.advance(run);
    assertThat(outcome).isInstanceOf(RunExecutor.Advance.Terminated.class);
    assertThat(((RunExecutor.Advance.Terminated) outcome).reason())
        .isEqualTo(TerminalReason.TERMINATED_TIMEOUT);
  }

  @Test
  void aRunThatReachesItsStepBoundTerminatesAndSaysSo() {
    final RunBounds tight = new RunBounds(2, 3, 5, Duration.ofHours(1), 1_000_000L);
    final Plan plan =
        plan(
            1,
            tight,
            io.reliabilityai.gateway.dataplane.agent.api.RestartPolicy.STRICT,
            model("a"),
            model("b"));
    start(plan);
    // Two steps fit the plan exactly, so the bound is reached at the same moment the plan ends.
    assertThat(drain(20)).isInstanceOf(RunExecutor.Advance.Terminated.class);
    assertThat(metrics.counter("run.terminated")).isEqualTo(1L);
  }

  @Test
  void aStepThatCannotBeAffordedTerminatesTheRunOnBudgetRatherThanRunningAnyway() {
    final Plan plan = plan(model("a"), model("b"));
    plans.publish(plan);
    executor =
        new RunExecutor(
            runs,
            plans,
            new ToolSessionRunner(sessions, tools),
            clock,
            metrics,
            io.reliabilityai.gateway.dataplane.agent.api.RunAuditPort.NOOP);
    runtime =
        new AgentRuntime(
            runs,
            plans,
            executor,
            clock,
            metrics,
            io.reliabilityai.gateway.dataplane.agent.api.RunAuditPort.NOOP);
    // A grant that cannot cover even one step's declared ceiling.
    runtime.start(run, plan, security("chat"), plan.bounds(), 10L);

    final RunExecutor.Advance outcome = executor.advance(run);
    assertThat(outcome).isInstanceOf(RunExecutor.Advance.Terminated.class);
    assertThat(((RunExecutor.Advance.Terminated) outcome).reason())
        .isEqualTo(TerminalReason.TERMINATED_BUDGET);
    assertThat(sessions.requests()).isEmpty();
    assertThat(metrics.counter("bound.refused.budget")).isEqualTo(1L);
  }

  @Test
  void aBoundRefusalHappensBeforeTheWorkRatherThanAfterPayingForIt() {
    final Plan plan = plan(model("a"));
    plans.publish(plan);
    executor =
        new RunExecutor(
            runs,
            plans,
            new ToolSessionRunner(sessions, tools),
            clock,
            metrics,
            io.reliabilityai.gateway.dataplane.agent.api.RunAuditPort.NOOP);
    runtime =
        new AgentRuntime(
            runs,
            plans,
            executor,
            clock,
            metrics,
            io.reliabilityai.gateway.dataplane.agent.api.RunAuditPort.NOOP);
    runtime.start(run, plan, security("chat"), plan.bounds(), 1L);

    executor.advance(run);
    assertThat(sessions.requests()).isEmpty();
    assertThat(tools.calls()).isEmpty();
  }

  // ---- Cancellation ----------------------------------------------------------------------------

  @Test
  void aCancelledRunStopsAtItsNextStepBoundaryAndRunsNoFurtherSteps() {
    final Plan plan = plan(model("a"), model("b"), model("c"));
    start(plan);
    executor.advance(run);
    runtime.cancel(run, CancellationCause.USER, "user asked");

    final RunExecutor.Advance outcome = executor.advance(run);
    assertThat(outcome).isInstanceOf(RunExecutor.Advance.Terminated.class);
    assertThat(sessions.requests()).hasSize(1);
  }

  @Test
  void cancellationIsRecordedAsADurableEvent() {
    final Plan plan = plan(model("a"));
    start(plan);
    runtime.cancel(run, CancellationCause.OPERATOR, "maintenance");

    assertThat(runs.load(run).orElseThrow().events())
        .anyMatch(event -> event instanceof RunEvent.RunCancelled);
  }

  @Test
  void aTerminatedRunCannotBeCancelledAgain() {
    final Plan plan = plan(model("a"));
    start(plan);
    drain(10);
    assertThat(runtime.cancel(run, CancellationCause.USER, "too late")).isFalse();
  }

  @Test
  void cancellingAnUnknownRunIsRefusedRatherThanCreatingOne() {
    runtimeFor(plan(model("a")));
    assertThat(runtime.cancel(RunId.of("ghost"), CancellationCause.USER, "x")).isFalse();
  }

  // ---- Store failure ---------------------------------------------------------------------------

  @Test
  void anUnreachableStoreStallsTheRunRatherThanProceedingUnrecorded() {
    // AGT-14. An executor that carried on would be executing a run whose history is a lie.
    final Plan plan = plan(model("a"));
    start(plan);
    runs.setAvailable(false);

    final RunExecutor.Advance outcome = executor.advance(run);
    assertThat(outcome).isInstanceOf(RunExecutor.Advance.Stalled.class);
    assertThat(sessions.requests()).isEmpty();
    assertThat(metrics.counter("store.unavailable")).isPositive();
  }

  @Test
  void aStalledRunResumesCleanlyOnceTheStoreComesBack() {
    final Plan plan = plan(model("a"));
    start(plan);
    runs.setAvailable(false);
    executor.advance(run);
    runs.setAvailable(true);

    assertThat(drain(10)).isInstanceOf(RunExecutor.Advance.Terminated.class);
    assertThat(sessions.requests()).hasSize(1);
  }

  @Test
  void aRunWhosePinnedPlanVersionHasBeenCollectedFailsWithPlanInvalid() {
    final Plan plan = plan(model("a"));
    start(plan);
    plans.collect(plan.id(), plan.version());

    final RunExecutor.Advance outcome = executor.advance(run);
    assertThat(outcome).isInstanceOf(RunExecutor.Advance.Terminated.class);
    assertThat(((RunExecutor.Advance.Terminated) outcome).reason())
        .isEqualTo(TerminalReason.FAILED_PLAN);
  }

  // ---- Step kinds ------------------------------------------------------------------------------

  @Test
  void aWaitStepParksTheRunAndHoldsNothing() {
    final Plan plan = plan(new Step.Wait("pause", Duration.ofHours(6)), model("after"));
    start(plan);

    final RunExecutor.Advance outcome = executor.advance(run);
    assertThat(outcome).isInstanceOf(RunExecutor.Advance.Parked.class);
    assertThat(runtime.snapshot(run).orElseThrow().state()).isEqualTo(RunState.WAITING);
    assertThat(runtime.snapshot(run).orElseThrow().state().holdsResources()).isFalse();
    assertThat(sessions.requests()).isEmpty();
  }

  @Test
  void aParkedRunIsNotAdvancedBeforeItsWakeInstant() {
    final Plan plan = plan(new Step.Wait("pause", Duration.ofHours(6)), model("after"));
    start(plan);
    executor.advance(run);

    assertThat(executor.advance(run)).isInstanceOf(RunExecutor.Advance.Idle.class);
    clock.advance(Duration.ofHours(7));
    assertThat(executor.advance(run)).isNotInstanceOf(RunExecutor.Advance.Idle.class);
  }

  @Test
  void aConditionStepRecordsItsBooleanWithoutCallingAnything() {
    final Plan plan =
        plan(model("a"), new Step.Condition("check", "a", ConditionOperator.CONTAINS, "ok"));
    start(plan);
    drain(20);

    assertThat(runtime.snapshot(run).orElseThrow().resultOf("check")).contains("true");
    assertThat(sessions.requests()).hasSize(1);
  }

  @Test
  void aConditionOverAnAbsentSubjectIsFalseRatherThanAnError() {
    final Plan plan =
        plan(model("a"), new Step.Condition("check", "a", ConditionOperator.CONTAINS, "zzz"));
    start(plan);
    assertThat(drain(20)).isInstanceOf(RunExecutor.Advance.Terminated.class);
    assertThat(runtime.snapshot(run).orElseThrow().resultOf("check")).contains("false");
  }

  @Test
  void aBranchStepCostsNothingAndCallsNothing() {
    final Plan plan =
        plan(model("a"), new Step.Branch("pick", "a", ConditionOperator.EXISTS, "", "a", "a"));
    start(plan);
    drain(5);
    assertThat(sessions.requests()).hasSize(1);
  }

  // ---- Capability narrowing --------------------------------------------------------------------

  @Test
  void aStepReceivesOnlyTheCapabilitiesItDeclaredAndNotTheWholeCeiling() {
    // Least privilege over time (AD-025 SS61.2). The run's ceiling is a maximum; each session is
    // opened with the far smaller set its own step asked for.
    final Step.Pipeline modest =
        new Step.Pipeline("a", "i", List.of(), Set.of("chat"), 1L, 5L, Duration.ofMinutes(1), true);
    start(plan(modest), security("chat", "search", "email"));
    drain(5);

    assertThat(sessions.requests().get(0).grantedCapabilities()).containsExactly("chat");
  }

  @Test
  void aRunIsRefusedAtAdmissionWhenThePrincipalLacksACapabilityThePlanNeeds() {
    // Fail fast, and fail before anything is recorded. The alternative is discovering it at step
    // thirty, after twenty-nine steps have been paid for.
    final Step.Pipeline greedy =
        new Step.Pipeline(
            "a", "i", List.of(), Set.of("chat", "email"), 1L, 5L, Duration.ofMinutes(1), true);
    final Plan plan = plan(greedy);
    final AgentRuntime started = runtimeFor(plan);

    final AgentRuntime.StartOutcome outcome =
        started.start(run, plan, security("chat"), plan.bounds(), 1_000_000L);
    assertThat(outcome).isInstanceOf(AgentRuntime.StartOutcome.Refused.class);
    assertThat(((AgentRuntime.StartOutcome.Refused) outcome).reason()).contains("email");
    assertThat(runs.load(run)).isEmpty();
  }

  @Test
  void aToolStepRequestingACapabilityOutsideTheCeilingNeverReachesTheTool() {
    // Refused at admission, so the run does not exist and the tool is never called. The step-level
    // ceiling check below is defence in depth for a ceiling narrowed after admission.
    final Plan plan = plan(tool("exfiltrate", "email"));
    final AgentRuntime started = runtimeFor(plan);
    assertThat(started.start(run, plan, security("chat"), plan.bounds(), 1_000_000L))
        .isInstanceOf(AgentRuntime.StartOutcome.Refused.class);
    assertThat(tools.calls()).isEmpty();
  }

  @Test
  void theStepLevelCeilingCheckRefusesAToolWhoseCapabilityIsNotGranted() {
    // Exercised directly, because admission normally catches this first. If the two checks ever
    // disagreed, the step-level one is the last thing between a plan and an ungranted tool.
    final io.reliabilityai.gateway.dataplane.agent.api.RunSnapshot snapshot =
        buildSnapshotWithCeiling(Set.of("chat"));
    final io.reliabilityai.gateway.dataplane.agent.api.StepResult result =
        new ToolSessionRunner(sessions, tools)
            .runToolStep(
                io.reliabilityai.gateway.dataplane.agent.api.StepId.of(run, 0),
                tool("exfiltrate", "email"),
                snapshot,
                "ref",
                clock.now());

    assertThat(result)
        .isInstanceOf(io.reliabilityai.gateway.dataplane.agent.api.StepResult.Failed.class);
    assertThat(((io.reliabilityai.gateway.dataplane.agent.api.StepResult.Failed) result).failure())
        .isEqualTo(FailureClass.STEP_DENIED);
    assertThat(tools.calls()).isEmpty();
  }

  @Test
  void anAffordableStepIsAllottedExactlyItsDeclaredBudget() {
    final Step.Pipeline expensive =
        new Step.Pipeline(
            "a", "i", List.of(), Set.of("chat"), 1L, 900_000L, Duration.ofMinutes(1), true);
    final Plan plan = plan(expensive);
    plans.publish(plan);
    newRuntime();
    runtime.start(run, plan, security("chat"), plan.bounds(), 2_000_000L);
    executor.advance(run);

    assertThat(sessions.requests().get(0).budgetMicros()).isEqualTo(900_000L);
  }

  @Test
  void aStepTheRunCannotAffordTerminatesItRatherThanRunningOnAReducedAllotment() {
    // Silently shrinking the allotment would let a run keep going past its grant on ever-smaller
    // steps, which is a budget bound that never binds.
    final Step.Pipeline expensive =
        new Step.Pipeline(
            "a", "i", List.of(), Set.of("chat"), 1L, 900_000L, Duration.ofMinutes(1), true);
    final Plan plan = plan(expensive);
    plans.publish(plan);
    newRuntime();
    runtime.start(run, plan, security("chat"), plan.bounds(), 900_000L);

    final RunExecutor.Advance outcome = executor.advance(run);
    assertThat(outcome).isInstanceOf(RunExecutor.Advance.Terminated.class);
    assertThat(((RunExecutor.Advance.Terminated) outcome).reason())
        .isEqualTo(TerminalReason.TERMINATED_BUDGET);
    assertThat(sessions.requests()).isEmpty();
  }

  // ---- Cost accounting -------------------------------------------------------------------------

  @Test
  void aSessionThatCannotPriceItselfIsChargedItsFullAllotmentRatherThanNothing() {
    // Unknown is not zero. A zero would make the budget check pass every time, so the bound would
    // be
    // evaluated and never bind — worse than having no bound, because it looks enforced.
    final Plan plan = plan(model("a"));
    sessions.respondWith(request -> new ToolSessionPort.Completed("ok", 0L, false, 1, false));
    start(plan);
    drain(5);

    assertThat(runtime.snapshot(run).orElseThrow().budget().consumedMicros())
        .isEqualTo(plan.stepAt(0).budgetMicros());
  }

  @Test
  void aSessionThatPricesItselfIsChargedWhatItReported() {
    final Plan plan = plan(model("a"));
    sessions.respondWith(request -> new ToolSessionPort.Completed("ok", 137L, true, 1, false));
    start(plan);
    drain(5);

    assertThat(runtime.snapshot(run).orElseThrow().budget().consumedMicros()).isEqualTo(137L);
  }

  @Test
  void aFailedStepStillCostsWhatItSpent() {
    final Plan plan = plan(model("a"));
    sessions.alwaysFail(FailureClass.STEP_PERMANENT, "bad output");
    start(plan);
    drain(5);

    assertThat(runtime.snapshot(run).orElseThrow().budget().consumedMicros()).isPositive();
  }

  // ---- Failure handling through the executor ----------------------------------------------------

  @Test
  void aSeamThatThrowsBecomesARecordedStepFailureRatherThanAStalledRun() {
    final Plan plan = plan(model("a"));
    sessions.throwing(new IllegalStateException("adapter exploded"));
    start(plan);

    assertThat(drain(5)).isInstanceOf(RunExecutor.Advance.Terminated.class);
    assertThat(runs.load(run).orElseThrow().events())
        .anyMatch(event -> event instanceof RunEvent.StepFailed);
  }

  @Test
  void skipSupervisionLetsTheRunCompletePartially() {
    final Plan plan = plan(1, RunBounds.DEFAULT, skipping(), model("a"), model("b"));
    sessions.respondWith(
        request ->
            request.instruction().endsWith("a")
                ? new ToolSessionPort.Failed(FailureClass.TOOL_FAILURE, "nope", 1L, true, 1)
                : new ToolSessionPort.Completed("ok", 1L, true, 1, false));
    start(plan);

    final RunExecutor.Advance outcome = drain(20);
    assertThat(outcome).isInstanceOf(RunExecutor.Advance.Terminated.class);
    assertThat(((RunExecutor.Advance.Terminated) outcome).reason())
        .isEqualTo(TerminalReason.COMPLETED_SUCCESS);
  }

  @Test
  void aNonIdempotentStepIsNotRetriedEvenUnderARetryingPolicy() {
    final Plan plan = plan(1, RunBounds.DEFAULT, retrying(5, 5), irreversibleModel("send"));
    sessions.alwaysFail(FailureClass.STEP_TRANSIENT, "timeout");
    start(plan);
    drain(20);

    assertThat(sessions.requests()).hasSize(1);
  }

  @Test
  void aCrashLoopingStepStopsAtTheRestartIntensityRatherThanBillingForever() {
    final Plan plan = plan(1, RunBounds.DEFAULT, retrying(99, 2), model("flaky"));
    sessions.alwaysFail(FailureClass.STEP_TRANSIENT, "always down");
    start(plan);

    assertThat(drain(50)).isInstanceOf(RunExecutor.Advance.Terminated.class);
    assertThat(sessions.requests()).hasSizeLessThanOrEqualTo(4);
    assertThat(metrics.counter("supervision.restart_intensity_exceeded")).isEqualTo(1L);
  }

  // ---- Read model -------------------------------------------------------------------------------

  @Test
  void theStepReadModelShowsOneRowPerAttemptRatherThanFlatteningRetries() {
    final Plan plan = plan(1, RunBounds.DEFAULT, retrying(3, 5), model("flaky"));
    final int[] attempts = {0};
    sessions.respondWith(
        request ->
            ++attempts[0] < 3
                ? new ToolSessionPort.Failed(FailureClass.STEP_TRANSIENT, "flaky", 1L, true, 1)
                : new ToolSessionPort.Completed("ok", 1L, true, 1, false));
    start(plan);
    drain(20);

    final HistoryBackedStepRepository steps = new HistoryBackedStepRepository(runs);
    assertThat(steps.stepsOf(run)).hasSize(3);
    assertThat(steps.countByStatus(run, StepStatus.FAILED)).isEqualTo(2);
    assertThat(steps.countByStatus(run, StepStatus.SUCCEEDED)).isEqualTo(1);
    assertThat(steps.latestStatus(run, "flaky")).contains(StepStatus.SUCCEEDED);
  }

  @Test
  void theStepReadModelKnowsNothingAboutAnUnknownRun() {
    final HistoryBackedStepRepository steps = new HistoryBackedStepRepository(runs);
    assertThat(steps.stepsOf(RunId.of("ghost"))).isEmpty();
    assertThat(steps.latestStatus(RunId.of("ghost"), "a")).isEmpty();
  }

  @Test
  void theStepReadModelCarriesEachStepsKindAndCost() {
    final Plan plan = plan(tool("fetch", "search"), model("summarise", "fetch"));
    start(plan);
    drain(20);

    final HistoryBackedStepRepository steps = new HistoryBackedStepRepository(runs);
    assertThat(steps.stepsOf(run)).hasSize(2);
    assertThat(steps.stepsOf(run).get(0).kind()).isEqualTo(StepKind.PLUGIN);
    assertThat(steps.stepsOf(run).get(1).kind()).isEqualTo(StepKind.PIPELINE);
  }

  // ---- Idle and unknown
  // --------------------------------------------------------------------------

  @Test
  void advancingAnUnknownRunIsIdleRatherThanAnError() {
    runtimeFor(plan(model("a")));
    assertThat(executor.advance(RunId.of("ghost"))).isInstanceOf(RunExecutor.Advance.Idle.class);
  }

  @Test
  void advancingATerminatedRunIsIdle() {
    final Plan plan = plan(model("a"));
    start(plan);
    drain(10);
    assertThat(executor.advance(run)).isInstanceOf(RunExecutor.Advance.Idle.class);
  }

  @Test
  void metricsRecordTheRunAndItsSteps() {
    final Plan plan = plan(model("a"), model("b"));
    start(plan);
    drain(20);

    assertThat(metrics.counter("run.started")).isEqualTo(1L);
    assertThat(metrics.counter("run.terminated")).isEqualTo(1L);
    assertThat(metrics.counter("step.finished")).isEqualTo(2L);
    assertThat(metrics.counter("replay.divergence")).isZero();
  }

  /** Rebuilds the executor and runtime over the current stores. */
  private void newRuntime() {
    executor =
        new RunExecutor(
            runs,
            plans,
            new ToolSessionRunner(sessions, tools),
            clock,
            metrics,
            io.reliabilityai.gateway.dataplane.agent.api.RunAuditPort.NOOP);
    runtime =
        new AgentRuntime(
            runs,
            plans,
            executor,
            clock,
            metrics,
            io.reliabilityai.gateway.dataplane.agent.api.RunAuditPort.NOOP);
  }

  /** A folded snapshot of a run whose ceiling is exactly the given capability set. */
  private io.reliabilityai.gateway.dataplane.agent.api.RunSnapshot buildSnapshotWithCeiling(
      final Set<String> ceiling) {
    final Plan plan = plan(model("a"));
    plans.publish(plan);
    final RunSecurityContext ceilingContext = security(ceiling.toArray(new String[0]));
    runs.create(RunId.of("ceiling-probe"), AgentFixtures.created(plan, ceilingContext));
    return io.reliabilityai.gateway.dataplane.agent.domain.ReplayEngine.fold(
        plan, runs.load(RunId.of("ceiling-probe")).orElseThrow());
  }

  private static int indexOfFirst(final List<RunEvent> events, final Class<?> type) {
    for (int i = 0; i < events.size(); i++) {
      if (type.isInstance(events.get(i))) {
        return i;
      }
    }
    return -1;
  }
}

package io.reliabilityai.gateway.dataplane.agent.application;

import static io.reliabilityai.gateway.dataplane.agent.AgentFixtures.TENANT;
import static io.reliabilityai.gateway.dataplane.agent.AgentFixtures.model;
import static io.reliabilityai.gateway.dataplane.agent.AgentFixtures.plan;
import static io.reliabilityai.gateway.dataplane.agent.AgentFixtures.retrying;
import static io.reliabilityai.gateway.dataplane.agent.AgentFixtures.security;
import static io.reliabilityai.gateway.dataplane.agent.AgentFixtures.tool;
import static org.assertj.core.api.Assertions.assertThat;

import io.reliabilityai.gateway.dataplane.agent.AgentFixtures.FakeSessions;
import io.reliabilityai.gateway.dataplane.agent.AgentFixtures.FakeTools;
import io.reliabilityai.gateway.dataplane.agent.AgentFixtures.TestClock;
import io.reliabilityai.gateway.dataplane.agent.api.AgentToolPort;
import io.reliabilityai.gateway.dataplane.agent.api.FailureClass;
import io.reliabilityai.gateway.dataplane.agent.api.Plan;
import io.reliabilityai.gateway.dataplane.agent.api.RunAuditPort;
import io.reliabilityai.gateway.dataplane.agent.api.RunBounds;
import io.reliabilityai.gateway.dataplane.agent.api.RunEvent;
import io.reliabilityai.gateway.dataplane.agent.api.RunHistory;
import io.reliabilityai.gateway.dataplane.agent.api.RunId;
import io.reliabilityai.gateway.dataplane.agent.api.RunRepository;
import io.reliabilityai.gateway.dataplane.agent.api.Step;
import io.reliabilityai.gateway.dataplane.agent.api.StepId;
import io.reliabilityai.gateway.dataplane.agent.api.StepKind;
import io.reliabilityai.gateway.dataplane.agent.api.TerminalReason;
import io.reliabilityai.gateway.dataplane.agent.api.ToolSessionPort;
import io.reliabilityai.gateway.dataplane.agent.internal.CanonicalRunSerializer;
import io.reliabilityai.gateway.dataplane.agent.internal.InMemoryPlanRepository;
import io.reliabilityai.gateway.dataplane.agent.internal.InMemoryRunRepository;
import io.reliabilityai.gateway.dataplane.agent.internal.InProcessAgentMetrics;
import io.reliabilityai.gateway.dataplane.agent.internal.JournalRunRepository;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.DisplayNameGeneration;
import org.junit.jupiter.api.DisplayNameGenerator;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Recovery from a node dying mid-step.
 *
 * <p>The headline property, and the reason the whole design is shaped the way it is: a run that
 * dies at step N resumes at step N without re-invoking the model that produced steps 1..N-1. Replay
 * reads recorded results; it does not recompute them. LangGraph re-executes the interrupted node
 * and the CLI agents lose the run outright, so this is where the design earns its cost.
 */
@DisplayNameGeneration(DisplayNameGenerator.ReplaceUnderscores.class)
class CrashRecoveryTest {

  private final InMemoryPlanRepository plans = new InMemoryPlanRepository();
  private final TestClock clock = new TestClock();
  private final InProcessAgentMetrics metrics = new InProcessAgentMetrics();
  private final RunId run = RunId.of("r-1");

  private RunExecutor executorOver(
      final RunRepository runs, final FakeSessions sessions, final FakeTools tools) {
    return new RunExecutor(
        runs, plans, new ToolSessionRunner(sessions, tools), clock, metrics, RunAuditPort.NOOP);
  }

  private AgentRuntime runtimeOver(final RunRepository runs, final RunExecutor executor) {
    return new AgentRuntime(runs, plans, executor, clock, metrics, RunAuditPort.NOOP);
  }

  /** Simulates a node that recorded the scheduling and then died before recording the outcome. */
  private void leaveInterrupted(
      final RunRepository runs, final int stepIndex, final String name, final StepKind kind) {
    final RunHistory history = runs.load(run).orElseThrow();
    runs.append(
        run,
        history.offset(),
        new RunEvent.StepScheduled(
            StepId.of(run, stepIndex),
            stepIndex,
            name,
            kind,
            1,
            StepId.of(run, stepIndex).value() + "@1",
            clock.now()));
  }

  // ---- Interrupted-step resolution ------------------------------------------------------------

  @Test
  void anInterruptedStepWhoseSessionActuallyCompletedIsAdoptedWithoutRerunningIt() {
    // The common case, and the one that costs nothing: the layer below has a terminal record, so
    // the
    // step's work is recovered rather than repeated. Zero extra provider calls, zero extra money.
    final InMemoryRunRepository runs = new InMemoryRunRepository();
    final FakeSessions sessions = new FakeSessions();
    final Plan plan = plan(model("a"), model("b"));
    plans.publish(plan);
    final RunExecutor executor = executorOver(runs, sessions, new FakeTools());
    runtimeOver(runs, executor).start(run, plan, security("chat"), plan.bounds(), 1_000_000L);

    leaveInterrupted(runs, 0, "a", StepKind.PIPELINE);
    sessions.remember(
        StepId.of(run, 0).value() + "@1",
        new ToolSessionPort.Completed("recovered answer", 55L, true, 1, false));

    final RunExecutor.Advance resumed = executor.advance(run);

    assertThat(resumed).isInstanceOf(RunExecutor.Advance.Advanced.class);
    assertThat(sessions.requests()).isEmpty();
    assertThat(sessions.pipelineExecutions()).isZero();
    assertThat(metrics.counter("recovery.interrupted.ADOPTED")).isEqualTo(1L);
  }

  @Test
  void anAdoptedOutcomeKeepsItsRecordedValueAndCost() {
    final InMemoryRunRepository runs = new InMemoryRunRepository();
    final FakeSessions sessions = new FakeSessions();
    final Plan plan = plan(model("a"), model("b"));
    plans.publish(plan);
    final RunExecutor executor = executorOver(runs, sessions, new FakeTools());
    runtimeOver(runs, executor).start(run, plan, security("chat"), plan.bounds(), 1_000_000L);

    leaveInterrupted(runs, 0, "a", StepKind.PIPELINE);
    sessions.remember(
        StepId.of(run, 0).value() + "@1",
        new ToolSessionPort.Completed("recovered answer", 55L, true, 1, false));
    executor.advance(run);

    final var snapshot = runtimeOver(runs, executor).snapshot(run).orElseThrow();
    assertThat(snapshot.resultOf("a")).contains("recovered answer");
    assertThat(snapshot.budget().consumedMicros()).isEqualTo(55L);
  }

  @Test
  void anInterruptedStepWithNoRecordBelowIsTreatedAsFailedRatherThanAssumedSucceeded() {
    // Resolution two. Never guess success: a step whose outcome is unknown must not silently become
    // a value the rest of the plan depends on.
    final InMemoryRunRepository runs = new InMemoryRunRepository();
    final FakeSessions sessions = new FakeSessions().forgetful();
    final Plan plan = plan(model("a"), model("b"));
    plans.publish(plan);
    final RunExecutor executor = executorOver(runs, sessions, new FakeTools());
    runtimeOver(runs, executor).start(run, plan, security("chat"), plan.bounds(), 1_000_000L);

    leaveInterrupted(runs, 0, "a", StepKind.PIPELINE);
    final RunExecutor.Advance resumed = executor.advance(run);

    assertThat(resumed).isInstanceOf(RunExecutor.Advance.Terminated.class);
    assertThat(metrics.counter("recovery.interrupted.ASSUMED_FAILED")).isEqualTo(1L);
    assertThat(runs.load(run).orElseThrow().events())
        .anyMatch(
            event ->
                event instanceof RunEvent.StepFailed failed
                    && failed.failure() == FailureClass.INTERRUPTED_UNRESOLVED);
  }

  @Test
  void anUnresolvedInterruptedStepIsNeverRetriedBecauseItsSideEffectsAreUnknown() {
    // INTERRUPTED_UNRESOLVED is not retryable. The tool may have run; running it again could
    // duplicate
    // an irreversible effect, which is the one thing worse than losing the run.
    final InMemoryRunRepository runs = new InMemoryRunRepository();
    final FakeSessions sessions = new FakeSessions().forgetful();
    final Plan plan = plan(1, RunBounds.DEFAULT, retrying(5, 5), model("a"));
    plans.publish(plan);
    final RunExecutor executor = executorOver(runs, sessions, new FakeTools());
    runtimeOver(runs, executor).start(run, plan, security("chat"), plan.bounds(), 1_000_000L);

    leaveInterrupted(runs, 0, "a", StepKind.PIPELINE);
    executor.advance(run);

    assertThat(sessions.requests()).isEmpty();
    assertThat(runs.load(run).orElseThrow().terminated()).isTrue();
  }

  @Test
  void anInterruptedToolStepIsResolvedFromTheToolLayer() {
    final InMemoryRunRepository runs = new InMemoryRunRepository();
    final FakeTools tools = new FakeTools();
    final Plan plan = plan(tool("fetch", "search"), model("use", "fetch"));
    plans.publish(plan);
    final RunExecutor executor = executorOver(runs, new FakeSessions(), tools);
    runtimeOver(runs, executor)
        .start(run, plan, security("chat", "search"), plan.bounds(), 1_000_000L);

    leaveInterrupted(runs, 0, "fetch", StepKind.PLUGIN);
    tools.invoke(
        new AgentToolPort.ToolCall(
            StepId.of(run, 0).value() + "@1",
            "search",
            "{}",
            TENANT,
            security("search").correlationId(),
            1L,
            Duration.ofSeconds(5)));

    final RunExecutor.Advance resumed = executor.advance(run);
    assertThat(resumed).isInstanceOf(RunExecutor.Advance.Advanced.class);
    assertThat(metrics.counter("recovery.interrupted.ADOPTED")).isEqualTo(1L);
  }

  @Test
  void anInterruptedPureStepIsSimplyRecomputedBecauseNobodyOutsideObservedIt() {
    // The only place re-execution during recovery is safe, and it is safe for a reason that does
    // not
    // generalise: a wait, a condition and a branch have no side effects to duplicate.
    final InMemoryRunRepository runs = new InMemoryRunRepository();
    final Plan plan = plan(new Step.Wait("pause", Duration.ofSeconds(1)), model("after"));
    plans.publish(plan);
    final RunExecutor executor = executorOver(runs, new FakeSessions(), new FakeTools());
    runtimeOver(runs, executor).start(run, plan, security("chat"), plan.bounds(), 1_000_000L);

    leaveInterrupted(runs, 0, "pause", StepKind.WAIT);
    final RunExecutor.Advance resumed = executor.advance(run);

    assertThat(resumed).isInstanceOf(RunExecutor.Advance.Parked.class);
    assertThat(metrics.counter("recovery.interrupted.RECOMPUTED")).isEqualTo(1L);
  }

  @Test
  void aRecoveredStepGoesThroughTheSameSupervisionPathAsANormalOne() {
    // A second path would be a second policy, and the two would drift.
    final InMemoryRunRepository runs = new InMemoryRunRepository();
    final FakeSessions sessions = new FakeSessions();
    final Plan plan = plan(1, RunBounds.DEFAULT, retrying(3, 5), model("a"));
    plans.publish(plan);
    final RunExecutor executor = executorOver(runs, sessions, new FakeTools());
    runtimeOver(runs, executor).start(run, plan, security("chat"), plan.bounds(), 1_000_000L);

    leaveInterrupted(runs, 0, "a", StepKind.PIPELINE);
    sessions.remember(
        StepId.of(run, 0).value() + "@1",
        new ToolSessionPort.Failed(FailureClass.STEP_TRANSIENT, "died", 2L, true, 1));
    executor.advance(run);

    assertThat(runs.load(run).orElseThrow().events())
        .anyMatch(
            event ->
                event instanceof RunEvent.SupervisionApplied supervision
                    && "retry".equals(supervision.decision()));
  }

  // ---- Recovery across a real restart ----------------------------------------------------------

  @Test
  void aRunSurvivesLosingTheProcessAndFinishesOnAFreshNode(@TempDir final Path dir) {
    final JournalRunRepository firstNode =
        new JournalRunRepository(dir, new CanonicalRunSerializer());
    final FakeSessions firstSessions = new FakeSessions();
    final Plan plan = plan(model("a"), model("b"), model("c"));
    plans.publish(plan);
    final RunExecutor firstExecutor = executorOver(firstNode, firstSessions, new FakeTools());
    runtimeOver(firstNode, firstExecutor)
        .start(run, plan, security("chat"), plan.bounds(), 1_000_000L);

    firstExecutor.advance(run);
    firstExecutor.advance(run);
    assertThat(firstSessions.requests()).hasSize(2);

    // The process dies. A different node opens the same journal and carries on.
    final JournalRunRepository secondNode =
        new JournalRunRepository(dir, new CanonicalRunSerializer());
    final FakeSessions secondSessions = new FakeSessions();
    final RunExecutor secondExecutor = executorOver(secondNode, secondSessions, new FakeTools());

    RunExecutor.Advance last = null;
    for (int i = 0; i < 10 && !(last instanceof RunExecutor.Advance.Terminated); i++) {
      last = secondExecutor.advance(run);
    }

    assertThat(last).isInstanceOf(RunExecutor.Advance.Terminated.class);
    assertThat(((RunExecutor.Advance.Terminated) last).reason())
        .isEqualTo(TerminalReason.COMPLETED_SUCCESS);
    // The decisive assertion: the second node ran only the step that was left, not the two already
    // done. Recovery cost one model call, not three.
    assertThat(secondSessions.requests()).hasSize(1);
    assertThat(secondSessions.pipelineExecutions()).isEqualTo(1);
  }

  @Test
  void recoveringALongRunCostsNoInferenceForTheStepsAlreadyDone(@TempDir final Path dir) {
    final JournalRunRepository firstNode =
        new JournalRunRepository(dir, new CanonicalRunSerializer());
    final Step[] steps = new Step[20];
    for (int i = 0; i < steps.length; i++) {
      steps[i] = model("s" + i);
    }
    final Plan plan = plan(steps);
    plans.publish(plan);
    final RunExecutor firstExecutor = executorOver(firstNode, new FakeSessions(), new FakeTools());
    runtimeOver(firstNode, firstExecutor)
        .start(run, plan, security("chat"), plan.bounds(), 10_000_000L);
    for (int i = 0; i < 18; i++) {
      firstExecutor.advance(run);
    }

    final JournalRunRepository secondNode =
        new JournalRunRepository(dir, new CanonicalRunSerializer());
    final FakeSessions secondSessions = new FakeSessions();
    final RunExecutor secondExecutor = executorOver(secondNode, secondSessions, new FakeTools());

    RunExecutor.Advance last = null;
    for (int i = 0; i < 10 && !(last instanceof RunExecutor.Advance.Terminated); i++) {
      last = secondExecutor.advance(run);
    }

    assertThat(last).isInstanceOf(RunExecutor.Advance.Terminated.class);
    assertThat(secondSessions.pipelineExecutions()).isEqualTo(2);
  }

  @Test
  void aRunInterruptedMidStepResumesFromTheJournalWithoutRepeatingEarlierSteps(
      @TempDir final Path dir) {
    final JournalRunRepository firstNode =
        new JournalRunRepository(dir, new CanonicalRunSerializer());
    final Plan plan = plan(model("a"), model("b"));
    plans.publish(plan);
    final RunExecutor firstExecutor = executorOver(firstNode, new FakeSessions(), new FakeTools());
    runtimeOver(firstNode, firstExecutor)
        .start(run, plan, security("chat"), plan.bounds(), 1_000_000L);
    firstExecutor.advance(run);
    leaveInterrupted(firstNode, 1, "b", StepKind.PIPELINE);

    final JournalRunRepository secondNode =
        new JournalRunRepository(dir, new CanonicalRunSerializer());
    assertThat(secondNode.load(run).orElseThrow().endsInterrupted()).isTrue();

    final FakeSessions secondSessions = new FakeSessions().forgetful();
    final RunExecutor secondExecutor = executorOver(secondNode, secondSessions, new FakeTools());
    secondExecutor.advance(run);

    assertThat(secondSessions.requests()).isEmpty();
    assertThat(secondNode.load(run).orElseThrow().terminated()).isTrue();
  }

  // ---- The recovery sweep -----------------------------------------------------------------------

  @Test
  void theSweepFindsEveryUnfinishedRunOfATenant() {
    final InMemoryRunRepository runs = new InMemoryRunRepository();
    final Plan plan = plan(model("a"));
    plans.publish(plan);
    final RunExecutor executor = executorOver(runs, new FakeSessions(), new FakeTools());
    final AgentRuntime runtime = runtimeOver(runs, executor);
    for (int i = 0; i < 5; i++) {
      runtime.start(RunId.of("r-" + i), plan, security("chat"), plan.bounds(), 1_000_000L);
    }

    assertThat(RunRecovery.sweep(runs, TENANT, 100)).hasSize(5);
  }

  @Test
  void theSweepStopsSeeingARunOnceItHasEnded() {
    final InMemoryRunRepository runs = new InMemoryRunRepository();
    final Plan plan = plan(model("a"));
    plans.publish(plan);
    final RunExecutor executor = executorOver(runs, new FakeSessions(), new FakeTools());
    runtimeOver(runs, executor).start(run, plan, security("chat"), plan.bounds(), 1_000_000L);
    executor.advance(run);
    executor.advance(run);

    assertThat(RunRecovery.sweep(runs, TENANT, 100)).isEmpty();
  }

  @Test
  void theSweepIsIdempotentSoRunningItTwiceResolvesNothingTwice() {
    final InMemoryRunRepository runs = new InMemoryRunRepository();
    final FakeSessions sessions = new FakeSessions();
    final Plan plan = plan(model("a"), model("b"));
    plans.publish(plan);
    final RunExecutor executor = executorOver(runs, sessions, new FakeTools());
    runtimeOver(runs, executor).start(run, plan, security("chat"), plan.bounds(), 1_000_000L);

    leaveInterrupted(runs, 0, "a", StepKind.PIPELINE);
    sessions.remember(
        StepId.of(run, 0).value() + "@1", new ToolSessionPort.Completed("v", 1L, true, 1, false));

    executor.advance(run);
    final List<RunEvent> afterFirst = runs.load(run).orElseThrow().events();
    final long completions =
        afterFirst.stream().filter(event -> event instanceof RunEvent.StepCompleted).count();
    assertThat(completions).isEqualTo(1L);
  }

  @Test
  void resolutionIsClassifiedForMetricsSoOperatorsSeeHowOftenNodesDieMidStep() {
    assertThat(RunRecovery.Resolution.values())
        .contains(
            RunRecovery.Resolution.ADOPTED,
            RunRecovery.Resolution.ASSUMED_FAILED,
            RunRecovery.Resolution.RECOMPUTED);
  }

  @Test
  void onlyTheKindsThatOpenedSomethingBelowAreResolvableByLookup() {
    assertThat(RunRecovery.resolvableByLookup(StepKind.PIPELINE)).isTrue();
    assertThat(RunRecovery.resolvableByLookup(StepKind.PLUGIN)).isTrue();
    assertThat(RunRecovery.resolvableByLookup(StepKind.WAIT)).isFalse();
    assertThat(RunRecovery.resolvableByLookup(StepKind.CONDITION)).isFalse();
    assertThat(RunRecovery.resolvableByLookup(StepKind.BRANCH)).isFalse();
  }

  @Test
  void aPureStepIsSafelyRepeatableAndANonDeterministicOneIsNot() {
    assertThat(
            RunRecovery.safelyRepeatable(
                new RunEvent.StepScheduled(
                    StepId.of(run, 0), 0, "w", StepKind.WAIT, 1, "r", clock.now())))
        .isTrue();
    assertThat(
            RunRecovery.safelyRepeatable(
                new RunEvent.StepScheduled(
                    StepId.of(run, 0), 0, "m", StepKind.PIPELINE, 1, "r", clock.now())))
        .isFalse();
  }
}

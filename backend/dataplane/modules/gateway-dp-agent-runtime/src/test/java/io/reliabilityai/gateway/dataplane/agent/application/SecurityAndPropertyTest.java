package io.reliabilityai.gateway.dataplane.agent.application;

import static io.reliabilityai.gateway.dataplane.agent.AgentFixtures.OTHER_TENANT;
import static io.reliabilityai.gateway.dataplane.agent.AgentFixtures.TENANT;
import static io.reliabilityai.gateway.dataplane.agent.AgentFixtures.model;
import static io.reliabilityai.gateway.dataplane.agent.AgentFixtures.plan;
import static io.reliabilityai.gateway.dataplane.agent.AgentFixtures.retrying;
import static io.reliabilityai.gateway.dataplane.agent.AgentFixtures.security;
import static io.reliabilityai.gateway.dataplane.agent.AgentFixtures.securityIn;
import static io.reliabilityai.gateway.dataplane.agent.AgentFixtures.skipping;
import static io.reliabilityai.gateway.dataplane.agent.AgentFixtures.tool;
import static org.assertj.core.api.Assertions.assertThat;

import io.reliabilityai.gateway.dataplane.agent.AgentFixtures.FakeSessions;
import io.reliabilityai.gateway.dataplane.agent.AgentFixtures.FakeTools;
import io.reliabilityai.gateway.dataplane.agent.AgentFixtures.TestClock;
import io.reliabilityai.gateway.dataplane.agent.api.ConditionOperator;
import io.reliabilityai.gateway.dataplane.agent.api.FailureClass;
import io.reliabilityai.gateway.dataplane.agent.api.Plan;
import io.reliabilityai.gateway.dataplane.agent.api.RunAuditPort;
import io.reliabilityai.gateway.dataplane.agent.api.RunBounds;
import io.reliabilityai.gateway.dataplane.agent.api.RunEvent;
import io.reliabilityai.gateway.dataplane.agent.api.RunHistory;
import io.reliabilityai.gateway.dataplane.agent.api.RunId;
import io.reliabilityai.gateway.dataplane.agent.api.RunSnapshot;
import io.reliabilityai.gateway.dataplane.agent.api.Step;
import io.reliabilityai.gateway.dataplane.agent.api.ToolSessionPort;
import io.reliabilityai.gateway.dataplane.agent.domain.ReplayEngine;
import io.reliabilityai.gateway.dataplane.agent.internal.InMemoryPlanRepository;
import io.reliabilityai.gateway.dataplane.agent.internal.InMemoryRunRepository;
import io.reliabilityai.gateway.dataplane.agent.internal.InProcessAgentMetrics;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.Set;
import org.junit.jupiter.api.DisplayNameGeneration;
import org.junit.jupiter.api.DisplayNameGenerator;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Security properties, and the invariants that must hold for every plan rather than for a chosen
 * one.
 *
 * <p>The property tests generate plans at random and assert what AD-025 promises unconditionally:
 * that every run terminates, that the budget is never exceeded by more than one step's allotment,
 * and that replaying a history reproduces it exactly.
 */
@DisplayNameGeneration(DisplayNameGenerator.ReplaceUnderscores.class)
class SecurityAndPropertyTest {

  private final InMemoryRunRepository runs = new InMemoryRunRepository();
  private final InMemoryPlanRepository plans = new InMemoryPlanRepository();
  private final TestClock clock = new TestClock();
  private final InProcessAgentMetrics metrics = new InProcessAgentMetrics();
  private final FakeSessions sessions = new FakeSessions();
  private final FakeTools tools = new FakeTools();

  private final RunExecutor executor =
      new RunExecutor(
          runs, plans, new ToolSessionRunner(sessions, tools), clock, metrics, RunAuditPort.NOOP);
  private final AgentRuntime runtime =
      new AgentRuntime(runs, plans, executor, clock, metrics, RunAuditPort.NOOP);

  private RunExecutor.Advance drain(final RunId run, final int max) {
    RunExecutor.Advance last = new RunExecutor.Advance.Idle();
    for (int i = 0; i < max; i++) {
      last = executor.advance(run);
      if (last instanceof RunExecutor.Advance.Terminated
          || last instanceof RunExecutor.Advance.Idle
          || last instanceof RunExecutor.Advance.Stalled) {
        return last;
      }
    }
    return last;
  }

  // ---- Security
  // -----------------------------------------------------------------------------------

  @Test
  void everyStepReAuthorizesBecauseEverySessionIsANewPipelineExecution() {
    // Nothing is cached between steps. The pipeline sees each step as a fresh request, so step 40
    // is
    // authorized exactly as rigorously as step 1.
    final Plan plan = plan(model("a"), model("b"), model("c"), model("d"), model("e"));
    plans.publish(plan);
    final RunId run = RunId.of("r-1");
    runtime.start(run, plan, security("chat"), plan.bounds(), 1_000_000L);
    drain(run, 20);

    assertThat(sessions.pipelineExecutions()).isEqualTo(5);
    assertThat(sessions.requests().stream().map(ToolSessionPort.SessionRequest::sessionRef))
        .doesNotHaveDuplicates();
  }

  @Test
  void theSecurityContextIsFixedAtAdmissionAndIdenticalOnEveryStep() {
    final Plan plan = plan(model("a"), model("b"), model("c"));
    plans.publish(plan);
    final RunId run = RunId.of("r-1");
    runtime.start(run, plan, security("chat"), plan.bounds(), 1_000_000L);
    drain(run, 20);

    assertThat(sessions.requests())
        .allMatch(request -> request.tenant().equals(TENANT))
        .allMatch(request -> "corr-1".equals(request.correlationId().value()));
  }

  @Test
  void nothingInTheRuntimeCanWidenARunsCapabilityCeiling() {
    final Plan plan = plan(model("a"));
    plans.publish(plan);
    final RunId run = RunId.of("r-1");
    runtime.start(run, plan, security("chat"), plan.bounds(), 1_000_000L);

    final RunSnapshot before = runtime.snapshot(run).orElseThrow();
    drain(run, 10);
    final RunSnapshot after = runtime.snapshot(run).orElseThrow();
    assertThat(after.security().capabilities()).isEqualTo(before.security().capabilities());
  }

  @Test
  void aRunNeverSeesAnotherTenantsRuns() {
    final Plan plan = plan(model("a"));
    plans.publish(plan);
    runtime.start(RunId.of("mine"), plan, security("chat"), plan.bounds(), 1_000L);
    runtime.start(
        RunId.of("theirs"), plan, securityIn(OTHER_TENANT, "c2", "chat"), plan.bounds(), 1_000L);

    assertThat(runs.claimable(TENANT, clock.now(), 100)).containsExactly(RunId.of("mine"));
    assertThat(runs.unfinished(OTHER_TENANT, 100)).containsExactly(RunId.of("theirs"));
  }

  @Test
  void aSessionIsToldTheRunsAccumulatedTaintSoTheRuleOfTwoSpansSessions() {
    // AD-025 §60.2 — the property C13 cannot provide, because it cannot see across sessions. Limb
    // one
    // arrives in step 1; the session opened for step 2 is told about it.
    final Plan plan = plan(tool("read", "search"), model("act", "read"));
    plans.publish(plan);
    final RunId run = RunId.of("r-1");
    runtime.start(run, plan, security("chat", "search"), plan.bounds(), 1_000_000L);
    drain(run, 20);

    assertThat(sessions.requests()).hasSize(1);
    assertThat(sessions.requests().get(0).inboundTainted()).isTrue();
  }

  @Test
  void taintPersistsForTheRestOfTheRunAndNeverDecays() {
    final Plan plan =
        plan(tool("read", "search"), model("one", "read"), model("two"), model("three"));
    plans.publish(plan);
    final RunId run = RunId.of("r-1");
    runtime.start(run, plan, security("chat", "search"), plan.bounds(), 1_000_000L);
    drain(run, 30);

    assertThat(sessions.requests()).hasSize(3);
    assertThat(sessions.requests()).allMatch(ToolSessionPort.SessionRequest::inboundTainted);
  }

  @Test
  void aRunHoldsNoSecretsAndPassesNoneToASession() {
    final Plan plan = plan(model("a"));
    plans.publish(plan);
    final RunId run = RunId.of("r-1");
    runtime.start(run, plan, security("chat"), plan.bounds(), 1_000_000L);
    drain(run, 10);

    // The session request carries identity and capabilities, and no credential material of any
    // kind.
    final ToolSessionPort.SessionRequest request = sessions.requests().get(0);
    assertThat(request.toString()).doesNotContain("secret").doesNotContain("token");
  }

  @Test
  void theHistoryRecordsDigestsRatherThanBecomingACopyOfEveryPayload() {
    final Plan plan = plan(model("a"));
    plans.publish(plan);
    final RunId run = RunId.of("r-1");
    sessions.respondWith(
        request -> new ToolSessionPort.Completed("x".repeat(50_000), 1L, true, 1, false));
    runtime.start(run, plan, security("chat"), plan.bounds(), 1_000_000L);
    drain(run, 10);

    final RunEvent.StepCompleted completed =
        (RunEvent.StepCompleted)
            runs.load(run).orElseThrow().events().stream()
                .filter(event -> event instanceof RunEvent.StepCompleted)
                .findFirst()
                .orElseThrow();
    assertThat(completed.value()).hasSizeLessThan(50_000);
    assertThat(completed.value()).endsWith("[truncated]");
    assertThat(completed.valueDigest()).isNotBlank();
  }

  @Test
  void theDigestCoversTheWholeValueEvenWhenTheRecordedCopyIsTruncated() {
    // Truncation must not weaken divergence detection, so the digest is taken before bounding.
    final String full = "y".repeat(50_000);
    assertThat(io.reliabilityai.gateway.dataplane.agent.domain.Digest.of(full))
        .isNotEqualTo(
            io.reliabilityai.gateway.dataplane.agent.domain.Digest.of(RunExecutor.bound(full)));
  }

  @Test
  void aRunCannotRaiseItsOwnBoundsOrBudgetMidFlight() {
    final Plan plan = plan(model("a"), model("b"));
    plans.publish(plan);
    final RunId run = RunId.of("r-1");
    runtime.start(run, plan, security("chat"), plan.bounds(), 5_000L);

    final RunSnapshot before = runtime.snapshot(run).orElseThrow();
    drain(run, 20);
    final RunSnapshot after = runtime.snapshot(run).orElseThrow();
    assertThat(after.bounds()).isEqualTo(before.bounds());
    assertThat(after.budget().grantMicros()).isEqualTo(before.budget().grantMicros());
  }

  // ---- Properties over generated plans
  // --------------------------------------------------------------

  @ParameterizedTest
  @ValueSource(longs = {1L, 2L, 3L, 5L, 8L, 13L, 21L, 34L, 55L, 89L})
  void everyRunTerminatesWhateverThePlanAndWhateverTheSeamsDo(final long seed) {
    // AD-025 §66. The guarantee holds independently of the plan's behaviour, the model's behaviour
    // or
    // any tool's behaviour, so the generator is free to be adversarial.
    final Random random = new Random(seed);
    final Plan plan = randomPlan(random, "p" + seed);
    plans.publish(plan);
    sessions.respondWith(
        request ->
            random.nextInt(3) == 0
                ? new ToolSessionPort.Failed(FailureClass.STEP_TRANSIENT, "flaky", 1L, true, 1)
                : new ToolSessionPort.Completed("ok", random.nextInt(50), true, 1, false));

    final RunId run = RunId.of("r-" + seed);
    runtime.start(run, plan, security("chat", "search"), plan.bounds(), 20_000L);

    final RunExecutor.Advance last = drain(run, 500);
    assertThat(last)
        .as("run must reach a terminal state")
        .isInstanceOf(RunExecutor.Advance.Terminated.class);
    assertThat(runtime.snapshot(run).orElseThrow().terminal()).isTrue();
  }

  @ParameterizedTest
  @ValueSource(longs = {1L, 2L, 3L, 5L, 8L, 13L})
  void aRunNeverSpendsMoreThanItsGrantPlusOneStepsAllotment(final long seed) {
    // The bound is checked before each step, so the only overrun possible is the step in flight
    // when
    // the remainder ran out. Anything larger would mean the check is not binding.
    final Random random = new Random(seed);
    final Plan plan = randomPlan(random, "b" + seed);
    plans.publish(plan);
    sessions.respondWith(request -> new ToolSessionPort.Completed("ok", 400L, true, 1, false));

    final long grant = 3_000L;
    final RunId run = RunId.of("r-" + seed);
    runtime.start(run, plan, security("chat", "search"), plan.bounds(), grant);
    drain(run, 500);

    final RunSnapshot snapshot = runtime.snapshot(run).orElseThrow();
    final long largestStep = plan.steps().stream().mapToLong(Step::budgetMicros).max().orElse(0L);
    assertThat(snapshot.budget().consumedMicros()).isLessThanOrEqualTo(grant + largestStep);
  }

  @ParameterizedTest
  @ValueSource(longs = {1L, 2L, 3L, 5L, 8L, 13L})
  void foldingAHistoryTwiceAlwaysProducesTheSameSnapshot(final long seed) {
    final Random random = new Random(seed);
    final Plan plan = randomPlan(random, "d" + seed);
    plans.publish(plan);
    sessions.respondWith(
        request ->
            random.nextInt(4) == 0
                ? new ToolSessionPort.Failed(FailureClass.STEP_TRANSIENT, "f", 1L, true, 1)
                : new ToolSessionPort.Completed("ok", 3L, true, 1, false));

    final RunId run = RunId.of("r-" + seed);
    runtime.start(run, plan, security("chat", "search"), plan.bounds(), 20_000L);
    drain(run, 500);

    final RunHistory history = runs.load(run).orElseThrow();
    assertThat(ReplayEngine.fold(plan, history)).isEqualTo(ReplayEngine.fold(plan, history));
  }

  @ParameterizedTest
  @ValueSource(longs = {1L, 2L, 3L, 5L})
  void replayingEveryPrefixOfAHistoryNeverThrowsAndNeverGoesBackwards(final long seed) {
    final Random random = new Random(seed);
    final Plan plan = randomPlan(random, "p2-" + seed);
    plans.publish(plan);
    final RunId run = RunId.of("r-" + seed);
    runtime.start(run, plan, security("chat", "search"), plan.bounds(), 20_000L);
    drain(run, 500);

    final RunHistory history = runs.load(run).orElseThrow();
    long lastOffset = -1L;
    for (long upTo = 1; upTo <= history.offset(); upTo++) {
      final RunSnapshot prefix = ReplayEngine.foldTo(plan, history, upTo);
      assertThat(prefix.cursor().historyOffset()).isGreaterThan(lastOffset);
      lastOffset = prefix.cursor().historyOffset();
    }
  }

  @ParameterizedTest
  @ValueSource(longs = {1L, 2L, 3L, 5L, 8L})
  void everyRunEndsWithExactlyOneTerminationEventAndNothingAfterIt(final long seed) {
    final Random random = new Random(seed);
    final Plan plan = randomPlan(random, "t" + seed);
    plans.publish(plan);
    final RunId run = RunId.of("r-" + seed);
    runtime.start(run, plan, security("chat", "search"), plan.bounds(), 20_000L);
    drain(run, 500);

    final List<RunEvent> events = runs.load(run).orElseThrow().events();
    final long terminations =
        events.stream().filter(event -> event instanceof RunEvent.RunTerminated).count();
    assertThat(terminations).isEqualTo(1L);
    assertThat(events.get(events.size() - 1)).isInstanceOf(RunEvent.RunTerminated.class);
  }

  @ParameterizedTest
  @ValueSource(longs = {1L, 2L, 3L, 5L, 8L})
  void everyHistoryBeginsWithCreationAndIsFoldableEndToEnd(final long seed) {
    final Random random = new Random(seed);
    final Plan plan = randomPlan(random, "c" + seed);
    plans.publish(plan);
    final RunId run = RunId.of("r-" + seed);
    runtime.start(run, plan, security("chat", "search"), plan.bounds(), 20_000L);
    drain(run, 500);

    final RunHistory history = runs.load(run).orElseThrow();
    assertThat(history.events().get(0)).isInstanceOf(RunEvent.RunCreated.class);
    assertThat(ReplayEngine.fold(plan, history)).isNotNull();
  }

  @ParameterizedTest
  @ValueSource(longs = {1L, 2L, 3L})
  void aRunNeverExceedsItsStepBound(final long seed) {
    final Random random = new Random(seed);
    final RunBounds tight = new RunBounds(6, 2, 2, Duration.ofHours(1), 20_000L);
    final Plan plan =
        new Plan(
            io.reliabilityai.gateway.dataplane.agent.api.PlanId.of("s" + seed),
            1,
            randomSteps(random, 5),
            tight,
            retrying(3, 3));
    plans.publish(plan);
    sessions.respondWith(
        request ->
            random.nextBoolean()
                ? new ToolSessionPort.Failed(FailureClass.STEP_TRANSIENT, "f", 1L, true, 1)
                : new ToolSessionPort.Completed("ok", 1L, true, 1, false));

    final RunId run = RunId.of("r-" + seed);
    runtime.start(run, plan, security("chat", "search"), tight, 20_000L);
    drain(run, 500);

    assertThat(runtime.snapshot(run).orElseThrow().stepsExecuted())
        .isLessThanOrEqualTo(tight.maxSteps() + 1);
  }

  /** A small plan of model, tool, condition and wait steps, wired so every reference resolves. */
  private static Plan randomPlan(final Random random, final String id) {
    return new Plan(
        io.reliabilityai.gateway.dataplane.agent.api.PlanId.of(id),
        1,
        randomSteps(random, 1 + random.nextInt(6)),
        RunBounds.DEFAULT,
        random.nextBoolean() ? retrying(2 + random.nextInt(3), 3) : skipping());
  }

  private static List<Step> randomSteps(final Random random, final int count) {
    final List<Step> steps = new ArrayList<>();
    steps.add(
        new Step.Pipeline(
            "s0", "start", List.of(), Set.of("chat"), 8L, 400L, Duration.ofMinutes(1), true));
    for (int i = 1; i < count; i++) {
      final String name = "s" + i;
      final String previous = "s" + (i - 1);
      switch (random.nextInt(4)) {
        case 0 ->
            steps.add(
                new Step.Plugin(
                    name, "search", "{}", List.of(previous), 200L, Duration.ofSeconds(30), true));
        case 1 -> steps.add(new Step.Wait(name, Duration.ZERO));
        case 2 -> steps.add(new Step.Condition(name, previous, ConditionOperator.EXISTS, ""));
        default ->
            steps.add(
                new Step.Pipeline(
                    name,
                    "go",
                    List.of(previous),
                    Set.of("chat"),
                    8L,
                    400L,
                    Duration.ofMinutes(1),
                    true));
      }
    }
    return steps;
  }
}

package io.reliabilityai.gateway.dataplane.agent.application;

import static io.reliabilityai.gateway.dataplane.agent.AgentFixtures.OTHER_TENANT;
import static io.reliabilityai.gateway.dataplane.agent.AgentFixtures.TENANT;
import static io.reliabilityai.gateway.dataplane.agent.AgentFixtures.model;
import static io.reliabilityai.gateway.dataplane.agent.AgentFixtures.plan;
import static io.reliabilityai.gateway.dataplane.agent.AgentFixtures.security;
import static io.reliabilityai.gateway.dataplane.agent.AgentFixtures.securityIn;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.reliabilityai.gateway.dataplane.agent.AgentFixtures.FakeSessions;
import io.reliabilityai.gateway.dataplane.agent.AgentFixtures.FakeTools;
import io.reliabilityai.gateway.dataplane.agent.AgentFixtures.TestClock;
import io.reliabilityai.gateway.dataplane.agent.api.CancellationCause;
import io.reliabilityai.gateway.dataplane.agent.api.Plan;
import io.reliabilityai.gateway.dataplane.agent.api.RunAuditPort;
import io.reliabilityai.gateway.dataplane.agent.api.RunBounds;
import io.reliabilityai.gateway.dataplane.agent.api.RunId;
import io.reliabilityai.gateway.dataplane.agent.api.RunState;
import io.reliabilityai.gateway.dataplane.agent.api.Step;
import io.reliabilityai.gateway.dataplane.agent.api.TerminalReason;
import io.reliabilityai.gateway.dataplane.agent.internal.InMemoryPlanRepository;
import io.reliabilityai.gateway.dataplane.agent.internal.InMemoryRunRepository;
import io.reliabilityai.gateway.dataplane.agent.internal.InProcessAgentMetrics;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DisplayNameGeneration;
import org.junit.jupiter.api.DisplayNameGenerator;
import org.junit.jupiter.api.Test;

/** The pull scheduler and the runtime facade. */
@DisplayNameGeneration(DisplayNameGenerator.ReplaceUnderscores.class)
class SchedulerAndRuntimeTest {

  private final InMemoryRunRepository runs = new InMemoryRunRepository();
  private final InMemoryPlanRepository plans = new InMemoryPlanRepository();
  private final FakeSessions sessions = new FakeSessions();
  private final TestClock clock = new TestClock();
  private final InProcessAgentMetrics metrics = new InProcessAgentMetrics();

  private final RunExecutor executor =
      new RunExecutor(
          runs,
          plans,
          new ToolSessionRunner(sessions, new FakeTools()),
          clock,
          metrics,
          RunAuditPort.NOOP);
  private final AgentRuntime runtime =
      new AgentRuntime(runs, plans, executor, clock, metrics, RunAuditPort.NOOP);

  private RunScheduler schedulerNamed(final String node) {
    return new RunScheduler(runs, executor, clock, node, Duration.ofSeconds(30), 10);
  }

  private Plan publish(final Plan plan) {
    plans.publish(plan);
    return plan;
  }

  // ---- AgentRuntime -----------------------------------------------------------------------------

  @Test
  void startingARunMakesItLoadableAndClaimable() {
    final Plan plan = publish(plan(model("a")));
    final AgentRuntime.StartOutcome outcome =
        runtime.start(RunId.of("r-1"), plan, security("chat"), plan.bounds(), 1_000_000L);

    assertThat(outcome).isInstanceOf(AgentRuntime.StartOutcome.Started.class);
    assertThat(runtime.lookup(RunId.of("r-1"))).isPresent();
    assertThat(runs.claimable(TENANT, clock.now(), 10)).containsExactly(RunId.of("r-1"));
  }

  @Test
  void aNewRunIsQueuedRatherThanLeftInCreated() {
    // Queueing is a second durable write, deliberately, so a node can never claim a run whose
    // creation has not been acknowledged.
    final Plan plan = publish(plan(model("a")));
    runtime.start(RunId.of("r-1"), plan, security("chat"), plan.bounds(), 1_000_000L);
    assertThat(runtime.snapshot(RunId.of("r-1")).orElseThrow().state()).isEqualTo(RunState.QUEUED);
  }

  @Test
  void startingTheSameRunIdTwiceIsRefusedSoARetriedSubmissionIsIdempotent() {
    final Plan plan = publish(plan(model("a")));
    runtime.start(RunId.of("r-1"), plan, security("chat"), plan.bounds(), 1_000_000L);
    final AgentRuntime.StartOutcome second =
        runtime.start(RunId.of("r-1"), plan, security("chat"), plan.bounds(), 1_000_000L);

    assertThat(second).isInstanceOf(AgentRuntime.StartOutcome.Refused.class);
    assertThat(((AgentRuntime.StartOutcome.Refused) second).reason()).contains("already exists");
  }

  @Test
  void aPlanTheRepositoryDoesNotRetainCannotStartARun() {
    final Plan unpublished = plan(model("a"));
    final AgentRuntime.StartOutcome outcome =
        runtime.start(RunId.of("r-1"), unpublished, security("chat"), unpublished.bounds(), 1L);
    assertThat(outcome).isInstanceOf(AgentRuntime.StartOutcome.Refused.class);
    assertThat(runs.load(RunId.of("r-1"))).isEmpty();
  }

  @Test
  void aRunsBoundsAreThePlansTightenedByTheCallersRequest() {
    final RunBounds tight = new RunBounds(3, 1, 1, Duration.ofMinutes(5), 500L);
    final Plan plan = publish(plan(model("a")));
    runtime.start(RunId.of("r-1"), plan, security("chat"), tight, 1_000_000L);

    final var bounds = runtime.snapshot(RunId.of("r-1")).orElseThrow().bounds();
    assertThat(bounds.maxSteps()).isEqualTo(3);
    assertThat(bounds.wallClock()).isEqualTo(Duration.ofMinutes(5));
  }

  @Test
  void aGrantIsCappedByTheSpendBoundSoTheTwoCannotDisagree() {
    final RunBounds capped = new RunBounds(50, 3, 5, Duration.ofHours(1), 900L);
    final Plan plan = publish(plan(model("a")));
    runtime.start(RunId.of("r-1"), plan, security("chat"), capped, 1_000_000L);

    assertThat(runtime.snapshot(RunId.of("r-1")).orElseThrow().budget().grantMicros())
        .isEqualTo(900L);
  }

  @Test
  void aRunPinsItsPlanVersionAtStart() {
    final Plan v1 =
        publish(
            plan(
                1,
                RunBounds.DEFAULT,
                io.reliabilityai.gateway.dataplane.agent.api.RestartPolicy.STRICT,
                model("a")));
    runtime.start(RunId.of("r-1"), v1, security("chat"), v1.bounds(), 1_000_000L);

    // Publishing v2 while the run is in flight must not reach it.
    publish(
        plan(
            2,
            RunBounds.DEFAULT,
            io.reliabilityai.gateway.dataplane.agent.api.RestartPolicy.STRICT,
            model("a"),
            model("b")));

    assertThat(runtime.lookup(RunId.of("r-1")).orElseThrow().plan().version()).isEqualTo(1);
    assertThat(runtime.lookup(RunId.of("r-1")).orElseThrow().plan().size()).isEqualTo(1);
  }

  @Test
  void aRunResumesAgainstItsPinnedVersionEvenAfterANewerOneIsPublished() {
    final Plan v1 =
        publish(
            plan(
                1,
                RunBounds.DEFAULT,
                io.reliabilityai.gateway.dataplane.agent.api.RestartPolicy.STRICT,
                model("a")));
    runtime.start(RunId.of("r-1"), v1, security("chat"), v1.bounds(), 1_000_000L);
    publish(
        plan(
            2,
            RunBounds.DEFAULT,
            io.reliabilityai.gateway.dataplane.agent.api.RestartPolicy.STRICT,
            model("a"),
            model("b"),
            model("c")));

    RunExecutor.Advance last = null;
    for (int i = 0; i < 10 && !(last instanceof RunExecutor.Advance.Terminated); i++) {
      last = executor.advance(RunId.of("r-1"));
    }
    // One step, because v1 has one step. v2's extra steps never touch this run.
    assertThat(sessions.requests()).hasSize(1);
  }

  @Test
  void publishingCannotOverwriteAnExistingPlanVersion() {
    final Plan first =
        plan(
            1,
            RunBounds.DEFAULT,
            io.reliabilityai.gateway.dataplane.agent.api.RestartPolicy.STRICT,
            model("a"));
    final Plan impostor =
        plan(
            1,
            RunBounds.DEFAULT,
            io.reliabilityai.gateway.dataplane.agent.api.RestartPolicy.STRICT,
            model("z"));
    assertThat(plans.publish(first)).isTrue();
    assertThat(plans.publish(impostor)).isFalse();
    assertThat(plans.resolve(first.id(), 1).orElseThrow().stepAt(0).name()).isEqualTo("a");
  }

  @Test
  void lookupIsEmptyForARunWhosePlanVersionHasBeenCollected() {
    final Plan plan = publish(plan(model("a")));
    runtime.start(RunId.of("r-1"), plan, security("chat"), plan.bounds(), 1_000_000L);
    plans.collect(plan.id(), plan.version());
    assertThat(runtime.lookup(RunId.of("r-1"))).isEmpty();
  }

  @Test
  void theRuntimeExposesTheSameTransitionTableTheExecutorEnforces() {
    assertThat(AgentRuntime.permits(RunState.QUEUED, RunState.RUNNING)).isTrue();
    assertThat(AgentRuntime.permits(RunState.COMPLETED, RunState.RUNNING)).isFalse();
  }

  @Test
  void theRuntimeReadsTimeOnlyThroughTheInjectedClock() {
    clock.set(io.reliabilityai.gateway.dataplane.agent.AgentFixtures.T0.plusSeconds(500));
    assertThat(runtime.now())
        .isEqualTo(io.reliabilityai.gateway.dataplane.agent.AgentFixtures.T0.plusSeconds(500));
  }

  @Test
  void anUnreachableStoreRefusesToStartARunRatherThanPretendingItStarted() {
    final Plan plan = publish(plan(model("a")));
    runs.setAvailable(false);
    assertThat(runtime.start(RunId.of("r-1"), plan, security("chat"), plan.bounds(), 1L))
        .isInstanceOf(AgentRuntime.StartOutcome.Refused.class);
  }

  // ---- RunScheduler
  // ------------------------------------------------------------------------------

  @Test
  void aPassClaimsAndAdvancesTheRunsItFinds() {
    final Plan plan = publish(plan(model("a"), model("b")));
    for (int i = 0; i < 3; i++) {
      runtime.start(RunId.of("r-" + i), plan, security("chat"), plan.bounds(), 1_000_000L);
    }

    final RunScheduler.Pass pass = schedulerNamed("node-a").pass(TENANT);
    assertThat(pass.claimed()).isEqualTo(3);
    assertThat(pass.advanced()).isEqualTo(3);
    assertThat(pass.productive()).isTrue();
  }

  @Test
  void aPassAdvancesEachRunByExactlyOneStepSoNoRunMonopolisesTheNode() {
    final Plan plan = publish(plan(model("a"), model("b"), model("c")));
    runtime.start(RunId.of("r-1"), plan, security("chat"), plan.bounds(), 1_000_000L);

    schedulerNamed("node-a").pass(TENANT);
    assertThat(sessions.requests()).hasSize(1);
  }

  @Test
  void theLeaseIsReleasedAfterEveryPassSoAnotherNodeCanTakeOver() {
    final Plan plan = publish(plan(model("a"), model("b")));
    runtime.start(RunId.of("r-1"), plan, security("chat"), plan.bounds(), 1_000_000L);

    schedulerNamed("node-a").pass(TENANT);
    assertThat(schedulerNamed("node-b").pass(TENANT).claimed()).isEqualTo(1);
  }

  @Test
  void aRunHeldByAnotherNodeIsNotOfferedTwice() {
    final Plan plan = publish(plan(model("a"), model("b")));
    runtime.start(RunId.of("r-1"), plan, security("chat"), plan.bounds(), 1_000_000L);
    runs.claim(RunId.of("r-1"), "someone-else", clock.now(), clock.now().plusSeconds(60));

    assertThat(schedulerNamed("node-a").pass(TENANT).claimed()).isZero();
  }

  @Test
  void aPassOverATenantWithNothingToDoIsUnproductiveRatherThanAnError() {
    assertThat(schedulerNamed("node-a").pass(TENANT).productive()).isFalse();
  }

  @Test
  void roundRobinServesEveryTenantSoOneCannotStarveAnother() {
    final Plan plan = publish(plan(model("a"), model("b")));
    for (int i = 0; i < 5; i++) {
      runtime.start(RunId.of("mine-" + i), plan, security("chat"), plan.bounds(), 1_000_000L);
    }
    runtime.start(
        RunId.of("theirs"),
        plan,
        securityIn(OTHER_TENANT, "c2", "chat"),
        plan.bounds(),
        1_000_000L);

    final List<RunScheduler.Pass> passes =
        schedulerNamed("node-a").roundRobin(List.of(TENANT, OTHER_TENANT));
    assertThat(passes).hasSize(2);
    assertThat(passes.get(0).advanced()).isEqualTo(5);
    assertThat(passes.get(1).advanced()).isEqualTo(1);
  }

  @Test
  void aBatchSizeCapsHowMuchOnePassTakesOn() {
    final Plan plan = publish(plan(model("a"), model("b")));
    for (int i = 0; i < 25; i++) {
      runtime.start(RunId.of("r-" + i), plan, security("chat"), plan.bounds(), 1_000_000L);
    }
    final RunScheduler small =
        new RunScheduler(runs, executor, clock, "node-a", Duration.ofSeconds(30), 4);
    assertThat(small.pass(TENANT).claimed()).isEqualTo(4);
  }

  @Test
  void drainingDrivesOneRunToCompletion() {
    final Plan plan = publish(plan(model("a"), model("b"), model("c")));
    runtime.start(RunId.of("r-1"), plan, security("chat"), plan.bounds(), 1_000_000L);

    final RunExecutor.Advance last = schedulerNamed("node-a").drain(RunId.of("r-1"), 20);
    assertThat(last).isInstanceOf(RunExecutor.Advance.Terminated.class);
    assertThat(sessions.requests()).hasSize(3);
  }

  @Test
  void drainingStopsAtAParkedRunRatherThanSpinning() {
    final Plan plan = publish(plan(new Step.Wait("pause", Duration.ofHours(6)), model("after")));
    runtime.start(RunId.of("r-1"), plan, security("chat"), plan.bounds(), 1_000_000L);

    assertThat(schedulerNamed("node-a").drain(RunId.of("r-1"), 20))
        .isInstanceOf(RunExecutor.Advance.Parked.class);
  }

  @Test
  void aDrainIsHardCappedSoABugCannotSpinForever() {
    final Plan plan = publish(plan(model("a"), model("b"), model("c")));
    runtime.start(RunId.of("r-1"), plan, security("chat"), plan.bounds(), 1_000_000L);
    // One iteration cannot finish a three-step plan, so the cap is what stops the loop.
    assertThat(schedulerNamed("node-a").drain(RunId.of("r-1"), 1))
        .isInstanceOf(RunExecutor.Advance.Advanced.class);
  }

  @Test
  void aTerminatedRunIsNotCountedAsAdvancedWork() {
    final Plan plan = publish(plan(model("a")));
    runtime.start(RunId.of("r-1"), plan, security("chat"), plan.bounds(), 1_000_000L);
    final RunScheduler scheduler = schedulerNamed("node-a");
    scheduler.pass(TENANT);

    final RunScheduler.Pass second = scheduler.pass(TENANT);
    assertThat(second.terminated()).isEqualTo(1);
    assertThat(second.advanced()).isZero();
  }

  @Test
  void aPassOverAnUnreachableStoreDoesNotSilentlyReportProgress() {
    final Plan plan = publish(plan(model("a")));
    runtime.start(RunId.of("r-1"), plan, security("chat"), plan.bounds(), 1_000_000L);
    runs.setAvailable(false);

    assertThatThrownBy(() -> schedulerNamed("node-a").pass(TENANT))
        .isInstanceOf(
            io.reliabilityai.gateway.dataplane.agent.api.RunStoreUnavailableException.class);
  }

  @Test
  void aSchedulerWithANonPositiveLeaseIsUnrepresentable() {
    assertThatThrownBy(() -> new RunScheduler(runs, executor, clock, "n", Duration.ZERO, 1))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void aSchedulerWithAZeroBatchIsUnrepresentable() {
    assertThatThrownBy(() -> new RunScheduler(runs, executor, clock, "n", Duration.ofSeconds(1), 0))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void theSchedulerReportsItsOwnNodeIdentity() {
    assertThat(schedulerNamed("node-xyz").nodeId()).isEqualTo("node-xyz");
  }

  @Test
  void aCancelledRunIsSweptToTerminationByTheNextPass() {
    final Plan plan = publish(plan(model("a"), model("b"), model("c")));
    runtime.start(RunId.of("r-1"), plan, security("chat"), plan.bounds(), 1_000_000L);
    final RunScheduler scheduler = schedulerNamed("node-a");
    scheduler.pass(TENANT);
    runtime.cancel(RunId.of("r-1"), CancellationCause.OPERATOR, "stop");

    assertThat(scheduler.pass(TENANT).terminated()).isEqualTo(1);
    assertThat(runtime.snapshot(RunId.of("r-1")).orElseThrow().terminalReason())
        .contains(TerminalReason.CANCELLED_CALLER);
  }

  @Test
  void theAuditPortSeesEveryDurableEventInOrder() {
    final List<String> seen = new ArrayList<>();
    final RunAuditPort recording = (runId, offset, event) -> seen.add(event.tag());
    final RunExecutor audited =
        new RunExecutor(
            runs,
            plans,
            new ToolSessionRunner(sessions, new FakeTools()),
            clock,
            metrics,
            recording);
    final AgentRuntime auditedRuntime =
        new AgentRuntime(runs, plans, audited, clock, metrics, recording);
    final Plan plan = publish(plan(model("a")));
    auditedRuntime.start(RunId.of("r-1"), plan, security("chat"), plan.bounds(), 1_000_000L);
    audited.advance(RunId.of("r-1"));
    audited.advance(RunId.of("r-1"));

    assertThat(seen).startsWith("run.created", "run.queued", "step.scheduled");
    assertThat(seen).contains("step.completed", "supervision.applied", "run.terminated");
  }

  @Test
  void aDroppedAuditSinkNeverAffectsTheRun() {
    // OBC-2: observability is best-effort, and an outage there must not become a run outage.
    final RunAuditPort exploding =
        (runId, offset, event) -> {
          throw new IllegalStateException("sink down");
        };
    final RunExecutor audited =
        new RunExecutor(
            runs,
            plans,
            new ToolSessionRunner(sessions, new FakeTools()),
            clock,
            metrics,
            exploding);
    final Plan plan = publish(plan(model("a")));
    new AgentRuntime(runs, plans, audited, clock, metrics, RunAuditPort.NOOP)
        .start(RunId.of("r-1"), plan, security("chat"), plan.bounds(), 1_000_000L);

    RunExecutor.Advance last = null;
    for (int i = 0; i < 5 && !(last instanceof RunExecutor.Advance.Terminated); i++) {
      last = audited.advance(RunId.of("r-1"));
    }

    assertThat(last).isInstanceOf(RunExecutor.Advance.Terminated.class);
    assertThat(((RunExecutor.Advance.Terminated) last).reason())
        .isEqualTo(TerminalReason.COMPLETED_SUCCESS);
    assertThat(sessions.requests()).hasSize(1);
  }

  @Test
  void aFailingAuditSinkIsCountedRatherThanIgnoredEntirely() {
    // Swallowed is not the same as unnoticed: a sink that never delivers should be visible to an
    // operator even though it cannot stop a run.
    final RunAuditPort exploding =
        (runId, offset, event) -> {
          throw new IllegalStateException("sink down");
        };
    final RunExecutor audited =
        new RunExecutor(
            runs,
            plans,
            new ToolSessionRunner(sessions, new FakeTools()),
            clock,
            metrics,
            exploding);
    final Plan plan = publish(plan(model("a")));
    new AgentRuntime(runs, plans, audited, clock, metrics, RunAuditPort.NOOP)
        .start(RunId.of("r-1"), plan, security("chat"), plan.bounds(), 1_000_000L);
    audited.advance(RunId.of("r-1"));

    assertThat(metrics.counter("store.unavailable")).isPositive();
  }
}

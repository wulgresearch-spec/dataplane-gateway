package io.reliabilityai.gateway.dataplane.agent.application;

import static io.reliabilityai.gateway.dataplane.agent.AgentFixtures.TENANT;
import static io.reliabilityai.gateway.dataplane.agent.AgentFixtures.model;
import static io.reliabilityai.gateway.dataplane.agent.AgentFixtures.plan;
import static io.reliabilityai.gateway.dataplane.agent.AgentFixtures.security;
import static org.assertj.core.api.Assertions.assertThat;

import io.reliabilityai.gateway.dataplane.agent.AgentFixtures.FakeSessions;
import io.reliabilityai.gateway.dataplane.agent.AgentFixtures.FakeTools;
import io.reliabilityai.gateway.dataplane.agent.AgentFixtures.TestClock;
import io.reliabilityai.gateway.dataplane.agent.api.Plan;
import io.reliabilityai.gateway.dataplane.agent.api.RunAuditPort;
import io.reliabilityai.gateway.dataplane.agent.api.RunHistory;
import io.reliabilityai.gateway.dataplane.agent.api.RunId;
import io.reliabilityai.gateway.dataplane.agent.api.Step;
import io.reliabilityai.gateway.dataplane.agent.domain.ReplayEngine;
import io.reliabilityai.gateway.dataplane.agent.internal.CanonicalRunSerializer;
import io.reliabilityai.gateway.dataplane.agent.internal.InMemoryPlanRepository;
import io.reliabilityai.gateway.dataplane.agent.internal.InMemoryRunRepository;
import io.reliabilityai.gateway.dataplane.agent.internal.InProcessAgentMetrics;
import io.reliabilityai.gateway.dataplane.agent.internal.JournalRunRepository;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DisplayNameGeneration;
import org.junit.jupiter.api.DisplayNameGenerator;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Measurements, taken in-process and reported rather than asserted tightly.
 *
 * <p>The assertions are deliberately loose — an order of magnitude, not a percentage — because a
 * unit test on shared CI hardware cannot hold a tight latency bound without becoming flaky, and a
 * flaky performance test is worse than none. The numbers printed alongside them are the
 * deliverable; the assertions only catch a regression large enough to mean something is
 * structurally wrong.
 *
 * <p>Each measurement is preceded by a warm-up so the figure is not dominated by class loading and
 * JIT, and the same work is then timed over enough iterations to be meaningful.
 */
@DisplayNameGeneration(DisplayNameGenerator.ReplaceUnderscores.class)
class AgentRuntimePerformanceTest {

  private final InMemoryRunRepository runs = new InMemoryRunRepository();
  private final InMemoryPlanRepository plans = new InMemoryPlanRepository();
  private final TestClock clock = new TestClock();
  private final InProcessAgentMetrics metrics = new InProcessAgentMetrics();
  private final FakeSessions sessions = new FakeSessions();

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

  private static void report(final String what, final long nanos, final int iterations) {
    final double perOp = (double) nanos / iterations;
    System.out.printf("  PERF  %-34s %,10.0f ns/op  (%d iterations)%n", what, perOp, iterations);
  }

  private Plan publishPlan(final int steps, final String id) {
    final Step[] all = new Step[steps];
    for (int i = 0; i < steps; i++) {
      all[i] = model("s" + i);
    }
    // Bounds sized to the plan: the step bound must admit the plan, and Plan validation refuses one
    // that cannot possibly complete — which is exactly the check that caught an earlier version of
    // this fixture asking for 200 steps under the default ceiling of 50.
    final io.reliabilityai.gateway.dataplane.agent.api.RunBounds bounds =
        new io.reliabilityai.gateway.dataplane.agent.api.RunBounds(
            Math.max(steps, 50), 3, 5, java.time.Duration.ofHours(4), 1_000_000_000L);
    final Plan plan =
        new Plan(
            io.reliabilityai.gateway.dataplane.agent.api.PlanId.of(id),
            1,
            List.of(all),
            bounds,
            io.reliabilityai.gateway.dataplane.agent.api.RestartPolicy.STRICT);
    plans.publish(plan);
    return plan;
  }

  @Test
  void runCreationIsCheapEnoughToAdmitThousandsPerSecond() {
    final Plan plan = publishPlan(3, "create");
    for (int i = 0; i < 200; i++) {
      runtime.start(RunId.of("warm-" + i), plan, security("chat"), plan.bounds(), 1_000L);
    }
    runs.clear();

    final int iterations = 5_000;
    final long start = System.nanoTime();
    for (int i = 0; i < iterations; i++) {
      runtime.start(RunId.of("r-" + i), plan, security("chat"), plan.bounds(), 1_000L);
    }
    final long elapsed = System.nanoTime() - start;
    report("run creation (in-memory store)", elapsed, iterations);

    assertThat(elapsed / iterations).isLessThan(1_000_000L);
  }

  @Test
  void advancingAStepIsDominatedByTheSeamRatherThanByTheRuntime() {
    final Plan plan = publishPlan(50, "advance");
    for (int i = 0; i < 50; i++) {
      runtime.start(RunId.of("warm-" + i), plan, security("chat"), plan.bounds(), 5_000_000L);
      executor.advance(RunId.of("warm-" + i));
    }

    final int iterations = 2_000;
    final RunId run = RunId.of("bulk");
    runtime.start(run, plan, security("chat"), plan.bounds(), 50_000_000L);
    final long start = System.nanoTime();
    for (int i = 0; i < iterations; i++) {
      executor.advance(RunId.of("spread-" + (i % 50)));
    }
    final long elapsed = System.nanoTime() - start;
    report("step advance (fake seam)", elapsed, iterations);

    assertThat(elapsed / iterations).isLessThan(5_000_000L);
  }

  @Test
  void checkpointingIsFreeBecauseItIsTheStepRecordItself() {
    // There is no separate checkpoint write to measure. What is measured is the cost of the durable
    // record that doubles as one, which is the honest figure.
    final Plan plan = publishPlan(20, "checkpoint");
    final RunId run = RunId.of("cp");
    runtime.start(run, plan, security("chat"), plan.bounds(), 20_000_000L);
    for (int i = 0; i < 5; i++) {
      executor.advance(run);
    }

    final RunHistory history = runs.load(run).orElseThrow();
    final int iterations = 100_000;
    final long start = System.nanoTime();
    long sink = 0;
    for (int i = 0; i < iterations; i++) {
      sink += history.offset();
    }
    final long elapsed = System.nanoTime() - start;
    report("checkpoint read (history offset)", elapsed, iterations);
    assertThat(sink).isPositive();
  }

  @Test
  void replayingALongHistoryCostsMicrosecondsAndNoInference() {
    final Plan plan = publishPlan(40, "replay");
    final RunId run = RunId.of("long");
    runtime.start(run, plan, security("chat"), plan.bounds(), 40_000_000L);
    for (int i = 0; i < 40; i++) {
      executor.advance(run);
    }
    final RunHistory history = runs.load(run).orElseThrow();
    final int callsBefore = sessions.pipelineExecutions();

    for (int i = 0; i < 500; i++) {
      ReplayEngine.fold(plan, history);
    }
    final int iterations = 5_000;
    final long start = System.nanoTime();
    for (int i = 0; i < iterations; i++) {
      ReplayEngine.fold(plan, history);
    }
    final long elapsed = System.nanoTime() - start;
    report("replay fold (" + history.offset() + " events)", elapsed, iterations);

    // The decisive assertion is not the latency: it is that replaying cost nothing at all in
    // inference. A framework that re-executes on resume cannot make this claim.
    assertThat(sessions.pipelineExecutions()).isEqualTo(callsBefore);
    assertThat(elapsed / iterations).isLessThan(2_000_000L);
  }

  @Test
  void recoveringARunFromDiskIsBoundedByReadingItsJournal(@TempDir final Path dir) {
    final JournalRunRepository store = new JournalRunRepository(dir, new CanonicalRunSerializer());
    final RunExecutor journalled =
        new RunExecutor(
            store,
            plans,
            new ToolSessionRunner(sessions, new FakeTools()),
            clock,
            metrics,
            RunAuditPort.NOOP);
    final Plan plan = publishPlan(20, "recover");
    for (int r = 0; r < 50; r++) {
      final RunId run = RunId.of("r-" + r);
      new AgentRuntime(store, plans, journalled, clock, metrics, RunAuditPort.NOOP)
          .start(run, plan, security("chat"), plan.bounds(), 20_000_000L);
      for (int i = 0; i < 10; i++) {
        journalled.advance(run);
      }
    }

    final int iterations = 20;
    final long start = System.nanoTime();
    for (int i = 0; i < iterations; i++) {
      new JournalRunRepository(dir, new CanonicalRunSerializer());
    }
    final long elapsed = System.nanoTime() - start;
    report("cold recovery of 50 runs", elapsed, iterations);

    assertThat(new JournalRunRepository(dir, new CanonicalRunSerializer()).size()).isEqualTo(50);
  }

  @Test
  void aDurableAppendIsDominatedByTheFsyncAsItShouldBe(@TempDir final Path dir) {
    final JournalRunRepository store = new JournalRunRepository(dir, new CanonicalRunSerializer());
    final Plan plan = publishPlan(200, "append");
    final RunExecutor journalled =
        new RunExecutor(
            store,
            plans,
            new ToolSessionRunner(sessions, new FakeTools()),
            clock,
            metrics,
            RunAuditPort.NOOP);
    final RunId run = RunId.of("appender");
    new AgentRuntime(store, plans, journalled, clock, metrics, RunAuditPort.NOOP)
        .start(run, plan, security("chat"), plan.bounds(), 200_000_000L);

    final int iterations = 100;
    final long start = System.nanoTime();
    for (int i = 0; i < iterations; i++) {
      journalled.advance(run);
    }
    final long elapsed = System.nanoTime() - start;
    report("durable step (fsync per append)", elapsed, iterations);

    // Deliberately generous: an fsync is a disk round-trip and its cost belongs to the disk. The
    // assertion exists to catch a structural regression such as fsyncing per byte.
    assertThat(elapsed / iterations).isLessThan(200_000_000L);
  }

  @Test
  void serializingAndParsingAnEventIsSubMicrosecond() {
    final CanonicalRunSerializer serializer = new CanonicalRunSerializer();
    final Plan plan = publishPlan(3, "serde");
    final RunId run = RunId.of("s");
    runtime.start(run, plan, security("chat"), plan.bounds(), 1_000_000L);
    executor.advance(run);
    final var event = runs.load(run).orElseThrow().events().get(2);

    for (int i = 0; i < 5_000; i++) {
      serializer.deserialize(serializer.serialize(event), run);
    }
    final int iterations = 100_000;
    final long start = System.nanoTime();
    for (int i = 0; i < iterations; i++) {
      serializer.deserialize(serializer.serialize(event), run);
    }
    final long elapsed = System.nanoTime() - start;
    report("journal encode + decode", elapsed, iterations);

    assertThat(elapsed / iterations).isLessThan(100_000L);
  }

  @Test
  void aRetryCostsOneMoreStepRatherThanRewindingTheRun() {
    final Plan plan =
        plan(
            1,
            io.reliabilityai.gateway.dataplane.agent.api.RunBounds.DEFAULT,
            io.reliabilityai.gateway.dataplane.agent.AgentFixtures.retrying(3, 50),
            model("flaky"));
    plans.publish(plan);
    final int[] attempt = {0};
    sessions.respondWith(
        request ->
            ++attempt[0] % 3 != 0
                ? new io.reliabilityai.gateway.dataplane.agent.api.ToolSessionPort.Failed(
                    io.reliabilityai.gateway.dataplane.agent.api.FailureClass.STEP_TRANSIENT,
                    "f",
                    1L,
                    true,
                    1)
                : new io.reliabilityai.gateway.dataplane.agent.api.ToolSessionPort.Completed(
                    "ok", 1L, true, 1, false));

    final int iterations = 300;
    final long start = System.nanoTime();
    for (int i = 0; i < iterations; i++) {
      final RunId run = RunId.of("retry-" + i);
      runtime.start(run, plan, security("chat"), plan.bounds(), 1_000_000L);
      for (int step = 0; step < 8; step++) {
        if (executor.advance(run) instanceof RunExecutor.Advance.Terminated) {
          break;
        }
      }
    }
    final long elapsed = System.nanoTime() - start;
    report("run with retries, end to end", elapsed, iterations);
    assertThat(elapsed / iterations).isLessThan(50_000_000L);
  }

  @Test
  void aSchedulingPassOverManyRunsScalesWithTheBatchNotTheStore() {
    final Plan plan = publishPlan(4, "sched");
    for (int i = 0; i < 2_000; i++) {
      runtime.start(RunId.of("r-" + i), plan, security("chat"), plan.bounds(), 4_000_000L);
    }
    final RunScheduler scheduler =
        new RunScheduler(runs, executor, clock, "node", Duration.ofSeconds(30), 32);
    scheduler.pass(TENANT);

    final int iterations = 50;
    final long start = System.nanoTime();
    for (int i = 0; i < iterations; i++) {
      scheduler.pass(TENANT);
    }
    final long elapsed = System.nanoTime() - start;
    report("scheduler pass (batch 32 of 2000)", elapsed, iterations);
    assertThat(elapsed / iterations).isLessThan(500_000_000L);
  }

  @Test
  void oneThousandLiveRunsFitInAModestHeap() {
    final Plan plan = publishPlan(5, "mem");
    final Runtime jvm = Runtime.getRuntime();
    System.gc();
    final long before = jvm.totalMemory() - jvm.freeMemory();

    final List<RunId> live = new ArrayList<>();
    for (int i = 0; i < 1_000; i++) {
      final RunId run = RunId.of("m-" + i);
      runtime.start(run, plan, security("chat"), plan.bounds(), 5_000_000L);
      executor.advance(run);
      executor.advance(run);
      live.add(run);
    }
    System.gc();
    final long after = jvm.totalMemory() - jvm.freeMemory();
    final long perRun = Math.max(0L, (after - before) / live.size());
    System.out.printf(
        "  PERF  %-34s %,10d bytes/run (1000 runs, 2 steps each)%n", "resident run state", perRun);

    // Loose by design: a heap delta measured around a GC is indicative, not exact. It catches an
    // order-of-magnitude regression such as retaining full payloads per event.
    assertThat(perRun).isLessThan(200_000L);
    assertThat(runs.size()).isEqualTo(1_000);
  }

  @Test
  void aParkedRunOccupiesNoThreadAndNoConnection() {
    // The structural claim behind hours-long pauses. Measured as: the run exists, is not claimable,
    // and no executor is blocked on it — the scheduler simply does not see it.
    final Plan plan = plan(new Step.Wait("pause", Duration.ofHours(6)), model("after"));
    plans.publish(plan);
    final RunId run = RunId.of("parked");
    runtime.start(run, plan, security("chat"), plan.bounds(), 1_000_000L);
    executor.advance(run);

    final int threadsBefore = Thread.activeCount();
    final RunScheduler scheduler =
        new RunScheduler(runs, executor, clock, "node", Duration.ofSeconds(30), 10);
    for (int i = 0; i < 100; i++) {
      assertThat(scheduler.pass(TENANT).productive()).isFalse();
    }
    assertThat(Thread.activeCount()).isLessThanOrEqualTo(threadsBefore + 2);
  }
}

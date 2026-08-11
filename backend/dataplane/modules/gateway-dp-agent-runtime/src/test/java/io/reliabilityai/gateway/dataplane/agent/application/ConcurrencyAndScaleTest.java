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
import io.reliabilityai.gateway.dataplane.agent.api.RunEvent;
import io.reliabilityai.gateway.dataplane.agent.api.RunHistory;
import io.reliabilityai.gateway.dataplane.agent.api.RunId;
import io.reliabilityai.gateway.dataplane.agent.api.StepId;
import io.reliabilityai.gateway.dataplane.agent.api.StepKind;
import io.reliabilityai.gateway.dataplane.agent.internal.InMemoryPlanRepository;
import io.reliabilityai.gateway.dataplane.agent.internal.InMemoryRunRepository;
import io.reliabilityai.gateway.dataplane.agent.internal.InProcessAgentMetrics;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.DisplayNameGeneration;
import org.junit.jupiter.api.DisplayNameGenerator;
import org.junit.jupiter.api.Test;

/**
 * Many runs, many nodes, one store.
 *
 * <p>The property that matters most here is that safety does not rest on the lease. Two executors
 * can believe they hold the same run — clock skew, a partition, a lease that expired mid-step — and
 * only one of them can win the conditional append. These tests make several nodes race deliberately
 * and assert that no step is ever executed twice.
 */
@DisplayNameGeneration(DisplayNameGenerator.ReplaceUnderscores.class)
class ConcurrencyAndScaleTest {

  private final InMemoryRunRepository runs = new InMemoryRunRepository();
  private final InMemoryPlanRepository plans = new InMemoryPlanRepository();
  private final TestClock clock = new TestClock();
  private final InProcessAgentMetrics metrics = new InProcessAgentMetrics();

  private RunExecutor newExecutor(final FakeSessions sessions) {
    return new RunExecutor(
        runs,
        plans,
        new ToolSessionRunner(sessions, new FakeTools()),
        clock,
        metrics,
        RunAuditPort.NOOP);
  }

  private AgentRuntime newRuntime(final RunExecutor executor) {
    return new AgentRuntime(runs, plans, executor, clock, metrics, RunAuditPort.NOOP);
  }

  @Test
  void oneThousandConcurrentRunsAllCompleteExactlyOnce() throws Exception {
    final Plan plan = plan(model("a"), model("b"), model("c"));
    plans.publish(plan);
    final FakeSessions sessions = new FakeSessions();
    final RunExecutor executor = newExecutor(sessions);
    final AgentRuntime runtime = newRuntime(executor);

    final int runCount = 1_000;
    for (int i = 0; i < runCount; i++) {
      runtime.start(RunId.of("r-" + i), plan, security("chat"), plan.bounds(), 1_000_000L);
    }

    // Eight nodes pulling from one store, each advancing whatever it can claim.
    final int nodes = 8;
    final ExecutorService pool = Executors.newFixedThreadPool(nodes);
    final CountDownLatch start = new CountDownLatch(1);
    final List<java.util.concurrent.Future<?>> workers = new ArrayList<>();
    for (int n = 0; n < nodes; n++) {
      final RunScheduler scheduler =
          new RunScheduler(runs, executor, clock, "node-" + n, Duration.ofSeconds(30), 64);
      workers.add(
          pool.submit(
              () -> {
                start.await();
                for (int pass = 0; pass < 200; pass++) {
                  if (!scheduler.pass(TENANT).productive()
                      && runs.unfinished(TENANT, 1).isEmpty()) {
                    return null;
                  }
                }
                return null;
              }));
    }
    start.countDown();
    for (final var worker : workers) {
      worker.get(120, TimeUnit.SECONDS);
    }
    pool.shutdownNow();

    assertThat(runs.unfinished(TENANT, runCount)).isEmpty();
    for (int i = 0; i < runCount; i++) {
      final RunHistory history = runs.load(RunId.of("r-" + i)).orElseThrow();
      assertThat(history.terminated()).as("run %d terminated", i).isTrue();
      assertThat(countOf(history, RunEvent.StepCompleted.class))
          .as("run %d completed steps", i)
          .isEqualTo(3);
    }
    // Three steps per run and not one more: no step was executed twice despite eight racing nodes.
    assertThat(sessions.pipelineExecutions()).isEqualTo(runCount * 3);
  }

  @Test
  void twoNodesRacingOnOneRunNeverExecuteTheSameStepTwice() throws Exception {
    final Plan plan = plan(model("a"), model("b"), model("c"), model("d"));
    plans.publish(plan);
    final FakeSessions sessions = new FakeSessions();
    final RunExecutor executor = newExecutor(sessions);
    final RunId run = RunId.of("contested");
    newRuntime(executor).start(run, plan, security("chat"), plan.bounds(), 1_000_000L);

    // Both nodes are handed the run directly, bypassing the lease entirely, so the only thing
    // between
    // them and a double execution is the conditional append.
    final int threads = 16;
    final ExecutorService pool = Executors.newFixedThreadPool(threads);
    final CountDownLatch start = new CountDownLatch(1);
    final AtomicInteger contended = new AtomicInteger();
    final List<java.util.concurrent.Future<?>> workers = new ArrayList<>();
    for (int t = 0; t < threads; t++) {
      workers.add(
          pool.submit(
              () -> {
                start.await();
                for (int i = 0; i < 20; i++) {
                  if (executor.advance(run) instanceof RunExecutor.Advance.Contended) {
                    contended.incrementAndGet();
                  }
                }
                return null;
              }));
    }
    start.countDown();
    for (final var worker : workers) {
      worker.get(60, TimeUnit.SECONDS);
    }
    pool.shutdownNow();

    final RunHistory history = runs.load(run).orElseThrow();
    assertThat(history.terminated()).isTrue();
    assertThat(countOf(history, RunEvent.StepCompleted.class)).isEqualTo(4);
    assertThat(countOf(history, RunEvent.RunTerminated.class)).isEqualTo(1);
  }

  @Test
  void aLosingWriterAbandonsItsStepRatherThanOverwritingTheWinners() {
    final Plan plan = plan(model("a"), model("b"));
    plans.publish(plan);
    final RunExecutor executor = newExecutor(new FakeSessions());
    final RunId run = RunId.of("r-1");
    newRuntime(executor).start(run, plan, security("chat"), plan.bounds(), 1_000_000L);

    final long staleOffset = runs.load(run).orElseThrow().offset();
    executor.advance(run);

    // A node that was mid-decision when another finished tries to append at the offset it
    // remembers.
    final var refused =
        runs.append(
            run,
            staleOffset,
            new RunEvent.StepScheduled(
                StepId.of(run, 0), 0, "a", StepKind.PIPELINE, 1, "stale", clock.now()));
    assertThat(refused)
        .isInstanceOf(
            io.reliabilityai.gateway.dataplane.agent.api.RunRepository.AppendResult.Conflict.class);
  }

  @Test
  void aRunIsOnlyEverClaimedByOneNodeAtATime() throws Exception {
    final Plan plan = plan(model("a"), model("b"));
    plans.publish(plan);
    final RunId run = RunId.of("r-1");
    newRuntime(newExecutor(new FakeSessions()))
        .start(run, plan, security("chat"), plan.bounds(), 1_000_000L);

    final int threads = 32;
    final ExecutorService pool = Executors.newFixedThreadPool(threads);
    final CountDownLatch start = new CountDownLatch(1);
    final AtomicInteger winners = new AtomicInteger();
    final List<java.util.concurrent.Future<?>> workers = new ArrayList<>();
    for (int t = 0; t < threads; t++) {
      final String node = "node-" + t;
      workers.add(
          pool.submit(
              () -> {
                start.await();
                if (runs.claim(run, node, clock.now(), clock.now().plusSeconds(60))) {
                  winners.incrementAndGet();
                }
                return null;
              }));
    }
    start.countDown();
    for (final var worker : workers) {
      worker.get(30, TimeUnit.SECONDS);
    }
    pool.shutdownNow();

    assertThat(winners.get()).isEqualTo(1);
  }

  @Test
  void concurrentCreationOfTheSameRunIdYieldsExactlyOneRun() throws Exception {
    final Plan plan = plan(model("a"));
    plans.publish(plan);
    final AgentRuntime runtime = newRuntime(newExecutor(new FakeSessions()));
    final RunId run = RunId.of("r-1");

    final int threads = 16;
    final ExecutorService pool = Executors.newFixedThreadPool(threads);
    final CountDownLatch start = new CountDownLatch(1);
    final AtomicInteger started = new AtomicInteger();
    final List<java.util.concurrent.Future<?>> workers = new ArrayList<>();
    for (int t = 0; t < threads; t++) {
      workers.add(
          pool.submit(
              () -> {
                start.await();
                if (runtime.start(run, plan, security("chat"), plan.bounds(), 1_000L)
                    instanceof AgentRuntime.StartOutcome.Started) {
                  started.incrementAndGet();
                }
                return null;
              }));
    }
    start.countDown();
    for (final var worker : workers) {
      worker.get(30, TimeUnit.SECONDS);
    }
    pool.shutdownNow();

    assertThat(started.get()).isEqualTo(1);
    assertThat(runs.size()).isEqualTo(1);
  }

  @Test
  void manyRunsAcrossManyTenantsStayInTheirOwnPartitions() {
    final Plan plan = plan(model("a"));
    plans.publish(plan);
    final AgentRuntime runtime = newRuntime(newExecutor(new FakeSessions()));

    final List<io.reliabilityai.gateway.canonical.identity.TenantScope> tenants = new ArrayList<>();
    for (int t = 0; t < 10; t++) {
      tenants.add(io.reliabilityai.gateway.canonical.identity.TenantScope.of("org", "t-" + t));
    }
    for (int t = 0; t < tenants.size(); t++) {
      for (int i = 0; i < 20; i++) {
        runtime.start(
            RunId.of("t" + t + "-r" + i),
            plan,
            new io.reliabilityai.gateway.dataplane.agent.api.RunSecurityContext(
                new io.reliabilityai.gateway.canonical.identity.PrincipalId("p"),
                tenants.get(t),
                new io.reliabilityai.gateway.canonical.identity.CorrelationId("c-" + t + "-" + i),
                Set.of("chat")),
            plan.bounds(),
            1_000L);
      }
    }

    for (int t = 0; t < tenants.size(); t++) {
      assertThat(runs.unfinished(tenants.get(t), 1_000)).hasSize(20);
    }
  }

  @Test
  void aBusyNodeSimplyClaimsLessRatherThanBlockingTheOthers() throws Exception {
    final Plan plan = plan(model("a"), model("b"));
    plans.publish(plan);
    final RunExecutor executor = newExecutor(new FakeSessions());
    final AgentRuntime runtime = newRuntime(executor);
    for (int i = 0; i < 200; i++) {
      runtime.start(RunId.of("r-" + i), plan, security("chat"), plan.bounds(), 1_000_000L);
    }

    final java.util.Map<String, Integer> claimsByNode = new ConcurrentHashMap<>();
    final ExecutorService pool = Executors.newFixedThreadPool(4);
    final List<java.util.concurrent.Future<?>> workers = new ArrayList<>();
    for (int n = 0; n < 4; n++) {
      final String node = "node-" + n;
      final RunScheduler scheduler =
          new RunScheduler(runs, executor, clock, node, Duration.ofSeconds(30), 16);
      workers.add(
          pool.submit(
              () -> {
                for (int pass = 0; pass < 40; pass++) {
                  final RunScheduler.Pass result = scheduler.pass(TENANT);
                  claimsByNode.merge(node, result.claimed(), Integer::sum);
                }
                return null;
              }));
    }
    for (final var worker : workers) {
      worker.get(120, TimeUnit.SECONDS);
    }
    pool.shutdownNow();

    assertThat(runs.unfinished(TENANT, 500)).isEmpty();
    // Every node did some work; none was starved by the others holding leases.
    assertThat(claimsByNode.values()).allMatch(claims -> claims > 0);
  }

  @Test
  void aThousandRunsProduceExactlyOneTerminationEventEach() {
    final Plan plan = plan(model("a"));
    plans.publish(plan);
    final RunExecutor executor = newExecutor(new FakeSessions());
    final AgentRuntime runtime = newRuntime(executor);
    for (int i = 0; i < 1_000; i++) {
      runtime.start(RunId.of("r-" + i), plan, security("chat"), plan.bounds(), 1_000L);
    }
    final RunScheduler scheduler =
        new RunScheduler(runs, executor, clock, "node", Duration.ofSeconds(30), 1_000);
    for (int pass = 0; pass < 10 && !runs.unfinished(TENANT, 1).isEmpty(); pass++) {
      scheduler.pass(TENANT);
    }

    for (int i = 0; i < 1_000; i++) {
      assertThat(countOf(runs.load(RunId.of("r-" + i)).orElseThrow(), RunEvent.RunTerminated.class))
          .isEqualTo(1);
    }
    assertThat(metrics.counter("run.terminated")).isEqualTo(1_000L);
  }

  private static int countOf(final RunHistory history, final Class<?> type) {
    int count = 0;
    for (final RunEvent event : history.events()) {
      if (type.isInstance(event)) {
        count++;
      }
    }
    return count;
  }
}

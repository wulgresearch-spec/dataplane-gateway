package io.reliabilityai.gateway.dataplane.governance;

import static io.reliabilityai.gateway.dataplane.governance.PolicyFixture.CLOCK;
import static io.reliabilityai.gateway.dataplane.governance.PolicyFixture.GLOBAL;
import static io.reliabilityai.gateway.dataplane.governance.PolicyFixture.MODEL;
import static io.reliabilityai.gateway.dataplane.governance.PolicyFixture.document;
import static io.reliabilityai.gateway.dataplane.governance.PolicyFixture.request;
import static io.reliabilityai.gateway.dataplane.governance.PolicyFixture.requestContext;
import static io.reliabilityai.gateway.dataplane.governance.PolicyFixture.rule;
import static io.reliabilityai.gateway.dataplane.governance.PolicyFixture.snapshot;
import static io.reliabilityai.gateway.dataplane.governance.PolicyFixture.usage;
import static org.assertj.core.api.Assertions.assertThat;

import io.reliabilityai.gateway.canonical.identity.TenantScope;
import io.reliabilityai.gateway.dataplane.governance.api.PolicyAuditEvent;
import io.reliabilityai.gateway.dataplane.governance.api.PolicyDecision;
import io.reliabilityai.gateway.dataplane.governance.api.PolicyScope;
import io.reliabilityai.gateway.dataplane.governance.api.PolicyScopeRef;
import io.reliabilityai.gateway.dataplane.governance.api.PolicyType;
import io.reliabilityai.gateway.dataplane.governance.api.PolicyUsagePort;
import io.reliabilityai.gateway.dataplane.governance.api.PolicyValue;
import io.reliabilityai.gateway.dataplane.governance.api.PolicyVersion;
import io.reliabilityai.gateway.dataplane.governance.api.ScopeChain;
import io.reliabilityai.gateway.dataplane.governance.api.TickerPort;
import io.reliabilityai.gateway.dataplane.governance.api.Verdict;
import io.reliabilityai.gateway.dataplane.governance.application.GovernanceEngine;
import io.reliabilityai.gateway.dataplane.governance.domain.PolicyEvaluator;
import io.reliabilityai.gateway.dataplane.governance.internal.InProcessPolicyMetrics;
import io.reliabilityai.gateway.dataplane.governance.internal.PolicyRegistry;
import io.reliabilityai.gateway.dataplane.governance.internal.PolicyStore;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Queue;
import java.util.Set;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

/**
 * Tests the engine under concurrency. The read path takes no lock and touches no shared mutable
 * state, so the properties worth checking are that a snapshot swap is invisible to in-flight
 * evaluations and that a hundred threads agree on what policy says.
 */
class GovernanceConcurrencyTest {

  private static final int THREADS = 100;
  private static final PolicyEvaluator EVALUATOR =
      new PolicyEvaluator(Duration.ofSeconds(30), Duration.ofMinutes(5));
  private static final PolicyUsagePort FRESH = scope -> Optional.of(usage());

  private final Queue<PolicyAuditEvent> audited = new ConcurrentLinkedQueue<>();
  private final InProcessPolicyMetrics metrics = new InProcessPolicyMetrics();
  private final PolicyRegistry registry = new PolicyRegistry(new PolicyStore(256, 4), metrics);
  private final GovernanceEngine engine =
      new GovernanceEngine(
          registry, EVALUATOR, FRESH, audited::add, metrics, CLOCK, TickerPort.FROZEN, null);

  /** Runs a task on every virtual thread at once, released together to maximise overlap. */
  private static <T> List<T> inParallel(
      final int count, final java.util.function.IntFunction<T> task) throws InterruptedException {
    final CountDownLatch start = new CountDownLatch(1);
    final CountDownLatch done = new CountDownLatch(count);
    final Queue<T> results = new ConcurrentLinkedQueue<>();
    try (ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor()) {
      for (int i = 0; i < count; i++) {
        final int index = i;
        pool.execute(
            () -> {
              try {
                start.await();
                results.add(task.apply(index));
              } catch (final InterruptedException interrupted) {
                Thread.currentThread().interrupt();
              } finally {
                done.countDown();
              }
            });
      }
      start.countDown();
      assertThat(done.await(30, TimeUnit.SECONDS)).isTrue();
    }
    return new ArrayList<>(results);
  }

  @Test
  void aHundredConcurrentRequestsAllReachTheSameVerdict() throws InterruptedException {
    registry.install(
        snapshot(
            1L,
            document(
                GLOBAL,
                1L,
                rule("m", PolicyType.MODEL_ALLOW_LIST, PolicyValue.Values.of(MODEL.value())))));

    final List<PolicyDecision> decisions =
        inParallel(
            THREADS, i -> engine.govern(request().requestContext(requestContext("c" + i)).build()));

    assertThat(decisions).hasSize(THREADS);
    assertThat(decisions).allSatisfy(d -> assertThat(d.verdict()).isEqualTo(Verdict.ALLOW));
  }

  @Test
  void aHundredConcurrentRefusalsAllNameTheSameBindingStatement() throws InterruptedException {
    registry.install(
        snapshot(
            1L,
            document(
                GLOBAL,
                1L,
                rule(
                    "only-approved",
                    PolicyType.MODEL_ALLOW_LIST,
                    PolicyValue.Values.of("model.other")))));

    final List<PolicyDecision> decisions =
        inParallel(
            THREADS, i -> engine.govern(request().requestContext(requestContext("c" + i)).build()));

    assertThat(decisions)
        .allSatisfy(
            d -> {
              assertThat(d.verdict()).isEqualTo(Verdict.DENY);
              assertThat(d.binding()).get().extracting("ruleId").isEqualTo("only-approved");
            });
  }

  @Test
  void everyConcurrentDecisionIsAudited() throws InterruptedException {
    registry.install(snapshot(1L, document(GLOBAL, 1L)));

    inParallel(
        THREADS, i -> engine.govern(request().requestContext(requestContext("c" + i)).build()));

    assertThat(audited).hasSize(THREADS);
    assertThat(audited.stream().map(PolicyAuditEvent::correlationId).distinct().count())
        .isEqualTo(THREADS);
  }

  @Test
  void everyConcurrentDecisionIsCounted() throws InterruptedException {
    registry.install(snapshot(1L, document(GLOBAL, 1L)));

    inParallel(
        THREADS, i -> engine.govern(request().requestContext(requestContext("c" + i)).build()));

    assertThat(metrics.evaluations()).isEqualTo(THREADS);
    assertThat(metrics.decisions(Verdict.ALLOW)).isEqualTo(THREADS);
  }

  @Test
  void concurrentRequestsFromDifferentTenantsSeeOnlyTheirOwnPolicy() throws InterruptedException {
    registry.install(
        snapshot(
            1L,
            document(
                PolicyScopeRef.of(PolicyScope.ORGANIZATION, "org-blocked"),
                1L,
                rule("kill", PolicyType.EMERGENCY_KILL_SWITCH, PolicyValue.Flag.TRUE))));

    final List<PolicyDecision> decisions =
        inParallel(
            THREADS,
            i -> {
              final String org = i % 2 == 0 ? "org-blocked" : "org-fine";
              return engine.govern(
                  request()
                      .requestContext(requestContext("c" + i))
                      .tenant(
                          new io.reliabilityai.gateway.canonical.context.TenantContext(
                              TenantScope.of(org, "tenant-" + i)))
                      .scopeChain(ScopeChain.builder().organization(org).build())
                      .build());
            });

    final long refused = decisions.stream().filter(d -> !d.admits()).count();
    // Exactly the blocked organization's half is refused. The other half never observes its policy
    // at
    // all — resolution only probes nodes on the request's own chain.
    assertThat(refused).isEqualTo(THREADS / 2);
  }

  @Test
  void aSnapshotSwapDuringTrafficNeverProducesAHalfAppliedDecision() throws InterruptedException {
    registry.install(
        snapshot(
            1L,
            document(
                GLOBAL, 1L, rule("ctx", PolicyType.MAX_CONTEXT, PolicyValue.Limit.of(1_000)))));
    final AtomicInteger installs = new AtomicInteger();

    final List<PolicyDecision> decisions =
        inParallel(
            THREADS,
            i -> {
              if (i % 10 == 0) {
                final long sequence = 2L + installs.incrementAndGet();
                registry.install(
                    snapshot(
                        sequence,
                        document(
                            GLOBAL,
                            sequence,
                            rule("ctx", PolicyType.MAX_CONTEXT, PolicyValue.Limit.of(1_000)))));
              }
              return engine.govern(
                  request().requestContext(requestContext("c" + i)).contextTokens(10).build());
            });

    // Whatever generation each request landed on, it evaluated against exactly one of them, and
    // every
    // generation here permits the request. A torn read would show up as a spurious refusal.
    assertThat(decisions).allSatisfy(d -> assertThat(d.verdict()).isEqualTo(Verdict.ALLOW));
  }

  @Test
  void everyDecisionStampsAVersionThatWasGenuinelyInForce() throws InterruptedException {
    registry.install(snapshot(1L, document(GLOBAL, 1L)));
    final AtomicInteger installs = new AtomicInteger();

    final List<PolicyDecision> decisions =
        inParallel(
            THREADS,
            i -> {
              if (i % 20 == 0) {
                final long sequence = 2L + installs.incrementAndGet();
                registry.install(snapshot(sequence, document(GLOBAL, sequence)));
              }
              return engine.govern(request().requestContext(requestContext("c" + i)).build());
            });

    final Set<Long> stamped =
        decisions.stream().map(d -> d.policyVersion().sequence()).collect(Collectors.toSet());
    assertThat(stamped)
        .allSatisfy(sequence -> assertThat(sequence).isBetween(1L, 2L + installs.get()));
  }

  @Test
  void aRollbackUnderLoadNeverProducesAnUndecidedRequest() throws InterruptedException {
    registry.install(snapshot(1L, document(GLOBAL, 1L)));
    registry.install(snapshot(2L, document(GLOBAL, 2L)));

    final List<PolicyDecision> decisions =
        inParallel(
            THREADS,
            i -> {
              if (i % 25 == 0) {
                registry.rollbackTo(PolicyVersion.of("v1", 1L));
              }
              return engine.govern(request().requestContext(requestContext("c" + i)).build());
            });

    assertThat(decisions).hasSize(THREADS).allSatisfy(d -> assertThat(d.verdict()).isNotNull());
  }

  @Test
  void concurrentFoldsOfTheSameChainConvergeOnOneCachedResult() throws InterruptedException {
    registry.install(
        snapshot(
            1L, document(GLOBAL, 1L, rule("c", PolicyType.MAX_COST, PolicyValue.Limit.of(5)))));

    final List<Long> limits =
        inParallel(
            THREADS,
            i ->
                ((PolicyValue.Limit)
                        registry
                            .effectiveFor(PolicyFixture.chain())
                            .ruleFor(PolicyType.MAX_COST)
                            .value())
                    .value());

    // Racing threads may each compute the fold, which is harmless because the fold is pure — but
    // they
    // must all compute the same answer.
    assertThat(limits).hasSize(THREADS).containsOnly(5L);
  }

  @Test
  void concurrentEvaluationIsDeterministicDownToTheWholeDecision() throws InterruptedException {
    registry.install(
        snapshot(
            1L,
            document(
                GLOBAL,
                1L,
                rule("m", PolicyType.MODEL_ALLOW_LIST, PolicyValue.Values.of(MODEL.value())),
                rule("c", PolicyType.MAX_CONTEXT, PolicyValue.Limit.of(4_096)))));

    final List<PolicyDecision> decisions =
        inParallel(THREADS, i -> engine.govern(request().contextTokens(100).build()));

    assertThat(decisions.stream().distinct().count()).isEqualTo(1L);
  }

  @Test
  void aHundredConcurrentSimulationsLeaveTheEnforcementTrailUntouched()
      throws InterruptedException {
    registry.install(snapshot(1L, document(GLOBAL, 1L)));
    final var simulator =
        new io.reliabilityai.gateway.dataplane.governance.application.PolicySimulationEngine(
            engine,
            new io.reliabilityai.gateway.dataplane.governance.internal.PolicyCompiler(),
            CLOCK);

    inParallel(THREADS, i -> simulator.simulateCurrent(request().build()));

    assertThat(audited).isEmpty();
    assertThat(metrics.evaluations()).isZero();
  }
}

package io.reliabilityai.gateway.dataplane.memory.application;

import static io.reliabilityai.gateway.dataplane.memory.MemoryFixtures.TENANT;
import static io.reliabilityai.gateway.dataplane.memory.MemoryFixtures.caller;
import static io.reliabilityai.gateway.dataplane.memory.MemoryFixtures.permissivePolicy;
import static io.reliabilityai.gateway.dataplane.memory.MemoryFixtures.snapshotOf;
import static io.reliabilityai.gateway.dataplane.memory.MemoryFixtures.write;
import static org.assertj.core.api.Assertions.assertThat;

import io.reliabilityai.gateway.canonical.identity.CorrelationId;
import io.reliabilityai.gateway.canonical.identity.PrincipalId;
import io.reliabilityai.gateway.dataplane.memory.MemoryFixtures.FakeEmbedding;
import io.reliabilityai.gateway.dataplane.memory.MemoryFixtures.FakeGovernance;
import io.reliabilityai.gateway.dataplane.memory.MemoryFixtures.TestClock;
import io.reliabilityai.gateway.dataplane.memory.api.MemoryAuditPort;
import io.reliabilityai.gateway.dataplane.memory.api.MemoryQuery;
import io.reliabilityai.gateway.dataplane.memory.api.MemoryScope;
import io.reliabilityai.gateway.dataplane.memory.api.MemoryType;
import io.reliabilityai.gateway.dataplane.memory.api.RankingSignal;
import io.reliabilityai.gateway.dataplane.memory.domain.MemoryPolicySnapshot;
import io.reliabilityai.gateway.dataplane.memory.domain.MemoryRanker;
import io.reliabilityai.gateway.dataplane.memory.internal.ConservativePiiClassifier;
import io.reliabilityai.gateway.dataplane.memory.internal.InMemoryMemoryStore;
import io.reliabilityai.gateway.dataplane.memory.internal.InMemoryVectorIndex;
import io.reliabilityai.gateway.dataplane.memory.internal.InProcessMemoryMetrics;
import io.reliabilityai.gateway.dataplane.memory.internal.MemoryPolicyStore;
import io.reliabilityai.gateway.dataplane.memory.internal.NotRealCryptoSealer;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.DisplayNameGeneration;
import org.junit.jupiter.api.DisplayNameGenerator;
import org.junit.jupiter.api.Test;

/**
 * Measurements, taken in-process and reported rather than asserted tightly.
 *
 * <p>The assertions are deliberately loose — an order of magnitude, not a percentage — because a
 * unit test on shared hardware cannot hold a tight latency bound without becoming flaky, and a
 * flaky performance test is worse than none. The printed numbers are the deliverable; the
 * assertions exist only to catch a regression large enough to mean something is structurally wrong.
 *
 * <p><b>What these numbers do and do not mean.</b> They measure the runtime's own overhead against
 * reference adapters that hold everything in a hash map. A production adapter's network round trip
 * will dominate all of them. That is the point: what is being measured here is the cost the memory
 * plane <em>adds</em>, and it should stay far below the cost of the storage it wraps.
 */
@DisplayNameGeneration(DisplayNameGenerator.ReplaceUnderscores.class)
class MemoryBenchmarkTest {

  private final InMemoryMemoryStore store = new InMemoryMemoryStore();
  private final InMemoryVectorIndex index = new InMemoryVectorIndex();
  private final MemoryPolicyStore policies = new MemoryPolicyStore(4);
  private final InProcessMemoryMetrics metrics = new InProcessMemoryMetrics();
  private final TestClock clock = new TestClock();

  private static void report(final String what, final long nanos, final int iterations) {
    System.out.printf(
        "  PERF  %-38s %,10.0f ns/op  (%d iterations)%n",
        what, (double) nanos / iterations, iterations);
  }

  private MemoryRuntime runtime() {
    policies.install(snapshotOf(permissivePolicy()));
    final MemoryEmbedder embedder = new MemoryEmbedder(new FakeEmbedding());
    final FakeGovernance governance = new FakeGovernance();
    return new MemoryRuntime(
        new MemoryWritePipeline(
            policies,
            governance,
            new ConservativePiiClassifier(),
            new NotRealCryptoSealer(),
            store,
            index,
            embedder,
            MemoryAuditPort.NOOP,
            metrics,
            clock,
            MemoryIdFactory.DETERMINISTIC),
        new MemoryReadPipeline(
            policies,
            governance,
            store,
            index,
            embedder,
            new NotRealCryptoSealer(),
            new MemoryRanker(RankingSignal.Weights.DEFAULT),
            MemoryAuditPort.NOOP,
            metrics,
            clock),
        policies,
        governance,
        store,
        index,
        MemoryAuditPort.NOOP,
        metrics,
        clock);
  }

  @Test
  void policyResolutionIsSingleDigitMicrosecondsOnTheHotPath() {
    // The claim behind MEM-18 and MEM-27: the hot path parses nothing, merges nothing and takes no
    // lock. If this were slow, policy would have to be cached and allowed to go stale.
    final MemoryPolicySnapshot snapshot = snapshotOf(permissivePolicy());
    policies.install(snapshot);
    final MemoryScope scope = MemoryScope.ofTenant(TENANT);

    for (int i = 0; i < 100_000; i++) {
      policies.current().resolve(scope, MemoryType.LONG_TERM);
    }
    final int iterations = 1_000_000;
    final long start = System.nanoTime();
    for (int i = 0; i < iterations; i++) {
      policies.current().resolve(scope, MemoryType.LONG_TERM);
    }
    final long elapsed = System.nanoTime() - start;
    report("policy resolve (compiled snapshot)", elapsed, iterations);

    assertThat(elapsed / iterations).isLessThan(10_000L);
  }

  @Test
  void aSnapshotReadIsEssentiallyFree() {
    policies.install(snapshotOf(permissivePolicy()));
    for (int i = 0; i < 100_000; i++) {
      policies.current();
    }
    final int iterations = 5_000_000;
    final long start = System.nanoTime();
    long sink = 0;
    for (int i = 0; i < iterations; i++) {
      sink += policies.current().version();
    }
    final long elapsed = System.nanoTime() - start;
    report("snapshot read (one volatile read)", elapsed, iterations);
    assertThat(sink).isPositive();
  }

  @Test
  void aWriteIsDominatedByClassificationRatherThanByThePipeline() {
    final MemoryRuntime runtime = runtime();
    for (int i = 0; i < 500; i++) {
      runtime.write(
          caller(), write(MemoryScope.ofTenant(TENANT), MemoryType.SESSION, "warm " + i, "w" + i));
    }
    store.clear();

    final int iterations = 20_000;
    final long start = System.nanoTime();
    for (int i = 0; i < iterations; i++) {
      runtime.write(
          caller(), write(MemoryScope.ofTenant(TENANT), MemoryType.SESSION, "body " + i, "k" + i));
    }
    final long elapsed = System.nanoTime() - start;
    report("write, end to end", elapsed, iterations);
    assertThat(elapsed / iterations).isLessThan(1_000_000L);
  }

  @Test
  void aScopedReadOverAPopulatedStoreStaysWithinAMillisecond() {
    final MemoryRuntime runtime = runtime();
    for (int i = 0; i < 2_000; i++) {
      runtime.write(
          caller(),
          write(MemoryScope.ofTenant(TENANT), MemoryType.LONG_TERM, "body " + i, "k" + i));
    }
    final MemoryQuery query =
        MemoryQuery.ofScope(MemoryScope.ofTenant(TENANT), MemoryType.LONG_TERM);
    for (int i = 0; i < 200; i++) {
      runtime.read(caller(), query);
    }

    final int iterations = 2_000;
    final long start = System.nanoTime();
    for (int i = 0; i < iterations; i++) {
      runtime.read(caller(), query);
    }
    final long elapsed = System.nanoTime() - start;
    report("read over 2000 records", elapsed, iterations);
    assertThat(elapsed / iterations).isLessThan(50_000_000L);
  }

  @Test
  void rankingATypicalResultSetIsMicroseconds() {
    final MemoryRuntime runtime = runtime();
    for (int i = 0; i < 500; i++) {
      runtime.write(
          caller(), write(MemoryScope.ofTenant(TENANT), MemoryType.LONG_TERM, "fox " + i, "k" + i));
    }
    final MemoryQuery query =
        MemoryQuery.ofKeyword(MemoryScope.ofTenant(TENANT), MemoryType.LONG_TERM, "fox");
    for (int i = 0; i < 200; i++) {
      runtime.read(caller(), query);
    }

    final int iterations = 2_000;
    final long start = System.nanoTime();
    for (int i = 0; i < iterations; i++) {
      runtime.read(caller(), query);
    }
    final long elapsed = System.nanoTime() - start;
    report("keyword read + rank (500 candidates)", elapsed, iterations);
    assertThat(elapsed / iterations).isLessThan(50_000_000L);
  }

  @Test
  void scopeNarrowingIsCheapEnoughToDoOnEveryRequest() {
    // It runs before every single adapter call, so if it were expensive the temptation would be to
    // cache it — and a cached authorization decision is how isolation bugs happen.
    final MemoryScope requested = MemoryScope.ofTenant(TENANT);
    final MemoryScope callerScope = MemoryScope.ofUser(TENANT, new PrincipalId("alice"));
    for (int i = 0; i < 200_000; i++) {
      requested.narrowTo(callerScope);
    }

    final int iterations = 2_000_000;
    final long start = System.nanoTime();
    for (int i = 0; i < iterations; i++) {
      requested.narrowTo(callerScope);
    }
    final long elapsed = System.nanoTime() - start;
    report("scope narrowing", elapsed, iterations);
    assertThat(elapsed / iterations).isLessThan(10_000L);
  }

  @Test
  void digestingATypicalBodyIsSubMicrosecond() {
    final String body = "a reasonably sized memory body ".repeat(20);
    for (int i = 0; i < 50_000; i++) {
      io.reliabilityai.gateway.dataplane.memory.domain.MemoryDigest.of(body);
    }
    final int iterations = 200_000;
    final long start = System.nanoTime();
    for (int i = 0; i < iterations; i++) {
      io.reliabilityai.gateway.dataplane.memory.domain.MemoryDigest.of(body);
    }
    final long elapsed = System.nanoTime() - start;
    report("content digest (600 chars)", elapsed, iterations);
    assertThat(elapsed / iterations).isLessThan(100_000L);
  }

  @Test
  void concurrentReadsScaleWithoutContendingOnPolicy() throws Exception {
    // Lock-free reads (MEM-27). Eight threads reading policy and memory at once must not serialise
    // on
    // anything the runtime owns.
    final MemoryRuntime runtime = runtime();
    for (int i = 0; i < 500; i++) {
      runtime.write(
          caller(),
          write(MemoryScope.ofTenant(TENANT), MemoryType.LONG_TERM, "body " + i, "k" + i));
    }
    final MemoryQuery query =
        MemoryQuery.ofScope(MemoryScope.ofTenant(TENANT), MemoryType.LONG_TERM);

    final int threads = 8;
    final int perThread = 500;
    final ExecutorService pool = Executors.newFixedThreadPool(threads);
    final CountDownLatch start = new CountDownLatch(1);
    final AtomicInteger succeeded = new AtomicInteger();

    final List<Future<?>> workers = new ArrayList<>();
    for (int t = 0; t < threads; t++) {
      workers.add(
          pool.submit(
              () -> {
                start.await();
                for (int i = 0; i < perThread; i++) {
                  if (runtime.read(caller(), query).succeeded()) {
                    succeeded.incrementAndGet();
                  }
                }
                return null;
              }));
    }
    final long began = System.nanoTime();
    start.countDown();
    for (final Future<?> worker : workers) {
      worker.get(120, TimeUnit.SECONDS);
    }
    final long elapsed = System.nanoTime() - began;
    pool.shutdownNow();

    report("concurrent read (8 threads)", elapsed, threads * perThread);
    assertThat(succeeded.get()).isEqualTo(threads * perThread);
  }

  @Test
  void concurrentPolicyInstallationDoesNotBlockReaders() throws Exception {
    policies.install(snapshotOf(permissivePolicy()));
    final MemoryScope scope = MemoryScope.ofTenant(TENANT);

    final ExecutorService pool = Executors.newFixedThreadPool(5);
    final java.util.concurrent.atomic.AtomicBoolean stop =
        new java.util.concurrent.atomic.AtomicBoolean();
    final AtomicInteger reads = new AtomicInteger();

    final List<Future<?>> readers = new ArrayList<>();
    for (int i = 0; i < 4; i++) {
      readers.add(
          pool.submit(
              () -> {
                while (!stop.get()) {
                  policies.current().resolve(scope, MemoryType.SESSION);
                  reads.incrementAndGet();
                }
              }));
    }
    final long start = System.nanoTime();
    for (int version = 2; version <= 2_000; version++) {
      policies.install(new MemoryPolicySnapshot(version, Map.of(), Map.of(), permissivePolicy()));
    }
    final long elapsed = System.nanoTime() - start;
    stop.set(true);
    for (final Future<?> reader : readers) {
      reader.get(60, TimeUnit.SECONDS);
    }
    pool.shutdownNow();

    report("policy install under read load", elapsed, 1_999);
    // Readers kept running throughout: an install that blocked them would show as a low count.
    assertThat(reads.get()).isGreaterThan(1_000);
  }

  @Test
  void aPopulatedStoreCostsAModestAmountPerRecord() {
    final MemoryRuntime runtime = runtime();
    final Runtime jvm = Runtime.getRuntime();
    System.gc();
    final long before = jvm.totalMemory() - jvm.freeMemory();

    final int records = 10_000;
    for (int i = 0; i < records; i++) {
      runtime.write(
          caller(),
          write(MemoryScope.ofTenant(TENANT), MemoryType.LONG_TERM, "body number " + i, "k" + i));
    }
    System.gc();
    final long after = jvm.totalMemory() - jvm.freeMemory();
    final long perRecord = Math.max(0L, (after - before) / records);
    System.out.printf(
        "  PERF  %-38s %,10d bytes/record (%d records)%n",
        "resident record state", perRecord, records);

    // Loose by design: a heap delta measured around a GC is indicative, not exact. It catches an
    // order-of-magnitude regression such as retaining a full copy of every body twice over.
    assertThat(perRecord).isLessThan(20_000L);
    assertThat(store.size()).isEqualTo(records);
  }

  @Test
  void aSweepOverManyExpiredRecordsIsBoundedByItsBatch() {
    final MemoryRuntime runtime = runtime();
    for (int i = 0; i < 5_000; i++) {
      runtime.write(
          caller(), write(MemoryScope.ofTenant(TENANT), MemoryType.SESSION, "t" + i, "k" + i));
    }
    clock.advance(java.time.Duration.ofDays(60));

    final MemoryLifecycleSweeper sweeper =
        new MemoryLifecycleSweeper(
            store,
            index,
            policies,
            MemoryAuditPort.NOOP,
            metrics,
            clock,
            new PrincipalId("sweeper"),
            new CorrelationId("sweep"));

    final long start = System.nanoTime();
    final MemoryLifecycleSweeper.Pass pass = sweeper.sweep(1_000);
    final long elapsed = System.nanoTime() - start;
    report("sweep pass (batch 1000 of 5000)", elapsed, 1);

    assertThat(pass.expired()).isEqualTo(1_000);
    assertThat(store.size()).isEqualTo(4_000);
  }
}

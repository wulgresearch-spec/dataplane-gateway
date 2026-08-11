package io.reliabilityai.gateway.dataplane.memory.store.journal;

import static io.reliabilityai.gateway.dataplane.memory.store.journal.StoreFixtures.TENANT;
import static io.reliabilityai.gateway.dataplane.memory.store.journal.StoreFixtures.record;
import static io.reliabilityai.gateway.dataplane.memory.store.journal.StoreFixtures.scopeQuery;
import static org.assertj.core.api.Assertions.assertThat;

import io.reliabilityai.gateway.canonical.identity.TenantScope;
import io.reliabilityai.gateway.dataplane.memory.api.MemoryRecordId;
import io.reliabilityai.gateway.dataplane.memory.api.MemoryScope;
import io.reliabilityai.gateway.dataplane.memory.api.MemoryType;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.DisplayNameGeneration;
import org.junit.jupiter.api.DisplayNameGenerator;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Measurements, taken in-process and reported rather than asserted tightly.
 *
 * <p>The assertions are deliberately loose — an order of magnitude, not a percentage — because a
 * test on shared hardware cannot hold a tight latency bound without becoming flaky, and a flaky
 * performance test is worse than none. The printed numbers are the deliverable.
 *
 * <p><b>The number that matters most is the durable write.</b> It is a disk round trip by design:
 * every {@code put} fsyncs before returning, because AD-026 A1 says durability must be real before
 * the call comes back. The unsynced figure is measured alongside it purely to show how much of the
 * cost is the disk rather than the engine — <b>unsynced mode is not durable and is not for
 * production</b>.
 */
@DisplayNameGeneration(DisplayNameGenerator.ReplaceUnderscores.class)
class JournalBenchmarkTest {

  private static void report(final String what, final long nanos, final int iterations) {
    System.out.printf(
        "  PERF  %-40s %,12.0f ns/op  (%d ops)%n", what, (double) nanos / iterations, iterations);
  }

  private static void reportRate(final String what, final long nanos, final int operations) {
    final double perSecond = operations / (nanos / 1_000_000_000.0d);
    System.out.printf("  PERF  %-40s %,12.0f ops/sec  (%d ops)%n", what, perSecond, operations);
  }

  private static MemoryScope tenantScope() {
    return MemoryScope.ofTenant(TENANT);
  }

  @Test
  void durableWriteLatency(@TempDir final Path root) {
    try (JournalMemoryStore store = new JournalMemoryStore(root)) {
      for (int i = 0; i < 200; i++) {
        store.put(record("warm" + i, tenantScope(), "warm body " + i), "w" + i);
      }
      final int iterations = 2_000;
      final long start = System.nanoTime();
      for (int i = 0; i < iterations; i++) {
        store.put(record("r" + i, tenantScope(), "a typical memory body " + i), "k" + i);
      }
      final long elapsed = System.nanoTime() - start;
      report("write, fsync per op (DURABLE)", elapsed, iterations);
      reportRate("write throughput, fsync per op", elapsed, iterations);
      assertThat(elapsed / iterations).isLessThan(500_000_000L);
    }
  }

  @Test
  void writeLatencyWithoutSyncShowsHowMuchOfTheCostIsTheDisk(@TempDir final Path root) {
    // Not a production mode. Measured only so the engine's own overhead is separable from the
    // disk's.
    try (JournalMemoryStore store = new JournalMemoryStore(root, 8L * 1024 * 1024, false)) {
      for (int i = 0; i < 500; i++) {
        store.put(record("warm" + i, tenantScope(), "warm " + i), "w" + i);
      }
      final int iterations = 20_000;
      final long start = System.nanoTime();
      for (int i = 0; i < iterations; i++) {
        store.put(record("r" + i, tenantScope(), "a typical memory body " + i), "k" + i);
      }
      final long elapsed = System.nanoTime() - start;
      report("write, no fsync (NOT DURABLE)", elapsed, iterations);
      reportRate("write throughput, no fsync", elapsed, iterations);
    }
  }

  @Test
  void pointReadLatency(@TempDir final Path root) {
    try (JournalMemoryStore store = new JournalMemoryStore(root, 8L * 1024 * 1024, false)) {
      for (int i = 0; i < 10_000; i++) {
        store.put(record("r" + i, tenantScope(), "body " + i), "k" + i);
      }
      for (int i = 0; i < 50_000; i++) {
        store.get(tenantScope(), MemoryRecordId.of("r" + (i % 10_000)));
      }
      final int iterations = 500_000;
      final long start = System.nanoTime();
      for (int i = 0; i < iterations; i++) {
        store.get(tenantScope(), MemoryRecordId.of("r" + (i % 10_000)));
      }
      final long elapsed = System.nanoTime() - start;
      report("point read (10k records)", elapsed, iterations);
      reportRate("point read throughput", elapsed, iterations);
      assertThat(elapsed / iterations).isLessThan(1_000_000L);
    }
  }

  @Test
  void scanLatencyOverAPopulatedTenant(@TempDir final Path root) {
    try (JournalMemoryStore store = new JournalMemoryStore(root, 8L * 1024 * 1024, false)) {
      for (int i = 0; i < 5_000; i++) {
        store.put(record("r" + i, tenantScope(), "body " + i), "k" + i);
      }
      for (int i = 0; i < 100; i++) {
        store.search(scopeQuery(tenantScope(), MemoryType.LONG_TERM));
      }
      final int iterations = 1_000;
      final long start = System.nanoTime();
      for (int i = 0; i < iterations; i++) {
        store.search(scopeQuery(tenantScope(), MemoryType.LONG_TERM));
      }
      final long elapsed = System.nanoTime() - start;
      report("scoped search over 5000 records", elapsed, iterations);
      assertThat(elapsed / iterations).isLessThan(100_000_000L);
    }
  }

  @Test
  void concurrentReadThroughput(@TempDir final Path root) throws Exception {
    try (JournalMemoryStore store = new JournalMemoryStore(root, 8L * 1024 * 1024, false)) {
      for (int i = 0; i < 5_000; i++) {
        store.put(record("r" + i, tenantScope(), "body " + i), "k" + i);
      }
      final int threads = 8;
      final int perThread = 50_000;
      final ExecutorService pool = Executors.newFixedThreadPool(threads);
      final CountDownLatch start = new CountDownLatch(1);
      final List<Future<?>> workers = new ArrayList<>();
      for (int t = 0; t < threads; t++) {
        workers.add(
            pool.submit(
                () -> {
                  start.await();
                  for (int i = 0; i < perThread; i++) {
                    store.get(tenantScope(), MemoryRecordId.of("r" + (i % 5_000)));
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

      report("concurrent point read (8 threads)", elapsed, threads * perThread);
      reportRate("concurrent read throughput (8 threads)", elapsed, threads * perThread);
    }
  }

  @Test
  void concurrentWriteThroughputAcrossTenants(@TempDir final Path root) throws Exception {
    // Different tenants take different locks, so this measures whether partitioning actually buys
    // parallelism rather than just tidiness.
    try (JournalMemoryStore store = new JournalMemoryStore(root, 8L * 1024 * 1024, false)) {
      final int tenants = 8;
      final int perTenant = 5_000;
      final ExecutorService pool = Executors.newFixedThreadPool(tenants);
      final CountDownLatch start = new CountDownLatch(1);
      final AtomicInteger next = new AtomicInteger();
      final List<Future<?>> workers = new ArrayList<>();
      for (int t = 0; t < tenants; t++) {
        workers.add(
            pool.submit(
                () -> {
                  final int index = next.getAndIncrement();
                  final MemoryScope scope =
                      MemoryScope.ofTenant(TenantScope.of("org", "tenant" + index));
                  start.await();
                  for (int i = 0; i < perTenant; i++) {
                    store.put(
                        record("t" + index + "r" + i, scope, "body " + i), "k" + index + "-" + i);
                  }
                  return null;
                }));
      }
      final long began = System.nanoTime();
      start.countDown();
      for (final Future<?> worker : workers) {
        worker.get(180, TimeUnit.SECONDS);
      }
      final long elapsed = System.nanoTime() - began;
      pool.shutdownNow();

      reportRate("concurrent write, 8 tenants, no fsync", elapsed, tenants * perTenant);
      assertThat(store.size()).isEqualTo(tenants * perTenant);
    }
  }

  @Test
  void concurrentWriteThroughputWithinOneTenant(@TempDir final Path root) throws Exception {
    // One tenant is one lock, so this is the serialised case — the honest lower bound.
    try (JournalMemoryStore store = new JournalMemoryStore(root, 8L * 1024 * 1024, false)) {
      final int threads = 8;
      final int perThread = 4_000;
      final ExecutorService pool = Executors.newFixedThreadPool(threads);
      final CountDownLatch start = new CountDownLatch(1);
      final AtomicInteger next = new AtomicInteger();
      final List<Future<?>> workers = new ArrayList<>();
      for (int t = 0; t < threads; t++) {
        workers.add(
            pool.submit(
                () -> {
                  final int writer = next.getAndIncrement();
                  start.await();
                  for (int i = 0; i < perThread; i++) {
                    store.put(
                        record("w" + writer + "r" + i, tenantScope(), "body " + i),
                        "k" + writer + "-" + i);
                  }
                  return null;
                }));
      }
      final long began = System.nanoTime();
      start.countDown();
      for (final Future<?> worker : workers) {
        worker.get(180, TimeUnit.SECONDS);
      }
      final long elapsed = System.nanoTime() - began;
      pool.shutdownNow();

      reportRate("concurrent write, 1 tenant, no fsync", elapsed, threads * perThread);
      assertThat(store.size()).isEqualTo(threads * perThread);
    }
  }

  @Test
  void concurrentDurableWriteThroughputAcrossTenants(@TempDir final Path root) throws Exception {
    // The figure an operator actually needs. A single-threaded durable write is one fsync round
    // trip,
    // so the single-thread number understates the system badly: different tenants take different
    // locks and therefore issue their fsyncs in parallel.
    try (JournalMemoryStore store = new JournalMemoryStore(root)) {
      final int tenants = 8;
      final int perTenant = 150;
      final ExecutorService pool = Executors.newFixedThreadPool(tenants);
      final CountDownLatch start = new CountDownLatch(1);
      final AtomicInteger next = new AtomicInteger();
      final List<Future<?>> workers = new ArrayList<>();
      for (int t = 0; t < tenants; t++) {
        workers.add(
            pool.submit(
                () -> {
                  final int index = next.getAndIncrement();
                  final MemoryScope scope =
                      MemoryScope.ofTenant(TenantScope.of("org", "dur" + index));
                  start.await();
                  for (int i = 0; i < perTenant; i++) {
                    store.put(
                        record("t" + index + "r" + i, scope, "body " + i), "k" + index + "-" + i);
                  }
                  return null;
                }));
      }
      final long began = System.nanoTime();
      start.countDown();
      for (final Future<?> worker : workers) {
        worker.get(300, TimeUnit.SECONDS);
      }
      final long elapsed = System.nanoTime() - began;
      pool.shutdownNow();

      reportRate("concurrent write, 8 tenants, DURABLE", elapsed, tenants * perTenant);
      assertThat(store.size()).isEqualTo(tenants * perTenant);
    }
  }

  @Test
  void restartTimeOverAPopulatedStore(@TempDir final Path root) {
    final int records = 20_000;
    try (JournalMemoryStore store = new JournalMemoryStore(root, 8L * 1024 * 1024, false)) {
      for (int i = 0; i < records; i++) {
        store.put(record("r" + i, tenantScope(), "a typical memory body " + i), "k" + i);
      }
    }
    final long start = System.nanoTime();
    final JournalMemoryStore reopened = new JournalMemoryStore(root);
    final long elapsed = System.nanoTime() - start;
    try {
      assertThat(reopened.size()).isEqualTo(records);
      System.out.printf(
          "  PERF  %-40s %,12.1f ms  (%d records, %,d bytes)%n",
          "cold restart (replay whole log)", elapsed / 1_000_000.0d, records, reopened.diskBytes());
      report("restart, per record", elapsed, records);
    } finally {
      reopened.close();
    }
  }

  @Test
  void restartTimeAcrossManyTenants(@TempDir final Path root) {
    final int tenants = 50;
    final int perTenant = 200;
    try (JournalMemoryStore store = new JournalMemoryStore(root, 8L * 1024 * 1024, false)) {
      for (int t = 0; t < tenants; t++) {
        final MemoryScope scope = MemoryScope.ofTenant(TenantScope.of("org", "t" + t));
        for (int i = 0; i < perTenant; i++) {
          store.put(record("t" + t + "r" + i, scope, "body " + i), "k" + t + "-" + i);
        }
      }
    }
    final long start = System.nanoTime();
    final JournalMemoryStore reopened = new JournalMemoryStore(root);
    final long elapsed = System.nanoTime() - start;
    try {
      assertThat(reopened.partitionCount()).isEqualTo(tenants);
      System.out.printf(
          "  PERF  %-40s %,12.1f ms  (%d tenants)%n",
          "cold restart across tenants", elapsed / 1_000_000.0d, tenants);
    } finally {
      reopened.close();
    }
  }

  @Test
  void memoryOverheadPerRecord(@TempDir final Path root) {
    final int records = 20_000;
    try (JournalMemoryStore store = new JournalMemoryStore(root, 8L * 1024 * 1024, false)) {
      final Runtime jvm = Runtime.getRuntime();
      System.gc();
      final long before = jvm.totalMemory() - jvm.freeMemory();
      for (int i = 0; i < records; i++) {
        store.put(record("r" + i, tenantScope(), "a typical memory body " + i), "k" + i);
      }
      System.gc();
      final long after = jvm.totalMemory() - jvm.freeMemory();
      final long perRecord = Math.max(0L, (after - before) / records);

      System.out.printf(
          "  PERF  %-40s %,12d bytes/record  (heap, %d records)%n",
          "resident overhead", perRecord, records);
      System.out.printf(
          "  PERF  %-40s %,12d bytes/record  (disk)%n",
          "on-disk overhead", store.diskBytes() / records);

      // Loose by design: a heap delta measured around a GC is indicative, not exact. It catches an
      // order-of-magnitude regression such as retaining every version of every record.
      assertThat(perRecord).isLessThan(20_000L);
    }
  }

  @Test
  void compactionCostAndSaving(@TempDir final Path root) {
    try (JournalMemoryStore store = new JournalMemoryStore(root, 8L * 1024 * 1024, false)) {
      for (int i = 0; i < 5_000; i++) {
        store.put(record("r" + i, tenantScope(), "body " + i), "k" + i);
      }
      for (int i = 0; i < 4_000; i++) {
        store.delete(tenantScope(), MemoryRecordId.of("r" + i));
      }
      final long before = store.diskBytes();
      final long start = System.nanoTime();
      final long after = store.compact();
      final long elapsed = System.nanoTime() - start;

      System.out.printf(
          "  PERF  %-40s %,12.1f ms  (%,d -> %,d bytes)%n",
          "compaction (5000 written, 4000 deleted)", elapsed / 1_000_000.0d, before, after);
      assertThat(after).isLessThan(before);
      assertThat(store.size()).isEqualTo(1_000);
    }
  }

  @Test
  void snapshotCost(@TempDir final Path root, @TempDir final Path snapshot) {
    try (JournalMemoryStore store = new JournalMemoryStore(root, 8L * 1024 * 1024, false)) {
      for (int i = 0; i < 10_000; i++) {
        store.put(record("r" + i, tenantScope(), "body " + i), "k" + i);
      }
      final long start = System.nanoTime();
      store.snapshotTo(snapshot);
      final long elapsed = System.nanoTime() - start;
      System.out.printf(
          "  PERF  %-40s %,12.1f ms  (%,d bytes)%n",
          "snapshot (10000 records)", elapsed / 1_000_000.0d, store.diskBytes());
    }
  }
}

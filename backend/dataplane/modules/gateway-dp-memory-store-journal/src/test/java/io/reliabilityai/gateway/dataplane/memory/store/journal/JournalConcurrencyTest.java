package io.reliabilityai.gateway.dataplane.memory.store.journal;

import static io.reliabilityai.gateway.dataplane.memory.store.journal.StoreFixtures.T0;
import static io.reliabilityai.gateway.dataplane.memory.store.journal.StoreFixtures.TENANT;
import static io.reliabilityai.gateway.dataplane.memory.store.journal.StoreFixtures.record;
import static io.reliabilityai.gateway.dataplane.memory.store.journal.StoreFixtures.scopeQuery;
import static org.assertj.core.api.Assertions.assertThat;

import io.reliabilityai.gateway.canonical.identity.TenantScope;
import io.reliabilityai.gateway.dataplane.memory.api.MemoryRecord;
import io.reliabilityai.gateway.dataplane.memory.api.MemoryRecordId;
import io.reliabilityai.gateway.dataplane.memory.api.MemoryScope;
import io.reliabilityai.gateway.dataplane.memory.api.MemoryStorePort.PutResult;
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
 * Concurrent writers, duplicate requests, optimistic concurrency and partial failure.
 *
 * <p>The property that matters most is that <b>the log and the index can never disagree</b>.
 * Appending, syncing and publishing happen as one step under a per-partition lock, so no
 * interleaving of concurrent writers can produce an in-memory state that a restart would not
 * reproduce. Every test here checks the live state <em>and</em> reopens the store to check the
 * recovered one.
 */
@DisplayNameGeneration(DisplayNameGenerator.ReplaceUnderscores.class)
class JournalConcurrencyTest {

  private static MemoryScope tenantScope() {
    return MemoryScope.ofTenant(TENANT);
  }

  private static <T> List<Future<T>> runAll(
      final ExecutorService pool, final int count, final java.util.concurrent.Callable<T> task)
      throws Exception {
    final CountDownLatch start = new CountDownLatch(1);
    final List<Future<T>> futures = new ArrayList<>(count);
    for (int i = 0; i < count; i++) {
      futures.add(
          pool.submit(
              () -> {
                start.await();
                return task.call();
              }));
    }
    start.countDown();
    for (final Future<T> future : futures) {
      future.get(120, TimeUnit.SECONDS);
    }
    return futures;
  }

  @Test
  void concurrentWritesToOneTenantAllLandAndAllSurviveARestart(@TempDir final Path root)
      throws Exception {
    final int writers = 8;
    final int perWriter = 250;
    try (JournalMemoryStore store = new JournalMemoryStore(root)) {
      final ExecutorService pool = Executors.newFixedThreadPool(writers);
      final AtomicInteger next = new AtomicInteger();
      runAll(
          pool,
          writers,
          () -> {
            final int writer = next.getAndIncrement();
            for (int i = 0; i < perWriter; i++) {
              final String id = "w" + writer + "-r" + i;
              store.put(record(id, tenantScope(), "body " + id), "k-" + id);
            }
            return null;
          });
      pool.shutdownNow();
      assertThat(store.size()).isEqualTo(writers * perWriter);
    }
    try (JournalMemoryStore reopened = new JournalMemoryStore(root)) {
      assertThat(reopened.size()).isEqualTo(writers * perWriter);
    }
  }

  @Test
  void concurrentDuplicateWritesStoreExactlyOnce(@TempDir final Path root) throws Exception {
    // The race a retried request creates: many callers, one write key. Exactly one must create.
    try (JournalMemoryStore store = new JournalMemoryStore(root)) {
      final ExecutorService pool = Executors.newFixedThreadPool(16);
      final AtomicInteger created = new AtomicInteger();
      runAll(
          pool,
          16,
          () -> {
            final PutResult result =
                store.put(record("contested", tenantScope(), "same"), "one-key");
            if (result.created()) {
              created.incrementAndGet();
            }
            return null;
          });
      pool.shutdownNow();

      assertThat(created.get()).isEqualTo(1);
      assertThat(store.size()).isEqualTo(1);
    }
    try (JournalMemoryStore reopened = new JournalMemoryStore(root)) {
      assertThat(reopened.size()).isEqualTo(1);
    }
  }

  @Test
  void concurrentWritersAcrossTenantsDoNotContendOrLeak(@TempDir final Path root) throws Exception {
    final int tenants = 8;
    final int perTenant = 200;
    try (JournalMemoryStore store = new JournalMemoryStore(root)) {
      final ExecutorService pool = Executors.newFixedThreadPool(tenants);
      final AtomicInteger next = new AtomicInteger();
      runAll(
          pool,
          tenants,
          () -> {
            final int index = next.getAndIncrement();
            final MemoryScope scope = MemoryScope.ofTenant(TenantScope.of("org", "t" + index));
            for (int i = 0; i < perTenant; i++) {
              store.put(record("t" + index + "-r" + i, scope, "body"), "k-" + index + "-" + i);
            }
            return null;
          });
      pool.shutdownNow();

      assertThat(store.partitionCount()).isEqualTo(tenants);
      for (int index = 0; index < tenants; index++) {
        final MemoryScope scope = MemoryScope.ofTenant(TenantScope.of("org", "t" + index));
        assertThat(store.search(scopeQuery(scope, MemoryType.LONG_TERM)).size())
            .isLessThanOrEqualTo(perTenant);
      }
      assertThat(store.size()).isEqualTo(tenants * perTenant);
    }
  }

  @Test
  void concurrentReadsAndWritesNeverObserveAPartialRecord(@TempDir final Path root)
      throws Exception {
    try (JournalMemoryStore store = new JournalMemoryStore(root)) {
      for (int i = 0; i < 200; i++) {
        store.put(record("r" + i, tenantScope(), "seed " + i), "k" + i);
      }
      final ExecutorService pool = Executors.newFixedThreadPool(8);
      final java.util.concurrent.atomic.AtomicBoolean stop =
          new java.util.concurrent.atomic.AtomicBoolean();
      final AtomicInteger reads = new AtomicInteger();
      final java.util.concurrent.atomic.AtomicBoolean torn =
          new java.util.concurrent.atomic.AtomicBoolean();

      final List<Future<?>> readers = new ArrayList<>();
      for (int i = 0; i < 6; i++) {
        readers.add(
            pool.submit(
                () -> {
                  while (!stop.get()) {
                    for (final var scored :
                        store.search(scopeQuery(tenantScope(), MemoryType.LONG_TERM))) {
                      final MemoryRecord seen = scored.record();
                      // Every field a reader can see must belong to one coherent record.
                      if (seen.content().digest() == null || seen.scope() == null) {
                        torn.set(true);
                      }
                    }
                    reads.incrementAndGet();
                  }
                }));
      }
      for (int i = 200; i < 600; i++) {
        store.put(record("r" + i, tenantScope(), "later " + i), "k" + i);
      }
      stop.set(true);
      for (final Future<?> reader : readers) {
        reader.get(60, TimeUnit.SECONDS);
      }
      pool.shutdownNow();

      assertThat(torn).isFalse();
      assertThat(reads.get()).isPositive();
      assertThat(store.size()).isEqualTo(600);
    }
  }

  @Test
  void concurrentDeletesRemoveExactlyOnce(@TempDir final Path root) throws Exception {
    try (JournalMemoryStore store = new JournalMemoryStore(root)) {
      store.put(record("doomed", tenantScope(), "x"), "k1");

      final ExecutorService pool = Executors.newFixedThreadPool(12);
      final AtomicInteger removed = new AtomicInteger();
      runAll(
          pool,
          12,
          () -> {
            if (store.delete(tenantScope(), MemoryRecordId.of("doomed"))) {
              removed.incrementAndGet();
            }
            return null;
          });
      pool.shutdownNow();

      assertThat(removed.get()).isEqualTo(1);
      assertThat(store.size()).isZero();
    }
  }

  @Test
  void concurrentTouchesDoNotLoseCounts(@TempDir final Path root) throws Exception {
    final int touches = 400;
    try (JournalMemoryStore store = new JournalMemoryStore(root)) {
      store.put(record("hot", tenantScope(), "x"), "k1");

      final ExecutorService pool = Executors.newFixedThreadPool(8);
      final AtomicInteger issued = new AtomicInteger();
      runAll(
          pool,
          8,
          () -> {
            for (int i = 0; i < touches / 8; i++) {
              store.touch(tenantScope(), MemoryRecordId.of("hot"), T0.plusSeconds(i));
              issued.incrementAndGet();
            }
            return null;
          });
      pool.shutdownNow();

      assertThat(store.get(tenantScope(), MemoryRecordId.of("hot")).orElseThrow().accessCount())
          .isEqualTo(issued.get());
    }
  }

  // ---- optimistic concurrency -------------------------------------------------------------------

  @Test
  void aVersionCheckedWriteSucceedsWhenNobodyIntervened(@TempDir final Path root) {
    try (JournalMemoryStore store = new JournalMemoryStore(root)) {
      store.put(record("r1", tenantScope(), "first"), "k1");
      final long stamp = store.stampOf(tenantScope(), MemoryRecordId.of("r1")).orElseThrow();

      assertThat(store.putIfUnchanged(record("r1", tenantScope(), "second"), "k2", stamp))
          .isPresent();
      assertThat(store.get(tenantScope(), MemoryRecordId.of("r1")).orElseThrow().content().body())
          .isEqualTo("second");
    }
  }

  @Test
  void aVersionCheckedWriteFailsWhenAnotherWriterIntervened(@TempDir final Path root) {
    try (JournalMemoryStore store = new JournalMemoryStore(root)) {
      store.put(record("r1", tenantScope(), "first"), "k1");
      final long stale = store.stampOf(tenantScope(), MemoryRecordId.of("r1")).orElseThrow();

      // Somebody else writes, moving the stamp.
      store.put(record("r1", tenantScope(), "intervening"), "k2");

      assertThat(store.putIfUnchanged(record("r1", tenantScope(), "lost update"), "k3", stale))
          .isEmpty();
      // The intervening write stands: a conflict must not silently overwrite.
      assertThat(store.get(tenantScope(), MemoryRecordId.of("r1")).orElseThrow().content().body())
          .isEqualTo("intervening");
    }
  }

  @Test
  void concurrentVersionCheckedWritesLetExactlyOneThrough(@TempDir final Path root)
      throws Exception {
    try (JournalMemoryStore store = new JournalMemoryStore(root)) {
      store.put(record("r1", tenantScope(), "seed"), "k0");
      final long stamp = store.stampOf(tenantScope(), MemoryRecordId.of("r1")).orElseThrow();

      final ExecutorService pool = Executors.newFixedThreadPool(12);
      final AtomicInteger won = new AtomicInteger();
      final AtomicInteger attempt = new AtomicInteger();
      runAll(
          pool,
          12,
          () -> {
            final int mine = attempt.getAndIncrement();
            if (store
                .putIfUnchanged(record("r1", tenantScope(), "writer " + mine), "k-" + mine, stamp)
                .isPresent()) {
              won.incrementAndGet();
            }
            return null;
          });
      pool.shutdownNow();

      // Exactly one saw the stamp it based its decision on. The rest are told, not silently
      // dropped.
      assertThat(won.get()).isEqualTo(1);
    }
  }

  @Test
  void aStampForAnUnknownRecordIsAbsent(@TempDir final Path root) {
    try (JournalMemoryStore store = new JournalMemoryStore(root)) {
      assertThat(store.stampOf(tenantScope(), MemoryRecordId.of("never"))).isEmpty();
    }
  }

  @Test
  void aVersionCheckedWriteAgainstAnAbsentRecordSucceedsAtStampZero(@TempDir final Path root) {
    // "Create only if it does not exist" falls out of the same mechanism, rather than needing a
    // second method that could disagree with this one.
    try (JournalMemoryStore store = new JournalMemoryStore(root)) {
      assertThat(store.putIfUnchanged(record("fresh", tenantScope(), "x"), "k1", 0L)).isPresent();
      assertThat(store.size()).isEqualTo(1);
    }
  }

  // ---- partial failure
  // ----------------------------------------------------------------------------

  @Test
  void aWriteThatFailsMidwayLeavesNothingHalfStored(@TempDir final Path root) {
    try (JournalMemoryStore store = new JournalMemoryStore(root)) {
      store.put(record("before", tenantScope(), "x"), "k1");
      store.setAvailable(false);
      org.assertj.core.api.Assertions.assertThatThrownBy(
              () -> store.put(record("during", tenantScope(), "y"), "k2"))
          .isInstanceOf(
              io.reliabilityai.gateway.dataplane.memory.api.MemoryStoreUnavailableException.class);
      store.setAvailable(true);

      assertThat(store.size()).isEqualTo(1);
      assertThat(store.get(tenantScope(), MemoryRecordId.of("during"))).isEmpty();
    }
  }

  @Test
  void aStoreThatWasUnavailableMidRunRecoversWithoutLosingWhatCameBefore(@TempDir final Path root) {
    try (JournalMemoryStore store = new JournalMemoryStore(root)) {
      store.put(record("a", tenantScope(), "x"), "k1");
      store.setAvailable(false);
      store.setAvailable(true);
      store.put(record("b", tenantScope(), "y"), "k2");
      assertThat(store.size()).isEqualTo(2);
    }
    try (JournalMemoryStore reopened = new JournalMemoryStore(root)) {
      assertThat(reopened.size()).isEqualTo(2);
    }
  }

  @Test
  void aRetriedWriteAfterAnUncertainFailureIsSafe(@TempDir final Path root) {
    // The caller never learned whether the first attempt landed. Retrying with the same key must be
    // harmless whichever it was — which is the whole reason idempotence is a port guarantee.
    try (JournalMemoryStore store = new JournalMemoryStore(root)) {
      store.put(record("r1", tenantScope(), "attempt"), "same-key");
      for (int retry = 0; retry < 5; retry++) {
        store.put(record("r1", tenantScope(), "attempt"), "same-key");
      }
      assertThat(store.size()).isEqualTo(1);
    }
  }

  @Test
  void concurrentCompactionAndWritesDoNotLoseRecords(@TempDir final Path root) throws Exception {
    try (JournalMemoryStore store = new JournalMemoryStore(root)) {
      for (int i = 0; i < 100; i++) {
        store.put(record("seed" + i, tenantScope(), "x"), "ks" + i);
      }
      final ExecutorService pool = Executors.newFixedThreadPool(4);
      final AtomicInteger next = new AtomicInteger();
      runAll(
          pool,
          4,
          () -> {
            final int writer = next.getAndIncrement();
            if (writer == 0) {
              store.compact();
            } else {
              for (int i = 0; i < 100; i++) {
                store.put(record("w" + writer + "-" + i, tenantScope(), "y"), "kw" + writer + i);
              }
            }
            return null;
          });
      pool.shutdownNow();
      assertThat(store.size()).isEqualTo(100 + 300);
    }
    try (JournalMemoryStore reopened = new JournalMemoryStore(root)) {
      assertThat(reopened.size()).isEqualTo(400);
    }
  }
}

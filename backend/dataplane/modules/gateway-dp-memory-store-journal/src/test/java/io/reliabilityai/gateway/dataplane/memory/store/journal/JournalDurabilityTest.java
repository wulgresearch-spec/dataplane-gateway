package io.reliabilityai.gateway.dataplane.memory.store.journal;

import static io.reliabilityai.gateway.dataplane.memory.store.journal.StoreFixtures.OTHER_TENANT;
import static io.reliabilityai.gateway.dataplane.memory.store.journal.StoreFixtures.T0;
import static io.reliabilityai.gateway.dataplane.memory.store.journal.StoreFixtures.TENANT;
import static io.reliabilityai.gateway.dataplane.memory.store.journal.StoreFixtures.awkwardRecord;
import static io.reliabilityai.gateway.dataplane.memory.store.journal.StoreFixtures.record;
import static io.reliabilityai.gateway.dataplane.memory.store.journal.StoreFixtures.scopeQuery;
import static org.assertj.core.api.Assertions.assertThat;

import io.reliabilityai.gateway.dataplane.memory.api.MemoryRecord;
import io.reliabilityai.gateway.dataplane.memory.api.MemoryRecordId;
import io.reliabilityai.gateway.dataplane.memory.api.MemoryScope;
import io.reliabilityai.gateway.dataplane.memory.api.MemoryType;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayNameGeneration;
import org.junit.jupiter.api.DisplayNameGenerator;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Crash recovery, restart, torn writes, compaction and snapshots.
 *
 * <p>Every case here simulates something a running process cannot do to itself: losing power
 * mid-write, being killed between two operations, having a sector corrupted underneath it. The
 * store is closed and reopened to stand in for a restart, and the on-disk bytes are mangled
 * directly to stand in for hardware.
 *
 * <p>The property under test throughout is that <b>a partial write is discarded, never
 * repaired</b>. A half-parsed record would be a fabricated fact, and every guarantee above this
 * layer — integrity, audit, delete proof — assumes a record is exactly what was written.
 */
@DisplayNameGeneration(DisplayNameGenerator.ReplaceUnderscores.class)
class JournalDurabilityTest {

  private static MemoryScope tenantScope() {
    return MemoryScope.ofTenant(TENANT);
  }

  private static Path onlySegment(final Path root) throws IOException {
    try (Stream<Path> files = Files.walk(root)) {
      return files
          .filter(path -> path.getFileName().toString().endsWith(".log"))
          .findFirst()
          .orElseThrow(() -> new IllegalStateException("no segment under " + root));
    }
  }

  private static List<Path> allSegments(final Path root) throws IOException {
    try (Stream<Path> files = Files.walk(root)) {
      return files.filter(path -> path.getFileName().toString().endsWith(".log")).sorted().toList();
    }
  }

  // ---- restart
  // ------------------------------------------------------------------------------------

  @Test
  void recordsSurviveACleanRestart(@TempDir final Path root) {
    try (JournalMemoryStore first = new JournalMemoryStore(root)) {
      first.put(record("r1", tenantScope(), "durable"), "k1");
      first.put(record("r2", tenantScope(), "also durable"), "k2");
    }
    try (JournalMemoryStore reopened = new JournalMemoryStore(root)) {
      assertThat(reopened.size()).isEqualTo(2);
      assertThat(reopened.get(tenantScope(), MemoryRecordId.of("r1"))).isPresent();
    }
  }

  @Test
  void recordsSurviveAnUncleanRestartBecauseEveryWriteIsSynced(@TempDir final Path root) {
    // No close at all — the process simply vanishes. Every acknowledged put was fsynced, so nothing
    // acknowledged is lost. That is what AD-026 A1 promises.
    final JournalMemoryStore abandoned = new JournalMemoryStore(root);
    abandoned.put(record("r1", tenantScope(), "survived"), "k1");
    abandoned.put(record("r2", tenantScope(), "survived too"), "k2");

    try (JournalMemoryStore reopened = new JournalMemoryStore(root)) {
      assertThat(reopened.size()).isEqualTo(2);
    }
  }

  @Test
  void everyAcknowledgedWriteForcedDataToDisk(@TempDir final Path root) {
    // Counts syncs rather than proving the platter was written. Nothing inside the process can
    // prove
    // the latter — the page cache serves reads whether or not data reached the disk, which is why
    // the
    // restart tests above pass even with fsync removed. This is the strongest in-process check
    // available, and AD-027 §9 records what it still does not prove.
    try (JournalMemoryStore store = new JournalMemoryStore(root)) {
      final long before = store.syncCount();
      for (int i = 0; i < 10; i++) {
        store.put(record("r" + i, tenantScope(), "durable " + i), "k" + i);
      }
      assertThat(store.syncCount() - before).isGreaterThanOrEqualTo(10L);
    }
  }

  @Test
  void theUnsyncedModeDoesNotForceAndIsThereforeNotDurable(@TempDir final Path root) {
    // The mode exists only so a benchmark can separate engine cost from disk cost. Asserting that
    // it
    // really does skip the sync keeps the benchmark honest about what it measured.
    try (JournalMemoryStore store = new JournalMemoryStore(root, 8L * 1024 * 1024, false)) {
      final long before = store.syncCount();
      for (int i = 0; i < 10; i++) {
        store.put(record("r" + i, tenantScope(), "not durable " + i), "k" + i);
      }
      assertThat(store.syncCount() - before).isZero();
    }
  }

  @Test
  void everyFieldSurvivesARestartIntact(@TempDir final Path root) {
    final MemoryRecord awkward = awkwardRecord("awkward", tenantScope());
    try (JournalMemoryStore first = new JournalMemoryStore(root)) {
      first.put(awkward, "k1");
    }
    try (JournalMemoryStore reopened = new JournalMemoryStore(root)) {
      assertThat(reopened.get(tenantScope(), awkward.id())).contains(awkward);
    }
  }

  @Test
  void aDeleteSurvivesARestartRatherThanTheRecordReappearing(@TempDir final Path root) {
    // The log is append-only, so a delete is a tombstone. If replay applied the write and missed
    // the
    // tombstone, deleted data would come back to life on every restart.
    try (JournalMemoryStore first = new JournalMemoryStore(root)) {
      first.put(record("r1", tenantScope(), "x"), "k1");
      first.delete(tenantScope(), MemoryRecordId.of("r1"));
    }
    try (JournalMemoryStore reopened = new JournalMemoryStore(root)) {
      assertThat(reopened.get(tenantScope(), MemoryRecordId.of("r1"))).isEmpty();
      assertThat(reopened.size()).isZero();
    }
  }

  @Test
  void aWriteKeyIsStillDeduplicatedAfterARestart(@TempDir final Path root) {
    try (JournalMemoryStore first = new JournalMemoryStore(root)) {
      first.put(record("r1", tenantScope(), "first"), "shared");
    }
    try (JournalMemoryStore reopened = new JournalMemoryStore(root)) {
      final var replay = reopened.put(record("r2", tenantScope(), "second"), "shared");
      assertThat(replay.created()).isFalse();
      assertThat(replay.record().id().value()).isEqualTo("r1");
    }
  }

  @Test
  void accessCountsSurviveACleanShutdown(@TempDir final Path root) {
    try (JournalMemoryStore first = new JournalMemoryStore(root)) {
      first.put(record("r1", tenantScope(), "x"), "k1");
      first.touch(tenantScope(), MemoryRecordId.of("r1"), T0.plusSeconds(10));
      first.touch(tenantScope(), MemoryRecordId.of("r1"), T0.plusSeconds(20));
      first.flush();
    }
    try (JournalMemoryStore reopened = new JournalMemoryStore(root)) {
      assertThat(reopened.get(tenantScope(), MemoryRecordId.of("r1")).orElseThrow().accessCount())
          .isEqualTo(2L);
    }
  }

  @Test
  void replayingATouchTwiceDoesNotDoubleCount(@TempDir final Path root) {
    // The touch entry carries an absolute count, not an increment. An incrementing entry would make
    // every restart inflate the usage-frequency ranking signal.
    try (JournalMemoryStore first = new JournalMemoryStore(root)) {
      first.put(record("r1", tenantScope(), "x"), "k1");
      first.touch(tenantScope(), MemoryRecordId.of("r1"), T0.plusSeconds(10));
    }
    try (JournalMemoryStore second = new JournalMemoryStore(root)) {
      assertThat(second.get(tenantScope(), MemoryRecordId.of("r1")).orElseThrow().accessCount())
          .isEqualTo(1L);
    }
    try (JournalMemoryStore third = new JournalMemoryStore(root)) {
      assertThat(third.get(tenantScope(), MemoryRecordId.of("r1")).orElseThrow().accessCount())
          .isEqualTo(1L);
    }
  }

  @Test
  void manyTenantsAllComeBack(@TempDir final Path root) {
    try (JournalMemoryStore first = new JournalMemoryStore(root)) {
      for (int i = 0; i < 12; i++) {
        final MemoryScope scope =
            MemoryScope.ofTenant(
                io.reliabilityai.gateway.canonical.identity.TenantScope.of("org", "t" + i));
        first.put(record("r" + i, scope, "body " + i), "k" + i);
      }
    }
    try (JournalMemoryStore reopened = new JournalMemoryStore(root)) {
      assertThat(reopened.partitionCount()).isEqualTo(12);
      assertThat(reopened.size()).isEqualTo(12);
    }
  }

  // ---- torn and corrupt writes
  // -----------------------------------------------------------------------

  @Test
  void aTornFinalEntryIsDiscardedAndEverythingBeforeItSurvives(@TempDir final Path root)
      throws IOException {
    // Power lost part-way through a write leaves a line with no terminator.
    try (JournalMemoryStore first = new JournalMemoryStore(root)) {
      first.put(record("r1", tenantScope(), "intact"), "k1");
      first.put(record("r2", tenantScope(), "also intact"), "k2");
    }
    final Path segment = onlySegment(root);
    Files.writeString(
        segment,
        Files.readString(segment, StandardCharsets.UTF_8) + "deadbeef:put|id=r3|partial",
        StandardCharsets.UTF_8);

    try (JournalMemoryStore reopened = new JournalMemoryStore(root)) {
      assertThat(reopened.size()).isEqualTo(2);
      assertThat(reopened.get(tenantScope(), MemoryRecordId.of("r3"))).isEmpty();
    }
  }

  @Test
  void aTornTailIsTruncatedSoTheNextAppendLandsAtAValidOffset(@TempDir final Path root)
      throws IOException {
    try (JournalMemoryStore first = new JournalMemoryStore(root)) {
      first.put(record("r1", tenantScope(), "intact"), "k1");
    }
    final Path segment = onlySegment(root);
    final long goodLength = Files.size(segment);
    try (RandomAccessFile raf = new RandomAccessFile(segment.toFile(), "rw")) {
      raf.seek(goodLength);
      raf.write("garbage-with-no-newline".getBytes(StandardCharsets.UTF_8));
    }

    try (JournalMemoryStore reopened = new JournalMemoryStore(root)) {
      assertThat(Files.size(segment)).isEqualTo(goodLength);
      reopened.put(record("r2", tenantScope(), "after recovery"), "k2");
    }
    try (JournalMemoryStore again = new JournalMemoryStore(root)) {
      assertThat(again.size()).isEqualTo(2);
    }
  }

  @Test
  void aCompleteLineWithAWrongChecksumIsDiscarded(@TempDir final Path root) throws IOException {
    // Rarer than a missing terminator, and the reason a length check alone is not enough: a
    // partially
    // written sector can happen to contain a newline, so the line looks complete and is not.
    try (JournalMemoryStore first = new JournalMemoryStore(root)) {
      first.put(record("r1", tenantScope(), "intact"), "k1");
      first.put(record("r2", tenantScope(), "corrupted next"), "k2");
    }
    final Path segment = onlySegment(root);
    final List<String> lines = Files.readAllLines(segment, StandardCharsets.UTF_8);
    // Line 0 is the header, 1 the first record, 2 the second. Corrupt the second's checksum.
    assertThat(lines).hasSize(3);
    final String corrupted = lines.get(2).replaceFirst("^[0-9a-f]+:", "00000000:");
    Files.writeString(
        segment,
        lines.get(0) + "\n" + lines.get(1) + "\n" + corrupted + "\n",
        StandardCharsets.UTF_8);

    try (JournalMemoryStore reopened = new JournalMemoryStore(root)) {
      assertThat(reopened.size()).isEqualTo(1);
      assertThat(reopened.get(tenantScope(), MemoryRecordId.of("r1"))).isPresent();
    }
  }

  @Test
  void aSegmentWithNoHeaderIsNotInterpreted(@TempDir final Path root) throws IOException {
    // Not written by this store, or its very first write was lost. Guessing which tenant its
    // entries
    // belong to would be worse than ignoring it.
    try (JournalMemoryStore first = new JournalMemoryStore(root)) {
      first.put(record("r1", tenantScope(), "x"), "k1");
    }
    final Path segment = onlySegment(root);
    final List<String> lines = Files.readAllLines(segment, StandardCharsets.UTF_8);
    Files.writeString(segment, lines.get(1) + "\n", StandardCharsets.UTF_8);

    try (JournalMemoryStore reopened = new JournalMemoryStore(root)) {
      assertThat(reopened.size()).isZero();
    }
  }

  @Test
  void anEmptySegmentIsHarmless(@TempDir final Path root) throws IOException {
    Files.createDirectories(root.resolve("acme-core-abcdef"));
    Files.writeString(root.resolve("acme-core-abcdef").resolve("seg-00000001.log"), "");
    try (JournalMemoryStore store = new JournalMemoryStore(root)) {
      assertThat(store.size()).isZero();
    }
  }

  @Test
  void aWellFramedEntryThisBuildCannotUnderstandStopsReplayRatherThanSkippingIt(
      @TempDir final Path root) throws IOException {
    // Skipping would apply later entries on top of a state that missed one — a silently wrong
    // store.
    try (JournalMemoryStore first = new JournalMemoryStore(root)) {
      first.put(record("r1", tenantScope(), "before"), "k1");
      first.put(record("r2", tenantScope(), "after"), "k2");
    }
    final Path segment = onlySegment(root);
    final List<String> lines = Files.readAllLines(segment, StandardCharsets.UTF_8);
    // Replace the first record's payload with a well-checksummed but unknown kind.
    final String payload = "sorcery|id=r9";
    final java.util.zip.CRC32 crc = new java.util.zip.CRC32();
    final byte[] bytes = payload.getBytes(StandardCharsets.UTF_8);
    crc.update(bytes, 0, bytes.length);
    Files.writeString(
        segment,
        lines.get(0)
            + "\n"
            + Long.toHexString(crc.getValue())
            + ":"
            + payload
            + "\n"
            + lines.get(2)
            + "\n",
        StandardCharsets.UTF_8);

    try (JournalMemoryStore reopened = new JournalMemoryStore(root)) {
      assertThat(reopened.size()).isZero();
    }
  }

  // ---- segments and compaction
  // -------------------------------------------------------------------------

  @Test
  void theLogRotatesIntoNewSegmentsAsItGrows(@TempDir final Path root) throws IOException {
    try (JournalMemoryStore small = new JournalMemoryStore(root, 2048L, true)) {
      for (int i = 0; i < 60; i++) {
        small.put(record("r" + i, tenantScope(), "a reasonably long body ".repeat(3) + i), "k" + i);
      }
      assertThat(allSegments(root).size()).isGreaterThan(1);
    }
  }

  @Test
  void aRotatedLogStillReplaysEverythingInOrder(@TempDir final Path root) {
    try (JournalMemoryStore small = new JournalMemoryStore(root, 2048L, true)) {
      for (int i = 0; i < 60; i++) {
        small.put(record("r" + i, tenantScope(), "body ".repeat(10) + i), "k" + i);
      }
    }
    try (JournalMemoryStore reopened = new JournalMemoryStore(root, 2048L, true)) {
      assertThat(reopened.size()).isEqualTo(60);
    }
  }

  @Test
  void compactionShrinksALogFullOfTouchesWithoutLosingRecords(@TempDir final Path root) {
    try (JournalMemoryStore store = new JournalMemoryStore(root)) {
      store.put(record("r1", tenantScope(), "x"), "k1");
      for (int i = 0; i < 500; i++) {
        store.touch(tenantScope(), MemoryRecordId.of("r1"), T0.plusSeconds(i));
      }
      final long before = store.diskBytes();
      final long after = store.compact();

      assertThat(after).isLessThan(before);
      assertThat(store.size()).isEqualTo(1);
      assertThat(store.get(tenantScope(), MemoryRecordId.of("r1"))).isPresent();
    }
  }

  @Test
  void compactionDropsTombstonedRecordsFromDisk(@TempDir final Path root) {
    try (JournalMemoryStore store = new JournalMemoryStore(root)) {
      for (int i = 0; i < 50; i++) {
        store.put(record("r" + i, tenantScope(), "body " + i), "k" + i);
      }
      for (int i = 0; i < 40; i++) {
        store.delete(tenantScope(), MemoryRecordId.of("r" + i));
      }
      final long before = store.diskBytes();
      store.compact();
      assertThat(store.diskBytes()).isLessThan(before);
      assertThat(store.size()).isEqualTo(10);
    }
  }

  @Test
  void aCompactedLogReplaysToTheSameState(@TempDir final Path root) {
    try (JournalMemoryStore store = new JournalMemoryStore(root)) {
      for (int i = 0; i < 20; i++) {
        store.put(record("r" + i, tenantScope(), "body " + i), "k" + i);
      }
      store.delete(tenantScope(), MemoryRecordId.of("r0"));
      store.touch(tenantScope(), MemoryRecordId.of("r1"), T0.plusSeconds(5));
      store.compact();
    }
    try (JournalMemoryStore reopened = new JournalMemoryStore(root)) {
      assertThat(reopened.size()).isEqualTo(19);
      assertThat(reopened.get(tenantScope(), MemoryRecordId.of("r0"))).isEmpty();
      assertThat(reopened.get(tenantScope(), MemoryRecordId.of("r1")).orElseThrow().accessCount())
          .isEqualTo(1L);
    }
  }

  @Test
  void compactionIsIdempotent(@TempDir final Path root) {
    try (JournalMemoryStore store = new JournalMemoryStore(root)) {
      for (int i = 0; i < 10; i++) {
        store.put(record("r" + i, tenantScope(), "body " + i), "k" + i);
      }
      final long once = store.compact();
      final long twice = store.compact();
      assertThat(twice).isEqualTo(once);
      assertThat(store.size()).isEqualTo(10);
    }
  }

  @Test
  void writesAfterCompactionStillSurviveARestart(@TempDir final Path root) {
    try (JournalMemoryStore store = new JournalMemoryStore(root)) {
      store.put(record("before", tenantScope(), "x"), "k1");
      store.compact();
      store.put(record("after", tenantScope(), "y"), "k2");
    }
    try (JournalMemoryStore reopened = new JournalMemoryStore(root)) {
      assertThat(reopened.size()).isEqualTo(2);
      assertThat(reopened.get(tenantScope(), MemoryRecordId.of("after"))).isPresent();
    }
  }

  // ---- snapshots
  // ------------------------------------------------------------------------------------------

  @Test
  void aSnapshotCapturesEveryPartition(@TempDir final Path root, @TempDir final Path snapshot) {
    try (JournalMemoryStore store = new JournalMemoryStore(root)) {
      store.put(record("mine", tenantScope(), "x"), "k1");
      store.put(record("theirs", MemoryScope.ofTenant(OTHER_TENANT), "y"), "k2");
      assertThat(store.snapshotTo(snapshot)).isEqualTo(2);
    }
  }

  @Test
  void aSnapshotCanBeOpenedAsAStoreInItsOwnRight(
      @TempDir final Path root, @TempDir final Path snapshot) {
    try (JournalMemoryStore store = new JournalMemoryStore(root)) {
      store.put(record("r1", tenantScope(), "captured"), "k1");
      store.snapshotTo(snapshot);
      // Diverge after the snapshot: the copy must not see this.
      store.put(record("r2", tenantScope(), "after the snapshot"), "k2");
    }
    try (JournalMemoryStore restored = new JournalMemoryStore(snapshot)) {
      assertThat(restored.size()).isEqualTo(1);
      assertThat(restored.get(tenantScope(), MemoryRecordId.of("r1"))).isPresent();
      assertThat(restored.get(tenantScope(), MemoryRecordId.of("r2"))).isEmpty();
    }
  }

  @Test
  void aSnapshotDoesNotDisturbTheLiveStore(@TempDir final Path root, @TempDir final Path snapshot) {
    try (JournalMemoryStore store = new JournalMemoryStore(root)) {
      store.put(record("r1", tenantScope(), "x"), "k1");
      store.snapshotTo(snapshot);
      store.put(record("r2", tenantScope(), "y"), "k2");
      assertThat(store.size()).isEqualTo(2);
    }
  }

  // ---- close
  // ------------------------------------------------------------------------------------------------

  @Test
  void aClosedStoreRefusesWorkRatherThanAnsweringFromStaleState(@TempDir final Path root) {
    final JournalMemoryStore store = new JournalMemoryStore(root);
    store.put(record("r1", tenantScope(), "x"), "k1");
    store.close();

    org.assertj.core.api.Assertions.assertThatThrownBy(
            () -> store.get(tenantScope(), MemoryRecordId.of("r1")))
        .isInstanceOf(
            io.reliabilityai.gateway.dataplane.memory.api.MemoryStoreUnavailableException.class);
  }

  @Test
  void closingTwiceIsHarmless(@TempDir final Path root) {
    final JournalMemoryStore store = new JournalMemoryStore(root);
    store.close();
    store.close();
  }

  @Test
  void aHostileTenantNameCannotEscapeTheStoreRoot(@TempDir final Path root) throws IOException {
    // Tenant names are caller-supplied strings. A traversal must not choose where the partition
    // lands.
    try (JournalMemoryStore store = new JournalMemoryStore(root)) {
      final MemoryScope hostile =
          MemoryScope.ofTenant(
              io.reliabilityai.gateway.canonical.identity.TenantScope.of("../..", "../etc"));
      store.put(record("r1", hostile, "x"), "k1");
      assertThat(onlySegment(root).toRealPath()).startsWith(root.toRealPath());
    }
  }

  @Test
  void twoTenantNamesThatSanitiseAlikeGetSeparatePartitions(@TempDir final Path root) {
    // "a/b" and "a-b" collapse to one directory name under sanitising alone; sharing a partition
    // would
    // be the exact cross-tenant failure the design exists to prevent, so the name carries a digest.
    try (JournalMemoryStore store = new JournalMemoryStore(root)) {
      final MemoryScope left =
          MemoryScope.ofTenant(
              io.reliabilityai.gateway.canonical.identity.TenantScope.of("org", "a/b"));
      final MemoryScope right =
          MemoryScope.ofTenant(
              io.reliabilityai.gateway.canonical.identity.TenantScope.of("org", "a-b"));
      store.put(record("l", left, "left"), "k1");
      store.put(record("r", right, "right"), "k2");

      assertThat(store.partitionCount()).isEqualTo(2);
      assertThat(store.search(scopeQuery(left, MemoryType.LONG_TERM))).hasSize(1);
      assertThat(store.search(scopeQuery(right, MemoryType.LONG_TERM))).hasSize(1);
    }
  }
}

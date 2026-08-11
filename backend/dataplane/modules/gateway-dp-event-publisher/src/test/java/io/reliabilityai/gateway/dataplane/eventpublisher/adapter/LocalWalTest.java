package io.reliabilityai.gateway.dataplane.eventpublisher.adapter;

import static org.assertj.core.api.Assertions.assertThat;

import io.reliabilityai.gateway.common.ContentFree;
import io.reliabilityai.gateway.dataplane.eventpublisher.api.BrokerRecord;
import io.reliabilityai.gateway.ports.EventPublisherPort.DeliveryClass;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Durability tests for the append-only {@link LocalWal}: recovery, replay, checksums, rotation. */
class LocalWalTest {

  private record Count(int value) implements ContentFree {}

  /** A real, deterministic 4-byte codec for the test payload (a genuine round-trip, not a stub). */
  private static final WalCodec CODEC =
      new WalCodec() {
        @Override
        public byte[] encode(final ContentFree payload) {
          return ByteBuffer.allocate(4).putInt(((Count) payload).value()).array();
        }

        @Override
        public ContentFree decode(final String topic, final byte[] bytes) {
          return new Count(ByteBuffer.wrap(bytes).getInt());
        }
      };

  private static BrokerRecord zl(final String eventId, final int value) {
    return new BrokerRecord(eventId, "topic.audit", DeliveryClass.ZL, new Count(value));
  }

  private static WalConfig cfg(final long segBytes, final WalSyncPolicy sync) {
    return new WalConfig(segBytes, sync);
  }

  private static List<String> replayIds(final LocalWal wal) {
    final List<String> ids = new ArrayList<>();
    wal.replayPending(r -> ids.add(r.eventId()));
    return ids;
  }

  @TempDir Path dir;

  @Test
  void appendMarkSentTracksPending() {
    try (LocalWal wal = new LocalWal(dir, cfg(4096, WalSyncPolicy.ALWAYS), CODEC)) {
      wal.append(zl("node-ZL-1", 10));
      wal.append(zl("node-ZL-2", 20));
      assertThat(wal.pendingCount()).isEqualTo(2);
      wal.markSent("node-ZL-1");
      assertThat(wal.pendingCount()).isEqualTo(1);
      assertThat(replayIds(wal)).containsExactly("node-ZL-2");
    }
  }

  @Test
  void highWaterMarkReflectsMaxSequencePerNode() {
    try (LocalWal wal = new LocalWal(dir, cfg(4096, WalSyncPolicy.ALWAYS), CODEC)) {
      wal.append(zl("node-ZL-5", 1));
      wal.append(zl("node-ZL-9", 2));
      wal.append(zl("node-ZL-3", 3));
      assertThat(wal.highWaterMark("node")).isEqualTo(9L);
      assertThat(wal.highWaterMark("other")).isZero();
    }
  }

  @Test
  void crashRecoveryReplaysUnsentInAppendOrder() {
    try (LocalWal wal = new LocalWal(dir, cfg(4096, WalSyncPolicy.ALWAYS), CODEC)) {
      wal.append(zl("node-ZL-1", 11));
      wal.append(zl("node-ZL-2", 22));
      wal.append(zl("node-ZL-3", 33));
      wal.markSent("node-ZL-2"); // the middle one is delivered
    } // "crash" — no explicit close beyond try-with-resources

    try (LocalWal recovered = new LocalWal(dir, cfg(4096, WalSyncPolicy.ALWAYS), CODEC)) {
      assertThat(recovered.pendingCount()).isEqualTo(2);
      final List<Integer> values = new ArrayList<>();
      recovered.replayPending(r -> values.add(((Count) r.payload()).value()));
      assertThat(replayIds(recovered)).containsExactly("node-ZL-1", "node-ZL-3");
      assertThat(values).containsExactly(11, 33); // payload round-trips via the codec, in order
      assertThat(recovered.highWaterMark("node")).isEqualTo(3L);
    }
  }

  @Test
  void tornTailFrameIsTruncatedAndRecoveryContinues() throws IOException {
    try (LocalWal wal = new LocalWal(dir, cfg(4096, WalSyncPolicy.ALWAYS), CODEC)) {
      wal.append(zl("node-ZL-1", 1));
      wal.append(zl("node-ZL-2", 2));
    }
    // Simulate a torn write: append garbage bytes to the tail of the active segment.
    final Path segment =
        Files.list(dir)
            .filter(p -> p.getFileName().toString().endsWith(".log"))
            .sorted()
            .reduce((a, b) -> b)
            .orElseThrow();
    Files.write(segment, new byte[] {9, 8, 7, 6, 5, 4}, StandardOpenOption.APPEND);

    try (LocalWal recovered = new LocalWal(dir, cfg(4096, WalSyncPolicy.ALWAYS), CODEC)) {
      assertThat(recovered.pendingCount()).isEqualTo(2); // garbage tail ignored, good frames intact
      recovered.append(zl("node-ZL-3", 3)); // writing continues correctly after truncation
    }
    try (LocalWal reopened = new LocalWal(dir, cfg(4096, WalSyncPolicy.ALWAYS), CODEC)) {
      assertThat(replayIds(reopened)).containsExactly("node-ZL-1", "node-ZL-2", "node-ZL-3");
    }
  }

  @Test
  void rotationCreatesMultipleSegments() {
    try (LocalWal wal = new LocalWal(dir, cfg(64, WalSyncPolicy.ALWAYS), CODEC)) {
      for (int i = 1; i <= 6; i++) {
        wal.append(zl("node-ZL-" + i, i));
      }
      assertThat(wal.segmentCount()).isGreaterThan(1);
      assertThat(wal.pendingCount()).isEqualTo(6);
    }
  }

  @Test
  void segmentNamesAreLocaleIndependent() throws IOException {
    // %d renders through the default locale's zero digit. Under a locale whose numbering system is
    // not latn, segment names came out in Arabic-Indic digits. A node that never changes locale
    // survives that, because Integer.parseInt accepts those digits too -- which is precisely why
    // it went unnoticed. The failure needs a locale change between runs: listSegments() orders by
    // filename, non-ASCII digits sort after ASCII ones, so recovery would pick the wrong file as
    // the highest-indexed segment and append over a segment that is not the newest.
    final Locale original = Locale.getDefault();
    try {
      Locale.setDefault(Locale.forLanguageTag("ar-SA-u-nu-arab"));

      try (LocalWal wal = new LocalWal(dir, cfg(64, WalSyncPolicy.ALWAYS), CODEC)) {
        for (int i = 1; i <= 6; i++) {
          wal.append(zl("node-ZL-" + i, i));
        }
        assertThat(wal.segmentCount()).isGreaterThan(1);
      }

      try (var listing = Files.list(dir)) {
        final List<String> names = listing.map(p -> p.getFileName().toString()).sorted().toList();
        assertThat(names).isNotEmpty();
        assertThat(names)
            .allMatch(n -> n.matches("wal-[0-9]{6}\\.log"), "ASCII-digit segment names");
      }

      // And the WAL still recovers everything it wrote under that locale.
      try (LocalWal reopened = new LocalWal(dir, cfg(64, WalSyncPolicy.ALWAYS), CODEC)) {
        assertThat(replayIds(reopened)).hasSize(6);
      }
    } finally {
      Locale.setDefault(original);
    }
  }

  @Test
  void compactionDeletesAcknowledgedOldSegmentsAndPreservesHighWaterMark() {
    try (LocalWal wal = new LocalWal(dir, cfg(64, WalSyncPolicy.ALWAYS), CODEC)) {
      wal.append(zl("node-ZL-1", 1));
      wal.append(zl("node-ZL-2", 2)); // fills first segment
      wal.append(zl("node-ZL-3", 3)); // rolls to a later segment
      wal.markSent("node-ZL-1");
      wal.markSent("node-ZL-2"); // first segment now fully acknowledged
      final int before = wal.segmentCount();

      final int deleted = wal.compact();
      assertThat(deleted).isGreaterThanOrEqualTo(1);
      assertThat(wal.segmentCount()).isLessThan(before);
      assertThat(wal.pendingCount()).isEqualTo(1); // e3 still pending, untouched
      assertThat(wal.highWaterMark("node")).isEqualTo(3L);
    }
    // The checkpoint written before deletion preserves the id counter across a restart.
    try (LocalWal reopened = new LocalWal(dir, cfg(64, WalSyncPolicy.ALWAYS), CODEC)) {
      assertThat(reopened.highWaterMark("node")).isEqualTo(3L);
      assertThat(replayIds(reopened)).containsExactly("node-ZL-3");
    }
  }

  @Test
  void nonSyncingPolicyStillRecoversAcrossReopen() {
    try (LocalWal wal = new LocalWal(dir, cfg(4096, WalSyncPolicy.NONE), CODEC)) {
      wal.append(zl("node-ZL-1", 1));
      wal.append(zl("node-ZL-2", 2));
    }
    try (LocalWal recovered = new LocalWal(dir, cfg(4096, WalSyncPolicy.NONE), CODEC)) {
      assertThat(recovered.pendingCount()).isEqualTo(2);
    }
  }
}

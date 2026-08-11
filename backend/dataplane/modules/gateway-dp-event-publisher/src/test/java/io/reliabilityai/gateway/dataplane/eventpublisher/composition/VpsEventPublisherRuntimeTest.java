package io.reliabilityai.gateway.dataplane.eventpublisher.composition;

import static org.assertj.core.api.Assertions.assertThat;

import io.reliabilityai.gateway.common.ContentFree;
import io.reliabilityai.gateway.dataplane.eventpublisher.adapter.InMemoryBroker;
import io.reliabilityai.gateway.dataplane.eventpublisher.adapter.LocalFileDeadLetter;
import io.reliabilityai.gateway.dataplane.eventpublisher.adapter.LocalWal;
import io.reliabilityai.gateway.dataplane.eventpublisher.adapter.WalCodec;
import io.reliabilityai.gateway.dataplane.eventpublisher.adapter.WalConfig;
import io.reliabilityai.gateway.dataplane.eventpublisher.adapter.WalSyncPolicy;
import io.reliabilityai.gateway.dataplane.eventpublisher.api.BrokerRecord;
import io.reliabilityai.gateway.dataplane.eventpublisher.api.RetryBackoff;
import io.reliabilityai.gateway.dataplane.eventpublisher.api.RetryPolicy;
import io.reliabilityai.gateway.ports.EventPublisherPort;
import io.reliabilityai.gateway.ports.EventPublisherPort.DeliveryClass;
import java.nio.ByteBuffer;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** End-to-end single-VPS wiring: Publisher → WalPort → BrokerPort, with crash replay. */
class VpsEventPublisherRuntimeTest {

  private record Count(int value) implements ContentFree {}

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

  private static LocalWal wal(final Path dir) {
    return new LocalWal(dir, new WalConfig(4096, WalSyncPolicy.ALWAYS), CODEC);
  }

  private static InMemoryBroker broker(final Consumer<BrokerRecord> subscriber) {
    return new InMemoryBroker(64, Duration.ofSeconds(2), subscriber);
  }

  @TempDir Path dir;
  @TempDir Path dlqDir;

  @Test
  void zeroLossPublishDeliversToBrokerAndMarksSent() {
    final List<String> delivered = new CopyOnWriteArrayList<>();
    final LocalWal wal = wal(dir);
    final InMemoryBroker broker = broker(r -> delivered.add(r.eventId()));
    final LocalFileDeadLetter dlq = new LocalFileDeadLetter(dlqDir.resolve("dlq.log"));
    final VpsEventPublisherRuntime runtime =
        VpsEventPublisherRuntime.start(
            broker, wal, dlq, new RetryPolicy(3), RetryBackoff.NONE, "node");

    runtime.publisher().publish("topic.audit", DeliveryClass.ZL, new Count(42));
    runtime.close(); // graceful drain

    assertThat(delivered).containsExactly("node-ZL-1");
    assertThat(wal.pendingCount()).isZero(); // appended then marked sent on confirmed delivery
  }

  @Test
  void crashLeftPendingRecordIsReplayedThenNewIdsDoNotCollide() {
    // Pre-crash: a ZL record was appended to the WAL but the process died before it was marked
    // sent.
    try (LocalWal preCrash = wal(dir)) {
      preCrash.append(new BrokerRecord("node-ZL-1", "topic.audit", DeliveryClass.ZL, new Count(7)));
      assertThat(preCrash.pendingCount()).isEqualTo(1);
    }

    // Restart: a fresh runtime recovers, replays the pending record, then serves new traffic.
    final List<String> delivered = new CopyOnWriteArrayList<>();
    final LocalWal wal = wal(dir);
    final InMemoryBroker broker = broker(r -> delivered.add(r.eventId()));
    final LocalFileDeadLetter dlq = new LocalFileDeadLetter(dlqDir.resolve("dlq.log"));
    final VpsEventPublisherRuntime runtime =
        VpsEventPublisherRuntime.start(
            broker, wal, dlq, new RetryPolicy(3), RetryBackoff.NONE, "node");

    // A brand-new event after recovery: its id must be seeded ABOVE the pre-crash high-water mark.
    runtime.publisher().publish("topic.audit", DeliveryClass.ZL, new Count(8));
    runtime.close();

    assertThat(delivered).containsExactly("node-ZL-1", "node-ZL-2"); // replayed, then the new event
    assertThat(wal.pendingCount()).isZero(); // both marked sent
  }

  @Test
  void reliableAndBestEffortPublishDeliverWithoutWalPending() {
    final List<String> delivered = new CopyOnWriteArrayList<>();
    final LocalWal wal = wal(dir);
    final InMemoryBroker broker = broker(r -> delivered.add(r.eventId()));
    final LocalFileDeadLetter dlq = new LocalFileDeadLetter(dlqDir.resolve("dlq.log"));
    final EventPublisherPort publisher =
        VpsEventPublisherRuntime.start(
                broker, wal, dlq, new RetryPolicy(3), RetryBackoff.NONE, "node")
            .publisher();

    publisher.publish("topic.metric", DeliveryClass.RL, new Count(1));
    publisher.publish("topic.metric", DeliveryClass.BE, new Count(2));
    broker.close();

    assertThat(delivered).containsExactly("node-RL-1", "node-BE-2");
    assertThat(wal.pendingCount()).isZero(); // RL/BE never touch the WAL (only ZL does)
    wal.close();
    dlq.close();
  }
}

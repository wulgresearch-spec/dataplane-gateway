package io.reliabilityai.gateway.dataplane.eventpublisher.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.reliabilityai.gateway.common.ContentFree;
import io.reliabilityai.gateway.dataplane.eventpublisher.api.BrokerException;
import io.reliabilityai.gateway.dataplane.eventpublisher.api.BrokerPort;
import io.reliabilityai.gateway.dataplane.eventpublisher.api.BrokerRecord;
import io.reliabilityai.gateway.dataplane.eventpublisher.api.DeadLetterPort;
import io.reliabilityai.gateway.dataplane.eventpublisher.api.RetryPolicy;
import io.reliabilityai.gateway.dataplane.eventpublisher.api.WalPort;
import io.reliabilityai.gateway.ports.EventPublisherPort.DeliveryClass;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Resilience tests for the event publisher (Doc 07): idempotency, WAL-first ZL, retry, DLQ, BE
 * drop.
 */
class ResilientEventPublisherTest {

  private static final String TOPIC = "rfaig.metering.usage.v1";

  private record TestEvent() implements ContentFree {}

  private final FakeBroker broker = new FakeBroker();
  private final FakeWal wal = new FakeWal();
  private final FakeDlq dlq = new FakeDlq();

  private final List<Integer> backoffs = new ArrayList<>();

  private ResilientEventPublisher publisher(final int maxAttempts) {
    return new ResilientEventPublisher(
        broker, wal, dlq, new RetryPolicy(maxAttempts), backoffs::add, "node-a");
  }

  @Test
  void zlWritesWalBeforeSendThenMarksSent() {
    publisher(3).publish(TOPIC, DeliveryClass.ZL, new TestEvent());
    assertThat(broker.eventIdsSeen).containsExactly("node-a-ZL-1");
    assertThat(wal.appended).containsExactly("node-a-ZL-1");
    assertThat(wal.markedSent).containsExactly("node-a-ZL-1");
    assertThat(wal.appendBeforeSend)
        .isTrue(); // durability precedes the broker send (RPO=0 boundary)
    assertThat(dlq.deadLettered).isEmpty();
  }

  @Test
  void retriesTransientFailuresWithStableEventId() {
    broker.transientFailsRemaining = 2;
    publisher(3).publish(TOPIC, DeliveryClass.ZL, new TestEvent());
    assertThat(broker.calls).isEqualTo(3);
    assertThat(broker.eventIdsSeen)
        .containsExactly("node-a-ZL-1", "node-a-ZL-1", "node-a-ZL-1"); // stable id
    assertThat(wal.markedSent).containsExactly("node-a-ZL-1");
  }

  @Test
  void permanentFailureFailsFastAndDeadLettersZl() {
    broker.permanentFail = true;
    publisher(5).publish(TOPIC, DeliveryClass.ZL, new TestEvent());
    assertThat(broker.calls).isEqualTo(1); // no retry storm on a permanent fault
    assertThat(wal.markedSent).isEmpty(); // NOT marked sent → remains in WAL for replay (RPO=0)
    assertThat(dlq.deadLettered).hasSize(1);
  }

  @Test
  void exhaustedRetriesDeadLetterReliable() {
    broker.alwaysTransientFail = true;
    publisher(3).publish(TOPIC, DeliveryClass.RL, new TestEvent());
    assertThat(broker.calls).isEqualTo(3);
    assertThat(dlq.deadLettered).hasSize(1);
  }

  @Test
  void bestEffortDropsOnFailureNeverDeadLetters() {
    broker.alwaysTransientFail = true;
    publisher(2).publish(TOPIC, DeliveryClass.BE, new TestEvent());
    assertThat(broker.calls).isEqualTo(2);
    assertThat(dlq.deadLettered).isEmpty(); // BE carries no decision/audit event → drop
    assertThat(wal.appended).isEmpty(); // BE is not written to the ZL WAL
  }

  @Test
  void eventIdsAreDeterministicAndUnique() {
    final var pub = publisher(1);
    pub.publish(TOPIC, DeliveryClass.RL, new TestEvent());
    pub.publish(TOPIC, DeliveryClass.RL, new TestEvent());
    assertThat(broker.eventIdsSeen).containsExactly("node-a-RL-1", "node-a-RL-2");
  }

  @Test
  void seedsEventIdAboveWalHighWaterMarkAfterRestart() {
    // Simulate a restart: the WAL durably recorded ids up to node-a-7 before the crash. New ids
    // must
    // resume above that so they never collide with an already-durable event (silent-loss guard,
    // §6).
    wal.highWaterMark = 7;
    publisher(1).publish(TOPIC, DeliveryClass.ZL, new TestEvent());
    assertThat(broker.eventIdsSeen).containsExactly("node-a-ZL-8");
  }

  @Test
  void markSentFailureDoesNotDeadLetterADeliveredRecord() {
    // Delivered successfully, but the WAL mark-sent fails: the record must NOT be dead-lettered or
    // re-sent (that would duplicate a delivered event). It stays for idempotent replay.
    wal.failMarkSent = true;
    publisher(3).publish(TOPIC, DeliveryClass.ZL, new TestEvent());
    assertThat(broker.calls).isEqualTo(1); // delivered once
    assertThat(dlq.deadLettered).isEmpty(); // no spurious dead-letter of a delivered record
  }

  @Test
  void appliesBackoffBetweenRetriesNotBeforeTheFirstAttempt() {
    broker.transientFailsRemaining = 2;
    publisher(3).publish(TOPIC, DeliveryClass.ZL, new TestEvent());
    assertThat(backoffs).containsExactly(2, 3); // backoff before attempts 2 and 3, never before 1
  }

  @Test
  void rejectsInvalidArguments() {
    final var pub = publisher(1);
    assertThatThrownBy(() -> pub.publish(" ", DeliveryClass.ZL, new TestEvent()))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> pub.publish(TOPIC, null, new TestEvent()))
        .isInstanceOf(NullPointerException.class);
    assertThatThrownBy(() -> new RetryPolicy(0)).isInstanceOf(IllegalArgumentException.class);
  }

  private static final class FakeBroker implements BrokerPort {
    private int calls;
    private int transientFailsRemaining;
    private boolean permanentFail;
    private boolean alwaysTransientFail;
    private final List<String> eventIdsSeen = new ArrayList<>();

    @Override
    public void send(final BrokerRecord record) {
      calls++;
      eventIdsSeen.add(record.eventId());
      if (permanentFail) {
        throw new BrokerException(false, "permanent");
      }
      if (alwaysTransientFail) {
        throw new BrokerException(true, "transient");
      }
      if (transientFailsRemaining > 0) {
        transientFailsRemaining--;
        throw new BrokerException(true, "transient");
      }
    }
  }

  private final class FakeWal implements WalPort {
    private final List<String> appended = new ArrayList<>();
    private final List<String> markedSent = new ArrayList<>();
    private boolean appendBeforeSend;
    private long highWaterMark;
    private boolean failMarkSent;

    @Override
    public void append(final BrokerRecord record) {
      appended.add(record.eventId());
      appendBeforeSend = broker.calls == 0; // WAL append precedes the first broker send
    }

    @Override
    public void markSent(final String eventId) {
      if (failMarkSent) {
        throw new IllegalStateException("wal mark-sent failed");
      }
      markedSent.add(eventId);
    }

    @Override
    public long highWaterMark(final String nodeId) {
      return highWaterMark;
    }
  }

  private static final class FakeDlq implements DeadLetterPort {
    private final List<String> deadLettered = new ArrayList<>();

    @Override
    public void deadLetter(final BrokerRecord record, final String reason) {
      deadLettered.add(record.eventId() + ":" + reason);
    }
  }
}

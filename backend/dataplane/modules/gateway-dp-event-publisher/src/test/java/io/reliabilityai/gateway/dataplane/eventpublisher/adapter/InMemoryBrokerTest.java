package io.reliabilityai.gateway.dataplane.eventpublisher.adapter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.reliabilityai.gateway.common.ContentFree;
import io.reliabilityai.gateway.dataplane.eventpublisher.api.BrokerException;
import io.reliabilityai.gateway.dataplane.eventpublisher.api.BrokerRecord;
import io.reliabilityai.gateway.ports.EventPublisherPort.DeliveryClass;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import org.junit.jupiter.api.Test;

/**
 * Concurrency tests for the in-process {@link InMemoryBroker}: FIFO, backpressure, graceful drain.
 */
class InMemoryBrokerTest {

  private record Marker(int n) implements ContentFree {}

  private static BrokerRecord record(final String eventId) {
    return new BrokerRecord(eventId, "topic.x", DeliveryClass.RL, new Marker(1));
  }

  @Test
  void deliversEveryRecordInSendOrder() {
    final List<String> delivered = new CopyOnWriteArrayList<>();
    final InMemoryBroker broker =
        new InMemoryBroker(16, Duration.ofSeconds(2), r -> delivered.add(r.eventId()));
    broker.send(record("e-1"));
    broker.send(record("e-2"));
    broker.send(record("e-3"));
    broker.close(); // drains before returning
    assertThat(delivered).containsExactly("e-1", "e-2", "e-3");
    assertThat(broker.deliveredCount()).isEqualTo(3);
  }

  @Test
  void gracefulShutdownLosesNoAcceptedRecord() {
    final List<String> delivered = new CopyOnWriteArrayList<>();
    final InMemoryBroker broker =
        new InMemoryBroker(8, Duration.ofSeconds(5), r -> delivered.add(r.eventId()));
    for (int i = 0; i < 500; i++) {
      broker.send(record("e-" + i));
    }
    broker.close();
    assertThat(delivered).hasSize(500);
  }

  @Test
  void backpressureFailsClosedRetryableWhenBounded() throws Exception {
    final CountDownLatch block = new CountDownLatch(1);
    final InMemoryBroker broker =
        new InMemoryBroker(
            2,
            Duration.ofMillis(100),
            r -> {
              try {
                block.await();
              } catch (final InterruptedException e) {
                Thread.currentThread().interrupt();
              }
            });
    BrokerException thrown = null;
    for (int i = 0; i < 10 && thrown == null; i++) {
      try {
        broker.send(record("e-" + i));
      } catch (final BrokerException e) {
        thrown = e;
      }
    }
    assertThat(thrown).isNotNull();
    assertThat(thrown.isRetryable()).isTrue(); // bounded backpressure → retryable, never OOM
    block.countDown();
    broker.close();
  }

  @Test
  void sendAfterCloseFailsClosedPermanent() {
    final InMemoryBroker broker = new InMemoryBroker(4, Duration.ofSeconds(1), r -> {});
    broker.close();
    assertThatThrownBy(() -> broker.send(record("e-1")))
        .isInstanceOf(BrokerException.class)
        .extracting(e -> ((BrokerException) e).isRetryable())
        .isEqualTo(false); // closed is permanent — no retry into a dead broker
  }

  @Test
  void subscriberFaultNeverCrashesBrokerAndIsCounted() {
    final InMemoryBroker broker =
        new InMemoryBroker(
            4,
            Duration.ofSeconds(2),
            r -> {
              throw new RuntimeException("consumer boom");
            });
    broker.send(record("e-1"));
    broker.close();
    assertThat(broker.deliveryErrorCount()).isEqualTo(1);
    assertThat(broker.deliveredCount()).isZero();
  }
}

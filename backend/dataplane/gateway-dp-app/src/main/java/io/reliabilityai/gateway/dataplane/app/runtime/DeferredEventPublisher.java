package io.reliabilityai.gateway.dataplane.app.runtime;

import io.reliabilityai.gateway.common.ContentFree;
import io.reliabilityai.gateway.common.Preconditions;
import io.reliabilityai.gateway.ports.EventPublisherPort;

/**
 * A composition-root indirection that lets modules be constructed before the live publisher exists.
 *
 * <p>The publisher only becomes available after WAL replay has finished (replay must not interleave
 * with new traffic, or a replayed record could be overtaken by a fresh one). Yet metering, cost and
 * secrets auditing all need an {@link EventPublisherPort} at construction time. This handle closes
 * that ordering gap without a container and without mutable module state: it is wired in during
 * {@code CREATE_RUNTIME} and bound to the real publisher at {@code START_BROKER}.
 *
 * <p><b>Fail-closed:</b> publishing before the bind, or after {@link #unbind()} during shutdown,
 * throws rather than silently dropping — an accounting or audit event must never be lost to a
 * lifecycle race.
 */
final class DeferredEventPublisher implements EventPublisherPort {

  private volatile EventPublisherPort delegate;

  @Override
  public <T extends ContentFree> void publish(
      final String topic, final DeliveryClass deliveryClass, final T payload) {
    final EventPublisherPort target = delegate;
    if (target == null) {
      throw new IllegalStateException(
          "event publisher not accepting: runtime is not ready (topic=" + topic + ")");
    }
    target.publish(topic, deliveryClass, payload);
  }

  /**
   * Binds the live publisher once the broker is running and replay is complete.
   *
   * @param publisher the live event publisher
   */
  void bind(final EventPublisherPort publisher) {
    delegate = Preconditions.requireNonNull(publisher, "publisher");
  }

  /** Stops accepting new events (first step of shutdown, before the broker is drained). */
  void unbind() {
    delegate = null;
  }

  /**
   * Whether the handle is currently accepting events.
   *
   * @return {@code true} once bound and before unbind
   */
  boolean isAccepting() {
    return delegate != null;
  }
}

package io.reliabilityai.gateway.dataplane.eventpublisher.api;

import io.reliabilityai.gateway.common.ContentFree;
import io.reliabilityai.gateway.common.Preconditions;
import io.reliabilityai.gateway.ports.EventPublisherPort.DeliveryClass;

/**
 * An immutable record queued for publication on the event fabric (Doc 07 §6). The {@code eventId}
 * is the idempotency/dedup key — stable across retries so at-least-once delivery is de-duplicated
 * at the consumer (Doc 07 §13). The payload is content-free (Doc 14 §7.1); serialization to the
 * wire format (Avro, Doc 07 §9) is the {@link BrokerPort} adapter's responsibility.
 *
 * @param eventId the stable idempotency/dedup key
 * @param topic the frozen topic name (Doc 06 §23)
 * @param deliveryClass the delivery class (ZL/RL/BE)
 * @param payload the content-free event payload
 */
public record BrokerRecord(
    String eventId, String topic, DeliveryClass deliveryClass, ContentFree payload) {

  /** Compact constructor validating required fields. */
  public BrokerRecord {
    Preconditions.requireNonBlank(eventId, "eventId");
    Preconditions.requireNonBlank(topic, "topic");
    Preconditions.requireNonNull(deliveryClass, "deliveryClass");
    Preconditions.requireNonNull(payload, "payload");
  }
}

package io.reliabilityai.gateway.dataplane.eventpublisher.api;

/**
 * The broker-communication seam (Doc 07, AD-009). Sends one record to the Kafka-class backbone with
 * idempotent-producer semantics (Doc 07 ZL contract). This is the <b>only</b> operation that talks
 * to the broker; no production implementation exists in this repository because it requires a real
 * Kafka-class cluster (repl>=3 / acks=all / minISR>=2) — it must not be faked. Implementations map
 * every provider exception to a typed {@link BrokerException} and never throw a raw client
 * exception.
 */
public interface BrokerPort {

  /**
   * Sends the record to the backbone.
   *
   * @param record the record to publish
   * @throws BrokerException fail-closed on any broker fault (retryable or permanent)
   */
  void send(BrokerRecord record);
}

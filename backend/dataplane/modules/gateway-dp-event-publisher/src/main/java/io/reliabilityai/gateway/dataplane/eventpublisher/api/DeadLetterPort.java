package io.reliabilityai.gateway.dataplane.eventpublisher.api;

/**
 * The dead-letter seam (Doc 07). A record that exhausts retries or fails permanently (for RL
 * delivery) is routed here for out-of-band handling; ZL records additionally remain in the WAL for
 * replay. The reason is content-free. No production implementation exists here (a durable DLQ topic
 * is infra).
 */
public interface DeadLetterPort {

  /**
   * Routes a record to the dead-letter queue with a content-free reason.
   *
   * @param record the failed record
   * @param reason the content-free failure reason
   */
  void deadLetter(BrokerRecord record, String reason);
}

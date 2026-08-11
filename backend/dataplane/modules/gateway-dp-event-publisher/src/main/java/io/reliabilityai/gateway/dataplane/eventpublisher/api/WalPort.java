package io.reliabilityai.gateway.dataplane.eventpublisher.api;

/**
 * The durable write-ahead-log seam for zero-loss delivery (Doc 07 EV-D3, Doc 08 DA-D1). A ZL record
 * is appended <b>before</b> the broker send and marked sent only on confirmed delivery; on
 * crash/failure it is replayed from the WAL, giving RPO=0. No production implementation exists here
 * because it requires a durable node-local store — it must not be faked. Implementations are
 * crash-safe and idempotent per {@code eventId}.
 */
public interface WalPort {

  /**
   * Durably appends a record before the broker send (Doc 07 EV-D3).
   *
   * @param record the record to persist
   */
  void append(BrokerRecord record);

  /**
   * Marks a record as confirmed-delivered so it is no longer replayed.
   *
   * @param eventId the delivered record's id
   */
  void markSent(String eventId);

  /**
   * The highest event sequence number durably recorded for this node (Doc 07 §6 — the event id is
   * the idempotency anchor and must be globally unique). Read once at startup to seed the in-memory
   * monotonic counter <b>above</b> any pre-crash id, so a restart never re-issues an id that a
   * different, already-durable event used — which would make the idempotent consumer drop a
   * genuinely new event as a duplicate (silent loss on a zero-loss stream). Returns {@code 0} for a
   * fresh node.
   *
   * @param nodeId the stable per-node identifier
   * @return the highest durably-recorded sequence number for the node ({@code >= 0})
   */
  long highWaterMark(String nodeId);
}

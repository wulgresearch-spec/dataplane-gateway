package io.reliabilityai.gateway.dataplane.eventpublisher.application;

import io.reliabilityai.gateway.common.ContentFree;
import io.reliabilityai.gateway.common.Preconditions;
import io.reliabilityai.gateway.dataplane.eventpublisher.api.BrokerPort;
import io.reliabilityai.gateway.dataplane.eventpublisher.api.BrokerRecord;
import io.reliabilityai.gateway.dataplane.eventpublisher.api.DeadLetterPort;
import io.reliabilityai.gateway.dataplane.eventpublisher.api.RetryBackoff;
import io.reliabilityai.gateway.dataplane.eventpublisher.api.RetryPolicy;
import io.reliabilityai.gateway.dataplane.eventpublisher.api.WalPort;
import io.reliabilityai.gateway.ports.EventPublisherPort;
import java.util.concurrent.atomic.AtomicLong;

/**
 * The production {@link EventPublisherPort} (Doc 07). Assigns each event a stable idempotency id
 * ({@code nodeId + '-' + monotonic sequence} — deterministic, no {@code UUID}/RNG, Doc 11 R-063)
 * that is reused across retries so at-least-once delivery de-duplicates at the consumer. For
 * <b>zero-loss (ZL)</b> it appends to the durable WAL <b>before</b> the broker send and marks sent
 * only on confirmed delivery (Doc 07 EV-D3, RPO=0 boundary); a failed ZL send remains in the WAL
 * for replay and is dead-lettered for visibility. <b>Reliable (RL)</b> retries then dead-letters on
 * exhaustion. <b>Best-effort (BE)</b> retries then silently drops (never dead-letters a
 * decision/audit event — BE carries none, Doc 07 §11). Bounded retry with fault classification
 * prevents retry storms.
 *
 * <p>Thread-safe / virtual-thread-safe: only an {@link AtomicLong} sequence is shared; the
 * WAL/broker/ DLQ ports own their own concurrency. Publication is side-effect-free toward runtime
 * decisions (OT-INV) and content-free.
 */
public final class ResilientEventPublisher implements EventPublisherPort {

  private final BrokerPort broker;
  private final WalPort wal;
  private final DeadLetterPort deadLetter;
  private final RetryPolicy retryPolicy;
  private final RetryBackoff retryBackoff;
  private final String nodeId;
  private final AtomicLong sequence;

  /**
   * Creates the publisher.
   *
   * @param broker the broker send seam (Doc 07)
   * @param wal the durable WAL for zero-loss (Doc 07 EV-D3)
   * @param deadLetter the dead-letter seam
   * @param retryPolicy the bounded retry policy
   * @param retryBackoff the between-attempt backoff (Doc 07 §14); use {@link RetryBackoff#NONE} for
   *     none
   * @param nodeId a stable per-node identifier (for deterministic, unique event ids)
   */
  public ResilientEventPublisher(
      final BrokerPort broker,
      final WalPort wal,
      final DeadLetterPort deadLetter,
      final RetryPolicy retryPolicy,
      final RetryBackoff retryBackoff,
      final String nodeId) {
    this.broker = Preconditions.requireNonNull(broker, "broker");
    this.wal = Preconditions.requireNonNull(wal, "wal");
    this.deadLetter = Preconditions.requireNonNull(deadLetter, "deadLetter");
    this.retryPolicy = Preconditions.requireNonNull(retryPolicy, "retryPolicy");
    this.retryBackoff = Preconditions.requireNonNull(retryBackoff, "retryBackoff");
    this.nodeId = Preconditions.requireNonBlank(nodeId, "nodeId");
    // Seed the monotonic counter above any pre-crash id (Doc 07 §6): after a restart the WAL's
    // durable high-water mark ensures new ids never collide with already-durable events (no silent
    // loss via consumer-side dedup of a genuinely-new event).
    final long highWaterMark = wal.highWaterMark(this.nodeId);
    if (highWaterMark < 0) {
      throw new IllegalStateException("WAL high-water mark must be non-negative");
    }
    this.sequence = new AtomicLong(highWaterMark);
  }

  @Override
  public <T extends ContentFree> void publish(
      final String topic, final DeliveryClass deliveryClass, final T payload) {
    Preconditions.requireNonBlank(topic, "topic");
    Preconditions.requireNonNull(deliveryClass, "deliveryClass");
    Preconditions.requireNonNull(payload, "payload");

    // The event id is namespaced by delivery class (Doc 07 §6): a ZL id can never collide with an
    // RL/BE id, so the WAL-seeded ZL counter's cross-restart uniqueness holds even though RL/BE ids
    // are not durably tracked (their weaker, non-RPO=0 semantics tolerate a rare cross-restart
    // dedup).
    final String eventId = nodeId + "-" + deliveryClass.name() + "-" + sequence.incrementAndGet();
    final BrokerRecord record = new BrokerRecord(eventId, topic, deliveryClass, payload);

    if (deliveryClass == DeliveryClass.ZL) {
      // Durability before the send: on crash after this point the record is replayed (RPO=0).
      wal.append(record);
    }

    try {
      sendWithRetry(record);
    } catch (final RuntimeException sendFailure) {
      handleFailure(record, sendFailure);
      return;
    }
    if (deliveryClass == DeliveryClass.ZL) {
      try {
        wal.markSent(eventId);
      } catch (final RuntimeException markFailure) {
        // The record was already delivered. A markSent failure only leaves it in the WAL for a
        // future
        // replay, which the consumer dedups by the stable eventId (Doc 07 §13/§6). It must NOT be
        // dead-lettered or re-sent — that would manufacture a duplicate of a successfully-delivered
        // record. Leaving it for idempotent replay is the correct, loss-free, dup-free resolution.
      }
    }
  }

  private void sendWithRetry(final BrokerRecord record) {
    RuntimeException last = null;
    for (int attempt = 1; attempt <= retryPolicy.maxAttempts(); attempt++) {
      if (attempt > 1) {
        try {
          retryBackoff.await(attempt); // bound the retry RATE, not just the count (Doc 07 §14)
        } catch (final InterruptedException interrupted) {
          Thread.currentThread().interrupt();
          throw last; // interrupted while backing off: surface the prior fault, fail closed
        }
      }
      try {
        broker.send(record);
        return;
      } catch (final RuntimeException failure) {
        last = failure;
        if (!retryPolicy.isRetryable(failure)) {
          throw failure; // permanent fault: fail fast, do not storm the broker
        }
      }
    }
    throw last; // retries exhausted
  }

  private void handleFailure(final BrokerRecord record, final RuntimeException failure) {
    if (record.deliveryClass() == DeliveryClass.BE) {
      // Best-effort carries no decision/accounting/audit event; drop, never dead-letter (Doc 07
      // §11).
      return;
    }
    // RL and ZL: dead-letter for visibility. A ZL record additionally stays in the WAL (not marked
    // sent) so the frozen replay mechanism re-delivers it — RPO=0 is preserved.
    deadLetter.deadLetter(record, "publish-failed:" + failure.getClass().getSimpleName());
  }
}

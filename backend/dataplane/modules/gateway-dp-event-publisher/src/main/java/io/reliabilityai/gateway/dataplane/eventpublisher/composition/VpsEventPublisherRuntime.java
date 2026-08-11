package io.reliabilityai.gateway.dataplane.eventpublisher.composition;

import io.reliabilityai.gateway.common.Preconditions;
import io.reliabilityai.gateway.dataplane.eventpublisher.adapter.InMemoryBroker;
import io.reliabilityai.gateway.dataplane.eventpublisher.adapter.LocalFileDeadLetter;
import io.reliabilityai.gateway.dataplane.eventpublisher.adapter.LocalWal;
import io.reliabilityai.gateway.dataplane.eventpublisher.api.BrokerRecord;
import io.reliabilityai.gateway.dataplane.eventpublisher.api.RetryBackoff;
import io.reliabilityai.gateway.dataplane.eventpublisher.api.RetryPolicy;
import io.reliabilityai.gateway.dataplane.eventpublisher.application.ResilientEventPublisher;
import io.reliabilityai.gateway.ports.EventPublisherPort;

/**
 * Single-VPS event-publisher runtime (Phase 3 wiring, Doc 07). Assembles the frozen {@link
 * ResilientEventPublisher} over the VPS adapters — {@code Publisher → WalPort → BrokerPort} — and
 * owns their lifecycle. The wiring realizes the zero-loss contract on one node:
 *
 * <ol>
 *   <li><b>Recover + replay:</b> on {@link #start}, before the publisher goes live, every
 *       appended-but-unsent record left by a prior crash is re-delivered to the broker in append
 *       order and marked sent on success (RPO=0, Doc 07 EV-D3); a still-failing record is
 *       dead-lettered while remaining in the WAL for a future replay.
 *   <li><b>Live:</b> {@link #publisher()} is the {@link EventPublisherPort} callers use; for ZL it
 *       appends to the WAL before the broker send and marks sent on confirmed delivery.
 * </ol>
 *
 * <p>The publisher is constructed <b>after</b> replay so its monotonic id counter is seeded from
 * the WAL high-water mark and never collides with a pre-crash id. {@link #close()} gracefully
 * drains the broker and closes the durable resources. No business logic lives here — only wiring.
 *
 * <p><b>AWS migration:</b> pass a {@code KafkaBrokerAdapter} + durable-WAL adapter instead; this
 * class and the publisher are unchanged.
 */
public final class VpsEventPublisherRuntime implements AutoCloseable {

  private final InMemoryBroker broker;
  private final LocalWal wal;
  private final LocalFileDeadLetter deadLetter;
  private final ResilientEventPublisher publisher;

  private VpsEventPublisherRuntime(
      final InMemoryBroker broker,
      final LocalWal wal,
      final LocalFileDeadLetter deadLetter,
      final ResilientEventPublisher publisher) {
    this.broker = broker;
    this.wal = wal;
    this.deadLetter = deadLetter;
    this.publisher = publisher;
  }

  /**
   * Recovers and replays the WAL, then starts a live publisher over the given VPS adapters.
   *
   * @param broker the in-process broker (already constructed with its subscriber)
   * @param wal the local durable WAL
   * @param deadLetter the local dead-letter log
   * @param retryPolicy the bounded broker-send retry policy
   * @param retryBackoff the between-attempt backoff (use {@link RetryBackoff#NONE} for none)
   * @param nodeId the stable per-node identifier for deterministic, unique event ids
   * @return the started runtime
   */
  public static VpsEventPublisherRuntime start(
      final InMemoryBroker broker,
      final LocalWal wal,
      final LocalFileDeadLetter deadLetter,
      final RetryPolicy retryPolicy,
      final RetryBackoff retryBackoff,
      final String nodeId) {
    Preconditions.requireNonNull(broker, "broker");
    Preconditions.requireNonNull(wal, "wal");
    Preconditions.requireNonNull(deadLetter, "deadLetter");

    // Re-deliver pre-crash pending records first (append order), before any new traffic (Doc 07
    // EV-D3).
    wal.replayPending(record -> redeliver(broker, wal, deadLetter, record));

    final ResilientEventPublisher publisher =
        new ResilientEventPublisher(broker, wal, deadLetter, retryPolicy, retryBackoff, nodeId);
    return new VpsEventPublisherRuntime(broker, wal, deadLetter, publisher);
  }

  private static void redeliver(
      final InMemoryBroker broker,
      final LocalWal wal,
      final LocalFileDeadLetter deadLetter,
      final BrokerRecord record) {
    try {
      broker.send(record);
    } catch (final RuntimeException sendFailure) {
      // Still failing on replay: dead-letter for visibility; it stays in the WAL for a future
      // replay
      // (RPO=0 preserved) — never dropped, never marked sent.
      deadLetter.deadLetter(record, "replay-failed:" + sendFailure.getClass().getSimpleName());
      return;
    }
    try {
      wal.markSent(record.eventId());
    } catch (final RuntimeException markFailure) {
      // Delivered but not marked: it will replay again and the consumer dedups by eventId. Safe.
    }
  }

  /** The live event-publisher port callers use. */
  public EventPublisherPort publisher() {
    return publisher;
  }

  /**
   * Gracefully drains the broker (no in-process loss) and closes durable resources.
   * Idempotent-safe.
   */
  @Override
  public void close() {
    broker.close();
    wal.close();
    deadLetter.close();
  }
}

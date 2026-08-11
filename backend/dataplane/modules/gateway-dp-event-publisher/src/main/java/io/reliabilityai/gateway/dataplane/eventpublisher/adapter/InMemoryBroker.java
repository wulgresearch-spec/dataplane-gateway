package io.reliabilityai.gateway.dataplane.eventpublisher.adapter;

import io.reliabilityai.gateway.common.Preconditions;
import io.reliabilityai.gateway.dataplane.eventpublisher.api.BrokerException;
import io.reliabilityai.gateway.dataplane.eventpublisher.api.BrokerPort;
import io.reliabilityai.gateway.dataplane.eventpublisher.api.BrokerRecord;
import java.time.Duration;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

/**
 * Single-VPS realization of {@link BrokerPort} (Doc 07, AD-009): an in-process, bounded, ordered
 * broker backed only by JDK concurrency primitives — no Kafka, no external dependency. A record is
 * enqueued on a bounded {@link ArrayBlockingQueue} and delivered, <b>in send order</b>, by a single
 * dispatcher thread to one registered subscriber, so delivery is FIFO and deterministic.
 *
 * <p><b>Backpressure:</b> {@link #send} blocks up to a configured bound when the queue is full and
 * then fails closed with a <b>retryable</b> {@link BrokerException} — the publisher's bounded retry
 * then paces the producer (never an unbounded queue, never OOM). <b>Graceful shutdown:</b> {@link
 * #close} stops accepting new records and then drains every already-accepted record to the
 * subscriber before returning, so there is <b>no message loss inside the process</b>. A send after
 * close fails closed with a permanent {@link BrokerException}.
 *
 * <p>Thread- and virtual-thread-safe: the queue is the only shared mutable state and is concurrent;
 * no lock is held across the blocking enqueue/dequeue (no virtual-thread pinning, R-049).
 *
 * <p><b>AWS migration:</b> replace with a {@code KafkaBrokerAdapter} implementing the same {@link
 * BrokerPort} (idempotent producer, acks=all); the publisher and every caller are unchanged.
 */
public final class InMemoryBroker implements BrokerPort, AutoCloseable {

  private final BlockingQueue<BrokerRecord> queue;
  private final Consumer<BrokerRecord> subscriber;
  private final long offerTimeoutNanos;
  private final Thread dispatcher;
  private final AtomicLong delivered = new AtomicLong();
  private final AtomicLong deliveryErrors = new AtomicLong();
  private final AtomicInteger inFlightSends = new AtomicInteger();

  private volatile boolean accepting = true;
  private volatile boolean running = true;

  /**
   * Creates and starts the broker.
   *
   * @param capacity the bounded queue capacity ({@code >= 1})
   * @param offerTimeout the maximum time {@link #send} blocks under backpressure before failing
   *     closed
   * @param subscriber the single in-process consumer of delivered records (never null)
   */
  public InMemoryBroker(
      final int capacity, final Duration offerTimeout, final Consumer<BrokerRecord> subscriber) {
    if (capacity < 1) {
      throw new IllegalArgumentException("capacity must be >= 1");
    }
    Preconditions.requireNonNull(offerTimeout, "offerTimeout");
    if (offerTimeout.isNegative()) {
      throw new IllegalArgumentException("offerTimeout must not be negative");
    }
    this.subscriber = Preconditions.requireNonNull(subscriber, "subscriber");
    this.queue = new ArrayBlockingQueue<>(capacity);
    this.offerTimeoutNanos = offerTimeout.toNanos();
    this.dispatcher = new Thread(this::dispatchLoop, "inmem-broker-dispatcher");
    this.dispatcher.setDaemon(true);
    this.dispatcher.start();
  }

  @Override
  public void send(final BrokerRecord record) {
    Preconditions.requireNonNull(record, "record");
    if (!accepting) {
      throw new BrokerException(false, "broker closed"); // permanent: no retry into a closed broker
    }
    // Register the in-flight enqueue so close() cannot decide the queue is fully drained while a
    // send
    // that already passed the accepting gate is still about to enqueue (which would lose that
    // record).
    inFlightSends.incrementAndGet();
    try {
      if (!queue.offer(record, offerTimeoutNanos, TimeUnit.NANOSECONDS)) {
        // Bounded backpressure exhausted — transient; the publisher's bounded retry paces the
        // producer.
        throw new BrokerException(true, "broker backpressure timeout");
      }
    } catch (final InterruptedException interrupted) {
      Thread.currentThread().interrupt();
      throw new BrokerException(true, "interrupted enqueuing to broker");
    } finally {
      inFlightSends.decrementAndGet();
    }
  }

  private void dispatchLoop() {
    while (running || !queue.isEmpty()) {
      final BrokerRecord record;
      try {
        record = queue.poll(20, TimeUnit.MILLISECONDS);
      } catch (final InterruptedException interrupted) {
        // Not used for graceful shutdown (which drains without interrupting); just re-poll. Do NOT
        // re-arm the interrupt flag here — that would make every subsequent poll throw immediately
        // and
        // spin without ever draining the remaining records.
        continue;
      }
      if (record != null) {
        deliver(record);
      }
    }
  }

  private void deliver(final BrokerRecord record) {
    try {
      subscriber.accept(record);
      delivered.incrementAndGet();
    } catch (final RuntimeException consumerFault) {
      // The record was delivered to the subscriber; a subscriber-side fault is the subscriber's
      // reliability concern (it owns its own WAL/dedup). Count it content-free; never crash the
      // broker.
      deliveryErrors.incrementAndGet();
    }
  }

  /** The number of records handed to the subscriber (for content-free operational visibility). */
  public long deliveredCount() {
    return delivered.get();
  }

  /** The number of subscriber-side delivery faults observed (content-free). */
  public long deliveryErrorCount() {
    return deliveryErrors.get();
  }

  /** The number of records currently queued awaiting delivery. */
  public int queueDepth() {
    return queue.size();
  }

  /**
   * Gracefully shuts down: stops accepting new records, drains every already-accepted record to the
   * subscriber, then stops the dispatcher — no in-process message loss. Idempotent.
   */
  @Override
  public void close() {
    accepting = false; // reject new sends first
    // Wait for any send that already passed the accepting gate to finish enqueuing, so the drain
    // below
    // observes every accepted record. The dispatcher is still running here, so blocked offers
    // drain.
    while (inFlightSends.get() > 0) {
      Thread.onSpinWait();
    }
    running =
        false; // now let the dispatcher drain the remainder, then exit (no interrupt: drain fully)
    try {
      dispatcher.join();
    } catch (final InterruptedException interrupted) {
      Thread.currentThread().interrupt();
    }
  }
}

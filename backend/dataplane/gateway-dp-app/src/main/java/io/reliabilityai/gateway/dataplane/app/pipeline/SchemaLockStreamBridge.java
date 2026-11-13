package io.reliabilityai.gateway.dataplane.app.pipeline;

import io.reliabilityai.gateway.dataplane.schemalock.api.StreamSourcePort;
import io.reliabilityai.gateway.dataplane.schemalock.domain.FailureClass;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;

/**
 * Feeds guarded delta text to SchemaLock as it arrives, so structured output is validated
 * <em>incrementally</em> rather than only once the response is complete (Doc 17 §streaming).
 *
 * <p>That distinction is the point of streaming validation: it lets SchemaLock detect a
 * structurally impossible output early and cancel, instead of paying for a full generation that was
 * never going to conform.
 *
 * <p>Bounded on purpose. The queue has a fixed capacity, so a SchemaLock session that falls behind
 * slows the producer rather than letting the gateway buffer an unbounded response. If the consumer
 * has gone away entirely, offers time out and trigger a terminal validation failure rather
 * than blocking the transport thread forever or accepting incomplete structured output.
 */
public final class SchemaLockStreamBridge implements StreamSourcePort {

  private static final int CAPACITY = 256;
  private static final long OFFER_TIMEOUT_MILLIS = 2_000;
  private static final long POLL_TIMEOUT_MILLIS = 30_000;

  private final BlockingQueue<StreamItem> queue = new ArrayBlockingQueue<>(CAPACITY);
  private volatile boolean closed;
  private volatile boolean deliveryFailed;

  @Override
  public StreamItem next() {
    if (deliveryFailed) {
      return new StreamItem.TransportFailed(FailureClass.RESOURCE_EXCEEDED);
    }
    try {
      final StreamItem item = queue.poll(POLL_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS);
      // An overflow must never be misreported as a conformant or complete response,
      // including when some queued fragments were consumed before the failure.
      if (deliveryFailed) {
        return new StreamItem.TransportFailed(FailureClass.RESOURCE_EXCEEDED);
      }
      // A silent producer is a failure, not an end-of-stream.
      return item == null ? new StreamItem.TransportFailed(FailureClass.TIMEOUT) : item;
    } catch (final InterruptedException interrupted) {
      Thread.currentThread().interrupt();
      return new StreamItem.TransportFailed(FailureClass.TIMEOUT);
    }
  }

  /**
   * Offers one delta fragment.
   *
   * @param fragment the decoded text fragment
   */
  public void offer(final String fragment) {
    if (closed || fragment == null || fragment.isEmpty()) {
      return;
    }
    put(new StreamItem.Delta(fragment));
  }

  /** Signals that the provider stream completed intact. */
  public void complete() {
    if (closed) {
      return;
    }
    closed = true;
    put(new StreamItem.TransportComplete());
  }

  /**
   * Signals that the provider stream failed.
   *
   * @param failureClass how it failed
   */
  public void fail(final FailureClass failureClass) {
    if (closed) {
      return;
    }
    closed = true;
    put(new StreamItem.TransportFailed(failureClass));
  }

  private void put(final StreamItem item) {
    if (deliveryFailed) {
      return;
    }
    try {
      if (!queue.offer(item, OFFER_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS)) {
        // A missing fragment is an incomplete validation stream: fail closed.
        deliveryFailed = true;
      }
    } catch (final InterruptedException interrupted) {
      deliveryFailed = true;
      Thread.currentThread().interrupt();
    }
  }
}

package io.reliabilityai.gateway.provider.openai;

import io.reliabilityai.gateway.common.Preconditions;
import io.reliabilityai.gateway.ports.ProviderTransportStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.concurrent.Flow;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * The OpenAI response body, exposed as a pull-based {@link ProviderTransportStream} (Doc 25 §31.1).
 *
 * <p><b>Nothing is buffered.</b> Each {@link #read()} takes whatever bytes have arrived on the
 * socket and hands them straight up. The gateway holds one small fixed array, never the response —
 * memory is constant regardless of how long the model talks. Because reads only happen when the
 * consumer asks, backpressure reaches the TCP window naturally: a slow HTTP client slows the
 * provider.
 *
 * <p><b>Bytes are preserved exactly.</b> No decoding, no SSE parsing, no normalisation happens
 * here. Framing is StreamGuard's job and interpretation is the decoder's; this class is a pipe. A
 * frame split across two socket reads stays split — reassembly belongs to the guard, which is the
 * component that has to prove nothing was lost.
 *
 * <p><b>Dual interface, single consumer.</b> The frozen {@code TransportResponse.Streamed} carrier
 * can only hold a {@link Flow.Publisher}, so this class implements that too in order to travel
 * through it. Only one facet may be used: whichever is touched first claims the body, and the other
 * then fails loudly rather than silently interleaving reads on the same socket and corrupting the
 * stream.
 */
public final class OpenAiTransportStream
    implements ProviderTransportStream, Flow.Publisher<byte[]> {

  /**
   * Read granularity. Small enough to forward a token promptly, large enough to avoid syscall
   * churn.
   */
  private static final int READ_BUFFER_BYTES = 8192;

  private final InputStream body;
  private final AtomicBoolean cancelled = new AtomicBoolean();
  private final AtomicBoolean pullClaimed = new AtomicBoolean();
  private final AtomicBoolean pushClaimed = new AtomicBoolean();

  /**
   * Wraps a streaming HTTP response body.
   *
   * @param body the response body stream; closed on cancellation or at end of stream
   */
  public OpenAiTransportStream(final InputStream body) {
    this.body = Preconditions.requireNonNull(body, "body");
  }

  @Override
  public Framing framing() {
    return Framing.SSE;
  }

  @Override
  public Chunk read() {
    if (pushClaimed.get()) {
      throw new IllegalStateException("stream already consumed as a publisher");
    }
    pullClaimed.set(true);
    if (cancelled.get()) {
      return new Chunk.Closed();
    }
    final byte[] buffer = new byte[READ_BUFFER_BYTES];
    final int read;
    try {
      read = body.read(buffer);
    } catch (final IOException disconnected) {
      // A reset or a close during cancellation both mean the same thing to the guard: the transport
      // ended here. Whether that was legitimate is the guard's verdict to make, not ours.
      closeQuietly();
      return new Chunk.Closed();
    }
    if (read < 0) {
      closeQuietly();
      return new Chunk.Closed();
    }
    if (read == 0) {
      // A zero-length read is not end-of-stream; returning Closed here would report a truncation
      // the
      // provider never made.
      return new Chunk.Data(new byte[0]);
    }
    final byte[] exact = new byte[read];
    System.arraycopy(buffer, 0, exact, 0, read);
    return new Chunk.Data(exact);
  }

  @Override
  public void cancel() {
    if (cancelled.compareAndSet(false, true)) {
      // Closing the body aborts the HTTP exchange, which is what actually releases the provider
      // connection. A blocked read() unblocks with an IOException and reports Closed.
      closeQuietly();
    }
  }

  /**
   * Whether the stream has been cancelled.
   *
   * @return {@code true} once cancelled
   */
  public boolean cancelled() {
    return cancelled.get();
  }

  @Override
  public void subscribe(final Flow.Subscriber<? super byte[]> subscriber) {
    Preconditions.requireNonNull(subscriber, "subscriber");
    if (pullClaimed.get() || !pushClaimed.compareAndSet(false, true)) {
      subscriber.onSubscribe(new NoopSubscription());
      subscriber.onError(new IllegalStateException("stream already consumed"));
      return;
    }
    subscriber.onSubscribe(new BodySubscription(subscriber));
  }

  private void closeQuietly() {
    try {
      body.close();
    } catch (final IOException ignored) {
      // the connection is going away regardless
    }
  }

  /** A subscription that yields nothing, used to reject a second consumer per the Flow contract. */
  private static final class NoopSubscription implements Flow.Subscription {
    @Override
    public void request(final long n) {
      // nothing to deliver
    }

    @Override
    public void cancel() {
      // nothing to cancel
    }
  }

  /**
   * Demand-driven delivery for the publisher facet: one read per requested item, so this path is as
   * backpressure-respecting as the pull facet. Reads run on a virtual thread so {@code request}
   * never blocks its caller.
   */
  private final class BodySubscription implements Flow.Subscription {

    private final Flow.Subscriber<? super byte[]> subscriber;
    private final AtomicBoolean terminated = new AtomicBoolean();

    BodySubscription(final Flow.Subscriber<? super byte[]> subscriber) {
      this.subscriber = subscriber;
    }

    @Override
    public void request(final long n) {
      if (n <= 0) {
        terminate(() -> subscriber.onError(new IllegalArgumentException("non-positive request")));
        return;
      }
      Thread.ofVirtual()
          .start(
              () -> {
                for (long i = 0; i < n && !terminated.get(); i++) {
                  final Chunk chunk = read();
                  if (chunk instanceof Chunk.Closed) {
                    terminate(subscriber::onComplete);
                    return;
                  }
                  subscriber.onNext(((Chunk.Data) chunk).bytes());
                }
              });
    }

    @Override
    public void cancel() {
      terminated.set(true);
      OpenAiTransportStream.this.cancel();
    }

    private void terminate(final Runnable signal) {
      if (terminated.compareAndSet(false, true)) {
        signal.run();
      }
    }
  }
}

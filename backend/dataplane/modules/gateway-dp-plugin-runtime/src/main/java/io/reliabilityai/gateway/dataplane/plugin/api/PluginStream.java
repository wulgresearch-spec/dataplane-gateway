package io.reliabilityai.gateway.dataplane.plugin.api;

/**
 * The consumer side of a streaming plugin invocation (Doc 28 §30, §39).
 *
 * <p>Pull-based, exactly like the provider stream path: the consumer asks for the next event and
 * only then does the producer get to make another. That is what makes the consumer the pace-setter
 * and keeps buffering bounded, rather than letting a fast plugin build an unbounded queue in host
 * memory.
 *
 * <p>{@link #cancel()} must be safe to call at any time, from any thread, any number of times — a
 * consumer that abandons a stream mid-flight is the normal case, not an error, and cancellation is
 * the only thing that releases the producer's resources.
 */
public interface PluginStream extends AutoCloseable {

  /**
   * Blocks until the next event is available and returns it.
   *
   * <p>Returns exactly one terminal event ({@link PluginEvent.Completed} or {@link
   * PluginEvent.Failed}) and nothing after it.
   *
   * @return the next event
   * @throws InterruptedException if the consuming thread is interrupted while waiting
   */
  PluginEvent next() throws InterruptedException;

  /**
   * Cancels the invocation and releases the producer. Idempotent and thread-safe.
   *
   * <p>Cancellation propagates all the way down: it interrupts the in-process worker or destroys
   * the sandbox process, so an abandoned stream never leaves work running (Doc 28 REC-2).
   */
  void cancel();

  /** Closes the stream by cancelling it. Never throws. */
  @Override
  default void close() {
    cancel();
  }
}

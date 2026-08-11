package io.reliabilityai.gateway.dataplane.plugin.api;

/**
 * Where a streaming plugin writes its events (Doc 28 §30).
 *
 * <p>{@link #emit} returns a boolean rather than throwing, and that boolean is the backpressure
 * signal: {@code false} means the consumer is gone or the deadline has passed, and the plugin
 * should stop. Doc 28 §30 requires consumer-paced backpressure with no unbounded buffering, so a
 * sink implementation blocks while the consumer is merely slow and refuses only when it is actually
 * finished.
 *
 * <p>A plugin that ignores {@code false} and keeps emitting is not trusted to behave: the sink
 * drops what it cannot deliver and the invocation's deadline ends it regardless.
 */
public interface PluginEventSink {

  /**
   * Offers one event to the consumer, blocking while the consumer is behind.
   *
   * @param event the event to emit
   * @return true if the event was accepted; false if the consumer is gone and the plugin should
   *     stop
   */
  boolean emit(PluginEvent event);

  /**
   * Whether the consumer is still reading.
   *
   * <p>Lets a plugin skip expensive work it is about to discard, without having to produce an event
   * first just to discover nobody is listening.
   *
   * @return true while the stream is live
   */
  boolean open();
}

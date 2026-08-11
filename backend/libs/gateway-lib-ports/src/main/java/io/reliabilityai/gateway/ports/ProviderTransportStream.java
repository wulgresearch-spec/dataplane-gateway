package io.reliabilityai.gateway.ports;

/**
 * The provider's raw, still-framed response stream, handed upward for integrity verification (Doc
 * 18, Doc 25 §31.1).
 *
 * <p><b>Why this exists.</b> StreamGuard proves properties <em>about the transport framing</em> —
 * sequence gaps, duplicate suppression, truncation, UTF-8 and CRC integrity. Those claims are only
 * meaningful about the bytes the provider actually sent. A canonical {@code StreamChunk} has
 * already discarded the framing, so a stream delivered as chunks can never be honestly guarded.
 * This port carries the original bytes, unmodified, from the adapter to the STREAM_GUARD stage.
 *
 * <p><b>Why it is declared here rather than reusing StreamGuard's own source port.</b> The
 * StreamGuard module depends on {@code gateway-lib-ports}; referencing its {@code
 * TransportSourcePort} from this package would invert that dependency and create a module cycle.
 * This port is the same shape, declared in the layer both sides can see, and is adapted to
 * StreamGuard's port by a pass-through at the composition ring — the byte arrays are forwarded,
 * never re-encoded.
 *
 * <p>Implementations are consumer-paced: {@link #read()} blocks until the next frame is available,
 * which is what gives the pipeline backpressure over the provider connection.
 */
public interface ProviderTransportStream {

  /** The wire framing the provider is using, so StreamGuard selects the right decoder. */
  enum Framing {
    /** Server-Sent Events. */
    SSE,
    /** AWS event-stream binary framing. */
    AWS_EVENT_STREAM,
    /** A chunked JSON array. */
    JSON_ARRAY_CHUNKED,
    /** Newline-delimited JSON. */
    NDJSON
  }

  /** One read from the provider connection. */
  sealed interface Chunk permits Chunk.Data, Chunk.Closed {

    /**
     * Raw transport bytes exactly as received.
     *
     * @param bytes the received bytes
     */
    record Data(byte[] bytes) implements Chunk {

      /** Defensively copies, so a producer cannot mutate bytes already handed to the guard. */
      public Data {
        if (bytes == null) {
          throw new NullPointerException("bytes must not be null");
        }
        bytes = bytes.clone();
      }

      @Override
      public byte[] bytes() {
        return bytes.clone();
      }
    }

    /** The provider closed the connection. */
    record Closed() implements Chunk {}
  }

  /**
   * The framing in use.
   *
   * @return the framing
   */
  Framing framing();

  /**
   * Reads the next transport chunk, blocking until one is available.
   *
   * @return the chunk, or {@link Chunk.Closed} when the provider closed
   * @throws InterruptedException if the consumer is cancelled while waiting
   */
  Chunk read() throws InterruptedException;

  /**
   * Cancels the stream, propagating the cancellation back to the provider connection.
   *
   * <p>Called when a downstream stage or the client abandons the response. Implementations must
   * make in-flight {@link #read()} calls return or throw promptly, so a cancelled request does not
   * hold a provider connection open.
   */
  void cancel();
}

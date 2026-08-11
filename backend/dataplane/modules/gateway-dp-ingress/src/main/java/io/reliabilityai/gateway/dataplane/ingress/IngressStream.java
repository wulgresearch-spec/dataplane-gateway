package io.reliabilityai.gateway.dataplane.ingress;

import io.reliabilityai.gateway.canonical.stream.StreamChunk;

/**
 * A consumer-paced canonical stream the HTTP server writes out as Server-Sent Events (Doc 30).
 *
 * <p>Pull-based, which is what makes backpressure real: the server asks for a chunk only once the
 * previous one has been written to the socket, so a slow client slows the provider read rather than
 * filling a queue inside the gateway. Nothing is accumulated.
 *
 * <p>Declared here rather than reusing the pipeline's own stream type because the ingress module
 * must not depend on the composition root that constructs it.
 */
public interface IngressStream {

  /**
   * Blocks for the next chunk.
   *
   * @return the next chunk; a {@link StreamChunk.Terminal} is the last value returned
   */
  StreamChunk next();

  /**
   * Whether a terminal chunk has already been produced.
   *
   * @return {@code true} once the stream is finished
   */
  boolean finished();

  /**
   * Abandons the stream, propagating cancellation back through the pipeline to the provider.
   *
   * @param reason a short, content-free reason
   */
  void cancel(String reason);
}

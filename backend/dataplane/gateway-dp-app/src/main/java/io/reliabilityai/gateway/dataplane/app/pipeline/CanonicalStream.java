package io.reliabilityai.gateway.dataplane.app.pipeline;

import io.reliabilityai.gateway.canonical.stream.StreamChunk;

/**
 * A consumer-paced canonical stream, handed to the caller once the guarded transport is open.
 *
 * <p>Pull-based on purpose. The consumer asks for the next chunk, which propagates backpressure all
 * the way to the provider socket: a slow HTTP client slows the provider read rather than filling an
 * unbounded buffer in the gateway. Nothing is accumulated — a chunk is forwarded the moment it is
 * verified and decoded.
 *
 * <p>The stream ends with exactly one {@link StreamChunk.Terminal}. Finalization — SchemaLock,
 * metering, cost and the audit emission — runs as that terminal is produced, so the accounting
 * stages observe the completed response even though the caller has already received most of it.
 */
public interface CanonicalStream extends AutoCloseable {

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
   * Abandons the stream, propagating cancellation back to the provider.
   *
   * @param reason a short, content-free reason recorded on the transport verdict
   */
  void cancel(String reason);

  @Override
  void close();
}

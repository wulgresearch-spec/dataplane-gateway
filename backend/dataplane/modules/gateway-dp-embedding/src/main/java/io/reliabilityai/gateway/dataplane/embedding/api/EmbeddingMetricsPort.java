package io.reliabilityai.gateway.dataplane.embedding.api;

import io.reliabilityai.gateway.dataplane.provider.api.ProviderId;

/**
 * Observability for the embedding path (AD-030 SS10).
 *
 * <p>Every parameter is an identifier, an enum, a count or a duration. None of them is input text,
 * and none can be widened to carry it without changing this interface.
 *
 * <p>Two signals here are worth more than the rest. The cache hit ratio decides whether the spend
 * is what anyone expects. The queue depth is the only warning that the batcher is absorbing more
 * load than the provider is draining, and it is the number that goes from fine to catastrophic
 * without passing through concerning.
 */
public interface EmbeddingMetricsPort {

  /** A metrics port that discards everything. */
  EmbeddingMetricsPort NOOP =
      new EmbeddingMetricsPort() {
        @Override
        public void requestCompleted(final int inputs, final long nanos) {}

        @Override
        public void providerCall(
            final ProviderId provider, final int batchSize, final long nanos, final boolean ok) {}

        @Override
        public void cacheHit(final int count) {}

        @Override
        public void cacheMiss(final int count) {}

        @Override
        public void negativeCacheHit(final int count) {}

        @Override
        public void retry(
            final ProviderId provider, final EmbeddingFailure reason, final int attempt) {}

        @Override
        public void failed(final EmbeddingFailure reason, final int inputs) {}

        @Override
        public void cost(final long micros, final long inputTokens) {}

        @Override
        public void queueDepth(final int depth) {}
      };

  /**
   * A whole request finished, cache hits and provider calls included.
   *
   * @param inputs how many texts it carried
   * @param nanos end-to-end duration
   */
  void requestCompleted(int inputs, long nanos);

  /**
   * One provider attempt finished.
   *
   * @param provider which provider
   * @param batchSize how many inputs the attempt carried
   * @param nanos the provider-side duration alone
   * @param ok whether it succeeded
   */
  void providerCall(ProviderId provider, int batchSize, long nanos, boolean ok);

  /**
   * Inputs served from cache.
   *
   * @param count how many
   */
  void cacheHit(int count);

  /**
   * Inputs the cache did not hold.
   *
   * @param count how many
   */
  void cacheMiss(int count);

  /**
   * Inputs served from the negative cache, meaning a known-unembeddable input was refused for free.
   *
   * @param count how many
   */
  void negativeCacheHit(int count);

  /**
   * An attempt is being repeated.
   *
   * @param provider which provider
   * @param reason why the previous attempt failed
   * @param attempt the attempt number, one-based
   */
  void retry(ProviderId provider, EmbeddingFailure reason, int attempt);

  /**
   * Inputs that ended in failure.
   *
   * @param reason the neutral failure kind
   * @param inputs how many
   */
  void failed(EmbeddingFailure reason, int inputs);

  /**
   * Spend incurred.
   *
   * @param micros the charge in millionths
   * @param inputTokens the tokens charged for
   */
  void cost(long micros, long inputTokens);

  /**
   * How many inputs are waiting for a batch to close.
   *
   * @param depth the queue depth
   */
  void queueDepth(int depth);
}

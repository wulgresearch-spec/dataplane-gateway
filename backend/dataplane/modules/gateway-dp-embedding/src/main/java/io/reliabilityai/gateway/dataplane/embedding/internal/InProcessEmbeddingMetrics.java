package io.reliabilityai.gateway.dataplane.embedding.internal;

import io.reliabilityai.gateway.dataplane.embedding.api.EmbeddingFailure;
import io.reliabilityai.gateway.dataplane.embedding.api.EmbeddingMetricsPort;
import io.reliabilityai.gateway.dataplane.provider.api.ProviderId;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Counters held in this process, for tests and for deployments with nowhere else to send them.
 *
 * <p>The discard paths matter as much as the success paths: the difference between "cheap because
 * the cache is working" and "cheap because everything is failing" is invisible in the result and
 * entirely visible here.
 */
public final class InProcessEmbeddingMetrics implements EmbeddingMetricsPort {

  private final Map<String, AtomicLong> counters = new ConcurrentHashMap<>();

  private volatile int peakQueueDepth;

  @Override
  public void requestCompleted(final int inputs, final long nanos) {
    bump("requests");
    add("request.inputs", inputs);
    add("request.nanos", nanos);
  }

  @Override
  public void providerCall(
      final ProviderId provider, final int batchSize, final long nanos, final boolean ok) {
    bump("providerCalls");
    bump(ok ? "providerCalls.ok" : "providerCalls.failed");
    add("providerCalls.batchSize", batchSize);
    add("providerCalls.nanos", nanos);
  }

  @Override
  public void cacheHit(final int count) {
    add("cache.hit", count);
  }

  @Override
  public void cacheMiss(final int count) {
    add("cache.miss", count);
  }

  @Override
  public void negativeCacheHit(final int count) {
    add("cache.negativeHit", count);
  }

  @Override
  public void retry(final ProviderId provider, final EmbeddingFailure reason, final int attempt) {
    bump("retries");
    bump("retries." + reason.name());
  }

  @Override
  public void failed(final EmbeddingFailure reason, final int inputs) {
    add("failed", inputs);
    add("failed." + reason.name(), inputs);
  }

  @Override
  public void cost(final long micros, final long inputTokens) {
    add("cost.micros", micros);
    add("cost.inputTokens", inputTokens);
  }

  @Override
  public void queueDepth(final int depth) {
    if (depth > peakQueueDepth) {
      peakQueueDepth = depth;
    }
  }

  /**
   * Reads one counter.
   *
   * @param name the counter name
   * @return its value, zero when never touched
   */
  public long count(final String name) {
    final AtomicLong counter = counters.get(name);
    return counter == null ? 0L : counter.get();
  }

  /**
   * The proportion of lookups served from cache.
   *
   * @return the hit ratio, or zero when nothing has been looked up
   */
  public double cacheHitRatio() {
    final long hits = count("cache.hit");
    final long total = hits + count("cache.miss");
    return total == 0L ? 0.0 : (double) hits / total;
  }

  /**
   * The mean inputs per provider call.
   *
   * @return the mean batch size, or zero when nothing has been called
   */
  public double meanBatchSize() {
    final long calls = count("providerCalls");
    return calls == 0L ? 0.0 : (double) count("providerCalls.batchSize") / calls;
  }

  /**
   * The largest queue depth observed.
   *
   * @return the peak depth
   */
  public int peakQueueDepth() {
    return peakQueueDepth;
  }

  /**
   * Increments a counter.
   *
   * @param name the counter name
   */
  private void bump(final String name) {
    add(name, 1L);
  }

  /**
   * Adds to a counter.
   *
   * @param name the counter name
   * @param delta the amount
   */
  private void add(final String name, final long delta) {
    counters.computeIfAbsent(name, ignored -> new AtomicLong()).addAndGet(delta);
  }
}

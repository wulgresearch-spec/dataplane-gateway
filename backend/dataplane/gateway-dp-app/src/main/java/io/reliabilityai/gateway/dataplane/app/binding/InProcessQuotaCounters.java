package io.reliabilityai.gateway.dataplane.app.binding;

import io.reliabilityai.gateway.canonical.usage.UsageFact;
import io.reliabilityai.gateway.dataplane.metering.api.QuotaCounterPort;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Node-local quota counters (Doc 23 §quota). On a single VPS the node <em>is</em> the cluster, so
 * an in-process counter is the whole truth rather than a shard of it; counters reset on restart,
 * which is correct for a node-scoped view and is exactly the seam that a shared counter store would
 * replace on a multi-node deployment.
 */
public final class InProcessQuotaCounters implements QuotaCounterPort {

  private final Map<String, AtomicLong> counters = new ConcurrentHashMap<>();

  @Override
  public void increment(final UsageFact fact, final String counterKey) {
    if (counterKey == null) {
      return;
    }
    counters.computeIfAbsent(counterKey, k -> new AtomicLong()).incrementAndGet();
  }

  /**
   * Reads a counter's current value.
   *
   * @param counterKey the counter key
   * @return the current count, or zero if the counter has never been incremented
   */
  public long count(final String counterKey) {
    final AtomicLong counter = counters.get(counterKey);
    return counter == null ? 0L : counter.get();
  }
}

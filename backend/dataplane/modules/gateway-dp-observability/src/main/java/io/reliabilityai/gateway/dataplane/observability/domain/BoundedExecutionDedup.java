package io.reliabilityai.gateway.dataplane.observability.domain;

import io.reliabilityai.gateway.canonical.identity.ExecutionIdentity;
import io.reliabilityai.gateway.common.Preconditions;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Bounded, best-effort de-duplicator keyed by {@code (ExecutionIdentity, metricName)} (Doc 27 §18.1
 * RDD-2): a metric is counted <b>once per execution identity</b>, so a retried/replayed emission
 * with the same identity is dropped — no double-count. Execution identity is owned by Reliability
 * (Doc 20, RDD-6); this component only consumes it.
 *
 * <p>State is a fixed-capacity LRU (the capacity is an operational baseline, Doc 27 §17.1 / Doc 16
 * §I.1): dedup accuracy degrades gracefully to "emit anyway" for entries evicted past the window,
 * so it never grows unbounded and never blocks the request (it is off the critical path, Doc 27
 * §17). Thread-safe via a monitor; the critical section is a bounded-size map operation only.
 */
public final class BoundedExecutionDedup {

  // A delimiter that cannot appear in an id, written as an explicit escape (no raw control byte).
  private static final String SEP = "\0"; // real NUL delimiter (never appears in a validated id)

  private final Map<String, Boolean> seen;

  /**
   * Creates a de-duplicator with the given capacity.
   *
   * @param capacity the maximum number of tracked keys (operational baseline; must be positive)
   */
  public BoundedExecutionDedup(final int capacity) {
    if (capacity <= 0) {
      throw new IllegalArgumentException("capacity must be positive: " + capacity);
    }
    this.seen =
        new LinkedHashMap<>(16, 0.75f, true) {
          @Override
          protected boolean removeEldestEntry(final Map.Entry<String, Boolean> eldest) {
            return size() > capacity;
          }
        };
  }

  /**
   * Returns whether this is the first occurrence of {@code (executionIdentity, metricName)} within
   * the bounded window; subsequent occurrences return {@code false} and must be dropped (Doc 27
   * RDD-2).
   *
   * @param executionIdentity the execution identity
   * @param metricName the metric name
   * @return {@code true} on first occurrence (emit), {@code false} on a duplicate (drop)
   */
  public synchronized boolean firstOccurrence(
      final ExecutionIdentity executionIdentity, final String metricName) {
    Preconditions.requireNonNull(executionIdentity, "executionIdentity");
    Preconditions.requireNonBlank(metricName, "metricName");
    final String key = key(executionIdentity, metricName);
    if (seen.containsKey(key)) {
      return false;
    }
    seen.put(key, Boolean.TRUE);
    return true;
  }

  private static String key(final ExecutionIdentity id, final String metricName) {
    return String.join(
        SEP,
        id.idempotencyKey().value(),
        id.attemptId().value(),
        id.correlationId().value(),
        metricName);
  }
}

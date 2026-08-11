package io.reliabilityai.gateway.dataplane.observability.api;

import io.reliabilityai.gateway.canonical.identity.ExecutionIdentity;
import io.reliabilityai.gateway.common.ContentFree;
import io.reliabilityai.gateway.common.Preconditions;
import java.util.Map;

/**
 * A content-free Plane-A metric (Doc 27 §5, Doc 14 §5). Metrics are never sampled (Doc 27 OT-D5)
 * and carry only bounded, allow-listed labels (Doc 27 OT-A6, Doc 14 §19) — never an
 * unbounded/identifying value, content, secret, or PII. It carries the {@link ExecutionIdentity} it
 * belongs to so the emitter can de-duplicate exactly once per execution under retry/replay (Doc 27
 * §18.1 RDD-1/RDD-2) without ever double-counting.
 *
 * @param name the metric name (owned by the Doc 14 registry; the emitter never authors it)
 * @param type the metric type
 * @param value the metric value
 * @param labels bounded, allow-listed labels (Doc 14 §19); defensively copied
 * @param executionIdentity the execution identity this metric belongs to (dedup key, Doc 27 RDD-1)
 */
public record CanonicalMetric(
    String name,
    MetricType type,
    long value,
    Map<String, String> labels,
    ExecutionIdentity executionIdentity)
    implements ContentFree {

  /** Compact constructor validating required fields and defensively copying labels. */
  public CanonicalMetric {
    Preconditions.requireNonBlank(name, "name");
    Preconditions.requireNonNull(type, "type");
    Preconditions.requireNonNull(executionIdentity, "executionIdentity");
    labels = labels == null ? Map.of() : Map.copyOf(labels);
  }
}

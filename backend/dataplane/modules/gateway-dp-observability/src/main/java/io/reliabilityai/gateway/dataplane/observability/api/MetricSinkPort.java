package io.reliabilityai.gateway.dataplane.observability.api;

/**
 * Outbound seam to the Plane-A metric backend (Prometheus/TSD, Doc 27 §4, Doc 14 §5.1). Owns no
 * store (Doc 27 REA-2). Metrics are never sampled and never dropped for backpressure (Doc 27 OT-D5,
 * §18.2).
 */
public interface MetricSinkPort {

  /**
   * Records a content-free Plane-A metric.
   *
   * @param metric the metric
   */
  void record(CanonicalMetric metric);
}

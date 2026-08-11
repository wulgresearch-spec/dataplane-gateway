package io.reliabilityai.gateway.dataplane.observability.api;

/**
 * The Plane-A metric type (Doc 27 §5, Doc 14 §5). Metrics are never sampled (Doc 27 OT-D5); the
 * exact registry is owned by Doc 14 (REA-6) — this enum names only the shape.
 */
public enum MetricType {
  /** Monotonic counter. */
  COUNTER,
  /** Point-in-time gauge. */
  GAUGE,
  /** Distribution/histogram observation. */
  HISTOGRAM
}

package io.reliabilityai.gateway.dataplane.observability.api;

/**
 * Outbound seam to the OpenTelemetry trace backend (Doc 27 §4, AD-011, Doc 14 §6). Owns no store.
 * Spans are best-effort and may be shed under saturation (Doc 27 §18.2).
 */
public interface TraceSinkPort {

  /**
   * Records a content-free span.
   *
   * @param span the span
   */
  void record(CanonicalSpan span);
}

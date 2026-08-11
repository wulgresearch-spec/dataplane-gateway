package io.reliabilityai.gateway.dataplane.observability.api;

/**
 * The inbound observability emission port (C9 emitter node, Doc 27 §4, OT-INV). Runtime components
 * hand already-produced, content-free telemetry to the emitter as they execute; the emitter is the
 * <b>only writer</b> (Doc 27 §RE REA-1). Emission is <b>passive and side-effect-free</b>: no method
 * may alter, block, or fail a request or any runtime decision (Doc 27 OT-A1), and every method
 * <b>fails closed by dropping telemetry</b> on any error — never runtime correctness (Doc 27 §18,
 * OT-D10). The layer is additive and removable with zero behavioral change (AD-018).
 */
public interface TelemetryEmitter {

  /**
   * Emits a Plane-A metric (never sampled, Doc 27 OT-D5); de-duplicated once per execution identity
   * (Doc 27 RDD-2). Never throws.
   *
   * @param metric the content-free metric
   */
  void metric(CanonicalMetric metric);

  /**
   * Emits an OpenTelemetry span (sampled deterministically, Doc 27 §19.1). Never throws.
   *
   * @param span the content-free span
   */
  void span(CanonicalSpan span);

  /**
   * Emits a structured log record (redacted-before-emit, Doc 27 §15). Never throws.
   *
   * @param record the content-free log record
   */
  void log(CanonicalLogRecord record);

  /**
   * Emits a content-free operational observation to the event fabric (Plane B, Doc 27 §EO). Never
   * throws.
   *
   * @param observation the content-free observation
   */
  void observation(CanonicalTelemetryObservation observation);
}

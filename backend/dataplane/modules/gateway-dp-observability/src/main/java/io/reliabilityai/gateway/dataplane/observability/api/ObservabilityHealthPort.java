package io.reliabilityai.gateway.dataplane.observability.api;

/**
 * Outbound seam for the emitter's own content-free health counters (Doc 27 §15.1 "reject/drop +
 * alert", §18). These record why telemetry was dropped (redaction rejection, cardinality violation,
 * sink failure, unknown signal) — never any signal content. Implementations MUST be non-blocking
 * and side-effect-free toward the request (Doc 27 OT-A1); they never recurse back into emission.
 */
public interface ObservabilityHealthPort {

  /** A signal was dropped because redaction found a residual sensitive pattern (Doc 27 PMR-7). */
  void redactionRejected();

  /**
   * A metric was dropped because its labels violated the cardinality/identifying-label policy
   * (OT-A6).
   */
  void cardinalityViolation();

  /**
   * A sink threw while accepting a signal; the signal was dropped, the request unaffected (OT-A1).
   */
  void sinkFailure();

  /** An unrecognized content-free signal type was handed to the generic emit path; dropped. */
  void unknownSignal();
}

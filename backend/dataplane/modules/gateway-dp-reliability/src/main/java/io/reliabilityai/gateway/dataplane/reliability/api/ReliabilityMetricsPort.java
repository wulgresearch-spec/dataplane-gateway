package io.reliabilityai.gateway.dataplane.reliability.api;

/**
 * Outbound seam for content-free reliability telemetry (Doc 20 §33, RE-D11). All signals are
 * bounded and content-free (Doc 14 §7.1); no provider name, no content. Side-effect-free toward the
 * decision (Doc 27 OT-A1); implementations MUST be non-blocking.
 */
public interface ReliabilityMetricsPort {

  /** A request was invoked successfully. */
  void succeeded();

  /** A retry of the same candidate was performed (counts against the retry ratio, Doc 20 §14). */
  void retry();

  /** A failover to the next candidate was performed (Doc 20 §9). */
  void failover();

  /** A candidate was skipped because its circuit was open (Doc 20 §10). */
  void circuitOpen();

  /**
   * A request was surfaced (fail closed) with the given content-free reason.
   *
   * @param reason the content-free failure reason code
   */
  void surfaced(String reason);
}

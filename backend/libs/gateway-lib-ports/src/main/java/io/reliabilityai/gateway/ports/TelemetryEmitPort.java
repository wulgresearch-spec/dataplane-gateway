package io.reliabilityai.gateway.ports;

import io.reliabilityai.gateway.common.ContentFree;

/**
 * The passive, side-effect-free telemetry emission port (C9, Doc 27 §4, OT-INV). Emitting telemetry
 * MUST NOT alter, block, or fail a request (Doc 27 OT-A1). Accepts only content-free signals (Doc
 * 27 §15.1); implementations drop telemetry on failure, never runtime correctness (Doc 27 §18).
 */
public interface TelemetryEmitPort {

  /**
   * Emits a content-free telemetry signal (metric/span/log/observation, per Doc 27).
   * Side-effect-free toward runtime state; never gates a decision.
   *
   * @param signal the content-free telemetry signal
   * @param <T> the content-free signal type
   */
  <T extends ContentFree> void emit(T signal);
}

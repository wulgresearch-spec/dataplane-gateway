package io.reliabilityai.gateway.dataplane.metering.api;

/**
 * Injected metering policy (Doc 23 §24/§41, operational baseline Doc 16 §I.1) — the engine
 * enforces, never authors. Immutable.
 *
 * @param allowEstimated whether a provider-flagged estimated usage fact may be recorded (always
 *     flagged, never treated as authoritative, Doc 23 §24); {@code false} ⇒ estimated usage fails
 *     closed
 */
public record MeteringPolicy(boolean allowEstimated) {

  /** The default fail-closed policy: estimated usage is not recorded (Doc 23 §24 default). */
  public static final MeteringPolicy FAIL_CLOSED = new MeteringPolicy(false);
}

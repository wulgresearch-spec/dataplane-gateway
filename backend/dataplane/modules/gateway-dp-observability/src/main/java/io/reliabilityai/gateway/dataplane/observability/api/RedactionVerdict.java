package io.reliabilityai.gateway.dataplane.observability.api;

/**
 * The outcome of the frozen redaction/classification scan (Doc 27 §15.1, Doc 14 §7.1). Redaction of
 * a signal's fields (the "redacted" transform) is performed producer-side, before emission
 * (redact-before-emit); this port is the final <b>reject-residual</b> gate: if any residual secret
 * / PII / provider-native / raw-tenant pattern remains, the signal is {@link #REJECTED dropped}
 * (fail-secure, Doc 27 PMR-7).
 */
public enum RedactionVerdict {
  /** No residual sensitive pattern found — safe to emit. */
  EMIT,
  /** A residual sensitive pattern remains — the signal MUST be dropped and an alarm raised. */
  REJECTED
}

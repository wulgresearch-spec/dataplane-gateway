/**
 * Public surface of the C9 telemetry emitter (Doc 27 §4/§5): the inbound {@code TelemetryEmitter},
 * the content-free canonical telemetry model ({@code
 * CanonicalMetric/Span/LogRecord/TelemetryObservation}), and the outbound sink/redaction/health
 * seams. Content-free by construction (Doc 27 §15, Doc 14 §7.1).
 */
package io.reliabilityai.gateway.dataplane.observability.api;

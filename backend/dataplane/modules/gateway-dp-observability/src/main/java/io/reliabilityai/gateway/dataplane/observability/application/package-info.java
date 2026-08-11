/**
 * The telemetry emission use-case (Doc 27 §4, OT-INV): passive, side-effect-free routing of
 * content-free signals — redact-before-emit, deterministic sampling, per-execution-identity dedup,
 * cardinality guard — that fails closed by dropping telemetry, never runtime correctness (Doc 27
 * OT-A1/§18).
 */
package io.reliabilityai.gateway.dataplane.observability.application;

package io.reliabilityai.gateway.dataplane.cost.api;

/**
 * The outcome of a cost projection (Doc 22 §7) — a never-underestimated {@link CostProjection}
 * upper bound, or a fail-closed {@link CostUnavailable} (CE-INV). Sealed.
 */
public sealed interface ProjectionOutcome permits CostProjection, CostUnavailable {}

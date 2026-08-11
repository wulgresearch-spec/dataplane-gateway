package io.reliabilityai.gateway.dataplane.cost.api;

/**
 * The outcome of an actual cost computation (Doc 22 §7) — an exact {@link CostResult}, or a
 * fail-closed {@link CostUnavailable} (CE-INV). Sealed.
 */
public sealed interface ComputationOutcome permits CostResult, CostUnavailable {}

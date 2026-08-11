package io.reliabilityai.gateway.dataplane.cost.domain;

import io.reliabilityai.gateway.common.Preconditions;

/**
 * Provider-neutral per-unit rates in micro-units of the descriptor's currency (Doc 22 §8).
 * Expresses any provider pricing shape (per-token, per-request, cached-input discount) in neutral
 * terms. Non-negative. Immutable.
 *
 * @param inputTokenMicros micro-units per input token
 * @param outputTokenMicros micro-units per output token
 * @param cachedTokenMicros micro-units per cached-input token (typically discounted)
 * @param perRequestMicros micro-units per request
 */
public record UnitRates(
    long inputTokenMicros, long outputTokenMicros, long cachedTokenMicros, long perRequestMicros) {

  /** Compact constructor validating non-negativity. */
  public UnitRates {
    Preconditions.requireNonNegative(inputTokenMicros, "inputTokenMicros");
    Preconditions.requireNonNegative(outputTokenMicros, "outputTokenMicros");
    Preconditions.requireNonNegative(cachedTokenMicros, "cachedTokenMicros");
    Preconditions.requireNonNegative(perRequestMicros, "perRequestMicros");
  }
}

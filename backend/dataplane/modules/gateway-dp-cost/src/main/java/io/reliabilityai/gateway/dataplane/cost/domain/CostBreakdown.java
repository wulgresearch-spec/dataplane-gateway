package io.reliabilityai.gateway.dataplane.cost.domain;

import io.reliabilityai.gateway.common.Preconditions;

/**
 * The per-component cost breakdown in the descriptor's pricing currency (Doc 22 §21) — recorded on
 * every result for audit/dispute reproduction (Doc 22 §36). Content-free (amounts only). Immutable.
 *
 * @param inputMicros input-token cost component
 * @param outputMicros output-token cost component
 * @param cachedMicros cached-token cost component
 * @param requestMicros per-request cost component
 */
public record CostBreakdown(
    long inputMicros, long outputMicros, long cachedMicros, long requestMicros) {

  /** Compact constructor validating non-negativity. */
  public CostBreakdown {
    Preconditions.requireNonNegative(inputMicros, "inputMicros");
    Preconditions.requireNonNegative(outputMicros, "outputMicros");
    Preconditions.requireNonNegative(cachedMicros, "cachedMicros");
    Preconditions.requireNonNegative(requestMicros, "requestMicros");
  }

  /**
   * The total cost in the pricing currency's micros.
   *
   * @return the summed components
   */
  public long totalMicros() {
    return inputMicros + outputMicros + cachedMicros + requestMicros;
  }
}

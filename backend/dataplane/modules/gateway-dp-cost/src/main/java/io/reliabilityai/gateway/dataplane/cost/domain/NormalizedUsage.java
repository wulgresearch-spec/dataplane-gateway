package io.reliabilityai.gateway.dataplane.cost.domain;

import io.reliabilityai.gateway.common.Preconditions;

/**
 * Canonical, provider-neutral usage the cost is computed over (Doc 22 §6/§20, from Doc 18 CV-5).
 * The cost engine <b>consumes</b> this from the Metering Engine / StreamGuard and <b>never
 * re-derives usage</b> (Doc 22 §CE-D10). The {@link UsageConfidence} class is carried through,
 * never conflated (Doc 22 §24). Non-negative counts. Immutable.
 *
 * @param inputTokens input tokens
 * @param outputTokens output tokens
 * @param cachedTokens cached-input tokens
 * @param requests request count
 * @param confidence the usage confidence class (authoritative/projected/estimated)
 */
public record NormalizedUsage(
    long inputTokens,
    long outputTokens,
    long cachedTokens,
    long requests,
    UsageConfidence confidence) {

  /** Compact constructor validating non-negativity and confidence. */
  public NormalizedUsage {
    Preconditions.requireNonNegative(inputTokens, "inputTokens");
    Preconditions.requireNonNegative(outputTokens, "outputTokens");
    Preconditions.requireNonNegative(cachedTokens, "cachedTokens");
    Preconditions.requireNonNegative(requests, "requests");
    Preconditions.requireNonNull(confidence, "confidence");
  }
}

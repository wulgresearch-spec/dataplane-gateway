package io.reliabilityai.gateway.canonical.io;

import io.reliabilityai.gateway.common.Preconditions;

/**
 * Canonical, provider-neutral usage (Doc 33 §10.5, Doc 18 CV-5). Facts, never fabricated; runtime
 * extraction is StreamGuard's (Doc 25 §20.1). Token counts are non-negative.
 *
 * @param prompt prompt tokens
 * @param completion completion tokens
 * @param reasoning reasoning tokens
 * @param cached cached tokens
 * @param toolTokens tool tokens
 * @param usageClass authoritative or estimated (Doc 18 CV-5)
 */
public record CanonicalUsage(
    long prompt,
    long completion,
    long reasoning,
    long cached,
    long toolTokens,
    UsageClass usageClass) {

  /** Compact constructor validating non-negativity and class presence. */
  public CanonicalUsage {
    Preconditions.requireNonNegative(prompt, "prompt");
    Preconditions.requireNonNegative(completion, "completion");
    Preconditions.requireNonNegative(reasoning, "reasoning");
    Preconditions.requireNonNegative(cached, "cached");
    Preconditions.requireNonNegative(toolTokens, "toolTokens");
    Preconditions.requireNonNull(usageClass, "usageClass");
  }
}

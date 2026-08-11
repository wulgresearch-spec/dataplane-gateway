package io.reliabilityai.gateway.canonical.identity;

import io.reliabilityai.gateway.common.Preconditions;

/**
 * Canonical model id — the provider-neutral model identifier (Doc 19 §9.2, Doc 25 PA-D2, AD-007).
 *
 * <p>Everything downstream keys on this; provider identity is internal to the adapter and is never
 * exposed here (Doc 25 §PA-D1).
 *
 * @param value the non-blank canonical model id
 */
public record CanonicalModelId(String value) {

  /** Compact constructor validating the id. */
  public CanonicalModelId {
    Preconditions.requireNonBlank(value, "canonicalModelId");
  }

  @Override
  public String toString() {
    return value;
  }
}

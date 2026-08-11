package io.reliabilityai.gateway.canonical.identity;

import io.reliabilityai.gateway.common.Preconditions;

/**
 * Causation id — carried on every event envelope to reconstruct causal flows (Doc 07 §6, Doc 33
 * §TIC). Threaded, single definition.
 *
 * @param value the non-blank causation id
 */
public record CausationId(String value) {

  /** Compact constructor validating the id. */
  public CausationId {
    Preconditions.requireNonBlank(value, "causationId");
  }

  @Override
  public String toString() {
    return value;
  }
}

package io.reliabilityai.gateway.dataplane.cost.domain;

import io.reliabilityai.gateway.common.Preconditions;

/**
 * The resolved precedence outcome (Doc 22 §15) — which descriptor won and at which pricing class.
 * It is a pure function of (entitlements, snapshot); recorded for audit/replay (Doc 22 §36). Sealed
 * alternative to a fail-closed {@link CostUnavailableReason}. Immutable.
 *
 * @param descriptor the winning descriptor
 * @param pricingClass the winning pricing class
 */
public record PricingResolution(PricingDescriptor descriptor, PricingClass pricingClass) {

  /** Compact constructor validating fields. */
  public PricingResolution {
    Preconditions.requireNonNull(descriptor, "descriptor");
    Preconditions.requireNonNull(pricingClass, "pricingClass");
  }
}

package io.reliabilityai.gateway.dataplane.cost.domain;

import io.reliabilityai.gateway.canonical.identity.CanonicalModelId;
import io.reliabilityai.gateway.canonical.identity.Region;
import io.reliabilityai.gateway.common.Preconditions;

/**
 * A canonical, provider-neutral, immutable price definition (Doc 22 §8, CE-D2) keyed by canonical
 * model id, region, and pricing class — <b>never</b> by provider name (AD-007). The engine reads
 * descriptors only; provider→descriptor mapping is upstream (C5 pricing sources). Version-stamped
 * for reproducibility (Doc 22 §37). Immutable.
 *
 * @param canonicalModelId the canonical model id (Doc 19 §9.2)
 * @param region the residency region (Doc 22 §CE-D11)
 * @param currency the pricing currency of the unit rates
 * @param pricingClass the pricing precedence class
 * @param unitRates the per-unit rates
 * @param version the descriptor version (stamped on every result)
 */
public record PricingDescriptor(
    CanonicalModelId canonicalModelId,
    Region region,
    String currency,
    PricingClass pricingClass,
    UnitRates unitRates,
    String version) {

  /** Compact constructor validating fields. */
  public PricingDescriptor {
    Preconditions.requireNonNull(canonicalModelId, "canonicalModelId");
    Preconditions.requireNonNull(region, "region");
    Preconditions.requireNonBlank(currency, "currency");
    Preconditions.requireNonNull(pricingClass, "pricingClass");
    Preconditions.requireNonNull(unitRates, "unitRates");
    Preconditions.requireNonBlank(version, "version");
  }

  /**
   * The snapshot lookup key {@code model|region|class} (Doc 22 §9).
   *
   * @param model the canonical model id
   * @param region the region
   * @param pricingClass the pricing class
   * @return the composite key
   */
  public static String key(
      final CanonicalModelId model, final Region region, final PricingClass pricingClass) {
    return model.value() + "|" + region.value() + "|" + pricingClass.name();
  }
}

package io.reliabilityai.gateway.dataplane.cost.domain;

import io.reliabilityai.gateway.canonical.identity.CanonicalModelId;
import io.reliabilityai.gateway.canonical.identity.Region;
import io.reliabilityai.gateway.common.Preconditions;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;

/**
 * A versioned, immutable pricing snapshot (Doc 22 §9/§13.1, AD-022) authored and distributed by the
 * C5 control plane — the single source of pricing truth (PSC-1). The engine <b>consumes it
 * read-only</b> and never refreshes/produces/mutates it (PSC-7/PSC-11). Beyond {@link #validUntil}
 * it is untrusted ⇒ fail closed (PSC-8). Immutable; descriptors defensively copied.
 *
 * @param version the monotonic snapshot version (stamped on every result, PSC-2)
 * @param producedAt the producer timestamp (PSC-3)
 * @param validUntil the validity boundary (beyond ⇒ fail closed, PSC-4)
 * @param descriptors {@code model|region|class} → descriptor (Doc 22 §8)
 * @param fx the authoritative FX table (Doc 22 §16.1)
 */
public record PricingSnapshot(
    String version,
    Instant producedAt,
    Instant validUntil,
    Map<String, PricingDescriptor> descriptors,
    FxTable fx) {

  /** Compact constructor validating fields and defensively copying descriptors. */
  public PricingSnapshot {
    Preconditions.requireNonBlank(version, "version");
    Preconditions.requireNonNull(producedAt, "producedAt");
    Preconditions.requireNonNull(validUntil, "validUntil");
    Preconditions.requireNonNull(fx, "fx");
    descriptors = descriptors == null ? Map.of() : Map.copyOf(descriptors);
  }

  /**
   * Resolves the descriptor for a model/region/class, if authoritatively present (Doc 22 §14).
   *
   * @param model the canonical model id
   * @param region the region
   * @param pricingClass the pricing class
   * @return the descriptor, or empty ⇒ fail closed
   */
  public Optional<PricingDescriptor> descriptorFor(
      final CanonicalModelId model, final Region region, final PricingClass pricingClass) {
    return Optional.ofNullable(descriptors.get(PricingDescriptor.key(model, region, pricingClass)));
  }
}

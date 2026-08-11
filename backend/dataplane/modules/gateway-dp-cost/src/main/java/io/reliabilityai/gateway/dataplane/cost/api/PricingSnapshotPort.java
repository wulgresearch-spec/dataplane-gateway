package io.reliabilityai.gateway.dataplane.cost.api;

import io.reliabilityai.gateway.dataplane.cost.domain.PricingSnapshot;
import java.util.Optional;

/**
 * The pricing-snapshot seam (Doc 22 §7/§13.1, AD-022). Delivers the current versioned, immutable
 * {@link PricingSnapshot} (with its FX table) authored by the C5 control plane — the engine
 * <b>consumes it read-only and never refreshes/produces/mutates it</b> (PSC-7/PSC-11). Empty ⇒ the
 * engine fails closed ({@code PRICING_MISSING}, PSC-9); beyond {@code validUntil} the engine fails
 * closed ({@code PRICING_STALE}, PSC-8).
 */
public interface PricingSnapshotPort {

  /**
   * Returns the current pricing snapshot (Doc 22 §13.1).
   *
   * @return the snapshot, or empty ⇒ fail closed ({@code PRICING_MISSING})
   */
  Optional<PricingSnapshot> current();
}

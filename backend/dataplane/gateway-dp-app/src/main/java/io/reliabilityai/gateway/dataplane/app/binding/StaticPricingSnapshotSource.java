package io.reliabilityai.gateway.dataplane.app.binding;

import io.reliabilityai.gateway.common.Preconditions;
import io.reliabilityai.gateway.dataplane.cost.api.PricingSnapshotPort;
import io.reliabilityai.gateway.dataplane.cost.domain.PricingSnapshot;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Pins the immutable {@link PricingSnapshot} (rate card + FX table) in memory for the cost engine
 * (Doc 22). Pricing is never fetched on the request path — an unavailable or expired snapshot makes
 * the engine report cost-unavailable rather than guess, which is the fail-closed behaviour money
 * requires.
 */
public final class StaticPricingSnapshotSource implements PricingSnapshotPort {

  private final AtomicReference<PricingSnapshot> pinned = new AtomicReference<>();

  /**
   * Creates the source with the startup pricing snapshot.
   *
   * @param initial the pricing snapshot pinned at startup
   */
  public StaticPricingSnapshotSource(final PricingSnapshot initial) {
    pinned.set(Preconditions.requireNonNull(initial, "initial"));
  }

  @Override
  public Optional<PricingSnapshot> current() {
    return Optional.ofNullable(pinned.get());
  }

  /**
   * Atomically replaces the pinned pricing snapshot.
   *
   * @param snapshot the newly validated pricing snapshot
   */
  public void applyPublished(final PricingSnapshot snapshot) {
    pinned.set(Preconditions.requireNonNull(snapshot, "snapshot"));
  }
}

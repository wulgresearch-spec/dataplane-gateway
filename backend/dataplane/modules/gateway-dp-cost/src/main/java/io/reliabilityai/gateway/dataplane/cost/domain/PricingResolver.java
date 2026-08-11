package io.reliabilityai.gateway.dataplane.cost.domain;

import io.reliabilityai.gateway.canonical.identity.CanonicalModelId;
import io.reliabilityai.gateway.canonical.identity.Region;
import io.reliabilityai.gateway.common.Preconditions;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.List;
import java.util.Optional;

/**
 * Deterministic pricing-precedence resolution (Doc 22 §15, CE-D7): negotiated &gt; enterprise &gt;
 * committed &gt; burst &gt; list. The <b>highest-precedence entitled class with an authoritative
 * descriptor wins</b>; if that class is entitled but has no descriptor, it fails closed ({@code
 * CONTRACT_UNRESOLVED}) — <b>never a silent list-price fallback</b> (CE-INV). {@code LIST} is
 * always available; a missing list price for the region ⇒ {@code NO_REGIONAL_PRICE}. Pure and
 * deterministic (Doc 22 §CE-D8).
 *
 * <p><b>Scope note (§18):</b> committed-use tier splitting (committed rate up to the committed
 * quantity, then burst for the excess) uses the frozen bounded-staleness commitment counter (Doc 21
 * §28.1) and is a composition concern that fails safe to burst on staleness (never-underestimate);
 * this resolver selects the precedence-winning descriptor.
 */
public final class PricingResolver {

  private PricingResolver() {}

  /**
   * Resolves the winning pricing descriptor by precedence (Doc 22 §15).
   *
   * @param model the canonical model id
   * @param region the region
   * @param entitlement the tenant contract entitlement
   * @param snapshot the pricing snapshot
   * @return a resolved outcome or a fail-closed reason
   */
  public static Result resolve(
      final CanonicalModelId model,
      final Region region,
      final ContractEntitlement entitlement,
      final PricingSnapshot snapshot) {
    Preconditions.requireNonNull(model, "model");
    Preconditions.requireNonNull(region, "region");
    Preconditions.requireNonNull(entitlement, "entitlement");
    Preconditions.requireNonNull(snapshot, "snapshot");

    final List<PricingClass> candidates = new ArrayList<>(entitlement.entitledClasses());
    candidates.add(PricingClass.LIST); // always available (lowest precedence)
    // Deterministic precedence order, highest first, deduplicated by enum identity.
    final EnumSet<PricingClass> seen = EnumSet.noneOf(PricingClass.class);
    candidates.removeIf(c -> !seen.add(c));
    candidates.sort(Comparator.comparingInt(PricingClass::precedence).reversed());

    final PricingClass winner = candidates.get(0);
    final Optional<PricingDescriptor> descriptor = snapshot.descriptorFor(model, region, winner);
    if (descriptor.isPresent()) {
      return Result.resolved(new PricingResolution(descriptor.get(), winner));
    }
    // The highest-precedence entitled class has no authoritative descriptor.
    return winner == PricingClass.LIST
        ? Result.failed(CostUnavailableReason.NO_REGIONAL_PRICE)
        : Result.failed(CostUnavailableReason.CONTRACT_UNRESOLVED); // no silent list fallback (§15)
  }

  /**
   * The resolution outcome: exactly one of {@code resolution} / {@code reason} is present.
   *
   * @param resolution the resolved precedence outcome (null iff failed)
   * @param reason the fail-closed reason (null iff resolved)
   */
  public record Result(PricingResolution resolution, CostUnavailableReason reason) {

    static Result resolved(final PricingResolution resolution) {
      return new Result(resolution, null);
    }

    static Result failed(final CostUnavailableReason reason) {
      return new Result(null, reason);
    }

    /**
     * Whether resolution succeeded.
     *
     * @return {@code true} if a descriptor was resolved
     */
    public boolean isResolved() {
      return resolution != null;
    }
  }
}

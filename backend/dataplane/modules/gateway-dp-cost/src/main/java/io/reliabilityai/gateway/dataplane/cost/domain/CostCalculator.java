package io.reliabilityai.gateway.dataplane.cost.domain;

import io.reliabilityai.gateway.common.Preconditions;

/**
 * The pure, deterministic cost-arithmetic core (Doc 22 §21/§CE-D8): {@code cost = Σ (unitRate ×
 * normalizedUnits)} in the descriptor's currency, converted to the canonical unit via an
 * authoritative {@link FxRate}. For projections it converts conservatively (worst-case rate, round
 * up) so the bound is <b>never underestimated</b> (Doc 22 §CE-D5/§FX-9). All arithmetic is exact
 * integer math; an overflow throws and is failed closed by the caller (never a wrapped/wrong cost).
 * No wall-clock/random (R-063).
 */
public final class CostCalculator {

  private CostCalculator() {}

  /**
   * Prices usage against a resolved descriptor and FX rate (Doc 22 §21/§23).
   *
   * @param descriptor the resolved pricing descriptor
   * @param usage the usage to price (actual usage, or a max-usage upper bound for projection)
   * @param fx the authoritative FX rate for the descriptor's currency
   * @param conservative whether to convert conservatively (projection ⇒ never underestimate)
   * @return the priced cost (canonical micros + breakdown)
   * @throws ArithmeticException on numeric overflow (failed closed by the caller)
   */
  public static Priced price(
      final PricingDescriptor descriptor,
      final NormalizedUsage usage,
      final FxRate fx,
      final boolean conservative) {
    Preconditions.requireNonNull(descriptor, "descriptor");
    Preconditions.requireNonNull(usage, "usage");
    Preconditions.requireNonNull(fx, "fx");
    final UnitRates rates = descriptor.unitRates();
    final CostBreakdown breakdown =
        new CostBreakdown(
            Math.multiplyExact(usage.inputTokens(), rates.inputTokenMicros()),
            Math.multiplyExact(usage.outputTokens(), rates.outputTokenMicros()),
            Math.multiplyExact(usage.cachedTokens(), rates.cachedTokenMicros()),
            Math.multiplyExact(usage.requests(), rates.perRequestMicros()));
    final long currencyTotal =
        Math.addExact(
            Math.addExact(breakdown.inputMicros(), breakdown.outputMicros()),
            Math.addExact(breakdown.cachedMicros(), breakdown.requestMicros()));
    final long canonicalMicros = fx.toCanonicalMicros(currencyTotal, conservative);
    return new Priced(canonicalMicros, breakdown);
  }

  /**
   * A priced result (Doc 22 §21).
   *
   * @param canonicalMicros the cost in canonical-unit micros
   * @param breakdown the per-component breakdown in the descriptor currency
   */
  public record Priced(long canonicalMicros, CostBreakdown breakdown) {
    /** Compact constructor validating fields. */
    public Priced {
      Preconditions.requireNonNegative(canonicalMicros, "canonicalMicros");
      Preconditions.requireNonNull(breakdown, "breakdown");
    }
  }
}

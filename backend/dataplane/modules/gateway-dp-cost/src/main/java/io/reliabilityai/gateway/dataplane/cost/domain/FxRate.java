package io.reliabilityai.gateway.dataplane.cost.domain;

import io.reliabilityai.gateway.common.Preconditions;

/**
 * An authoritative currency-conversion rate to the canonical comparative unit (Doc 22 §12/§16.1),
 * scaled by {@code 1_000_000} (rate 1.0 → {@code 1_000_000}). The {@code worstRateMicros} is the
 * most cost-increasing authoritative rate within the table's stated validity — used for
 * <b>conservative, never-underestimating</b> projection conversion (Doc 22 §FX-9); {@code
 * rateMicros} is the single authoritative rate for exact actual conversion. Immutable.
 *
 * @param rateMicros the authoritative rate ×1e6 (for actual cost)
 * @param worstRateMicros the worst-case (most cost-increasing) rate ×1e6 (for projections); {@code
 *     >= rateMicros}
 */
public record FxRate(long rateMicros, long worstRateMicros) {

  private static final long SCALE = 1_000_000L;

  /** Compact constructor validating the rates. */
  public FxRate {
    Preconditions.requireNonNegative(rateMicros, "rateMicros");
    Preconditions.requireNonNegative(worstRateMicros, "worstRateMicros");
    if (worstRateMicros < rateMicros) {
      throw new IllegalArgumentException("worstRateMicros must be >= rateMicros");
    }
  }

  /** The identity rate (1.0) — for converting a currency to itself. */
  public static final FxRate IDENTITY = new FxRate(SCALE, SCALE);

  /**
   * Converts an amount in the source currency's micros to canonical micros (Doc 22 §16.1).
   * Projections use the worst-case (most cost-increasing) rate (FX-9); the actual conversion uses
   * the single authoritative rate. In <b>both</b> cases division rounds <b>up</b> — cost is
   * <b>never optimistically rounded down</b> (CE-INV) — so neither a projection nor an actual cost
   * can underestimate by a sub-unit remainder (a bounded over of &lt;1 micro is reconciled by C5,
   * Doc 22 §29).
   *
   * @param currencyMicros the amount in the source currency's micros
   * @param conservative whether to use the worst-case rate (projection)
   * @return the amount in canonical micros (rounded up)
   */
  public long toCanonicalMicros(final long currencyMicros, final boolean conservative) {
    final long rate = conservative ? worstRateMicros : rateMicros;
    final long product =
        Math.multiplyExact(currencyMicros, rate); // overflow ⇒ fail closed upstream
    return ceilDiv(product, SCALE); // never round down (CE-INV)
  }

  private static long ceilDiv(final long numerator, final long denominator) {
    return (numerator + denominator - 1) / denominator;
  }
}

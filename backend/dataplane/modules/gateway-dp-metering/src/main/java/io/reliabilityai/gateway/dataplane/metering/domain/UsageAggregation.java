package io.reliabilityai.gateway.dataplane.metering.domain;

import io.reliabilityai.gateway.canonical.io.CanonicalUsage;
import io.reliabilityai.gateway.canonical.io.UsageClass;
import io.reliabilityai.gateway.common.Preconditions;

/**
 * Pure, deterministic aggregation of canonical usage units (Doc 23 §26 — provider usage = Σ all
 * attempts). Summation is commutative and exact (integer counts, no floating point). The aggregate
 * {@link UsageClass} is {@code ESTIMATED} if <b>any</b> summed fact is estimated, else {@code
 * AUTHORITATIVE} — an aggregate is never claimed more authoritative than its inputs (UME-INV).
 * Deterministic (Doc 23 §36); overflow-guarded.
 */
public final class UsageAggregation {

  /** The additive identity — zero authoritative usage. */
  public static final CanonicalUsage ZERO =
      new CanonicalUsage(0, 0, 0, 0, 0, UsageClass.AUTHORITATIVE);

  private UsageAggregation() {}

  /**
   * Adds two canonical usage records exactly (Doc 23 §26).
   *
   * @param a the first usage
   * @param b the second usage
   * @return the summed usage (estimated if either is estimated)
   */
  public static CanonicalUsage add(final CanonicalUsage a, final CanonicalUsage b) {
    Preconditions.requireNonNull(a, "a");
    Preconditions.requireNonNull(b, "b");
    final UsageClass cls =
        a.usageClass() == UsageClass.ESTIMATED || b.usageClass() == UsageClass.ESTIMATED
            ? UsageClass.ESTIMATED
            : UsageClass.AUTHORITATIVE;
    return new CanonicalUsage(
        addExact(a.prompt(), b.prompt()),
        addExact(a.completion(), b.completion()),
        addExact(a.reasoning(), b.reasoning()),
        addExact(a.cached(), b.cached()),
        addExact(a.toolTokens(), b.toolTokens()),
        cls);
  }

  private static long addExact(final long x, final long y) {
    try {
      return Math.addExact(x, y);
    } catch (final ArithmeticException e) {
      throw new IllegalStateException("usage aggregation overflow", e);
    }
  }
}

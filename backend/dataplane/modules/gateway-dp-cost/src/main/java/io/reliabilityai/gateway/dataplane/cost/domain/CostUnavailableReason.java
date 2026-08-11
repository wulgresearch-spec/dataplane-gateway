package io.reliabilityai.gateway.dataplane.cost.domain;

/**
 * The neutral fail-closed reasons for {@code CostUnavailable} (Doc 22 §39, CE-INV). Every
 * uncertainty — missing/stale pricing or FX, unbounded projection, unresolved contract, missing
 * usage — surfaces here; the engine never guesses, fabricates, defaults, or under-estimates a cost.
 */
public enum CostUnavailableReason {
  /** No pricing snapshot available (cold start / distribution outage) (Doc 22 §PSC-9). */
  PRICING_MISSING,
  /** Pricing snapshot beyond {@code validUntil} — untrusted (Doc 22 §PSC-8). */
  PRICING_STALE,
  /** No descriptor for the model/region/class (Doc 22 §14). */
  NO_REGIONAL_PRICE,
  /** Required FX pair absent from the authoritative table (Doc 22 §FX-8). */
  FX_MISSING,
  /** FX table beyond validity — untrusted (Doc 22 §FX-7). */
  FX_STALE,
  /** Cannot construct a never-underestimated projection bound (Doc 22 §23.1). */
  USAGE_UNBOUNDED,
  /** Usage estimated/missing and policy does not permit an estimated charge (Doc 22 §24). */
  USAGE_ESTIMATED_NOT_PERMITTED,
  /**
   * A tenant-entitled contract class has no authoritative descriptor — no silent list fallback (Doc
   * 22 §15).
   */
  CONTRACT_UNRESOLVED,
  /** Any unknown/internal error — fail closed (Doc 22 §39). */
  INTERNAL_ERROR
}

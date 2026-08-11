package io.reliabilityai.gateway.dataplane.cost.api;

import io.reliabilityai.gateway.dataplane.cost.domain.CostUnavailableReason;

/**
 * The content-free cost-outcome seam (Doc 22 §44/§46/§47). Fans out cost decisions to Audit (C10)
 * and Observability (C9) — all <b>content-free</b>: amounts, versions, pricing/usage class; never
 * prompt/completion or provider identity (Doc 22 §43). A no-op default lets composition omit it.
 * Best-effort: never alters a cost decision.
 */
public interface CostOutcomeSink {

  /** A no-op sink (safe default). */
  CostOutcomeSink NO_OP = new CostOutcomeSink() {};

  /**
   * Records a computed cost decision (Doc 22 §45) — content-free.
   *
   * @param result the cost result
   */
  default void onCost(final CostResult result) {}

  /**
   * Records a projection decision (Doc 22 §45) — content-free.
   *
   * @param projection the cost projection
   */
  default void onProjection(final CostProjection projection) {}

  /**
   * Records a fail-closed cost-unavailable decision (Doc 22 §39/§45).
   *
   * @param reason the neutral reason
   */
  default void onUnavailable(final CostUnavailableReason reason) {}
}

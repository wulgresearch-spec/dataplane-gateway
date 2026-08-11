package io.reliabilityai.gateway.dataplane.cost.api;

import io.reliabilityai.gateway.dataplane.cost.domain.NormalizedUsage;

/**
 * The Cost Engine inbound port (Doc 22 §7) — authoritative, deterministic, never-underestimated
 * cost calculation. A <b>projection</b> at admission (a never-underestimated upper bound Governance
 * checks against budget) and an <b>actual</b> cost after usage is known. On any
 * pricing/FX/usage/contract uncertainty it returns {@code CostUnavailable} (fail closed, CE-INV) —
 * never a guessed or under-estimated cost.
 */
public interface CostEnginePort {

  /**
   * Computes a never-underestimated admission cost upper bound (Doc 22 §23) — {@code projection ≥
   * actual}.
   *
   * @param request the cost request (with the declared max-output bound)
   * @return a projection or a fail-closed {@code CostUnavailable}
   */
  ProjectionOutcome project(CostRequest request);

  /**
   * Computes the exact actual cost from authoritative usage (Doc 22 §21).
   *
   * @param request the cost request
   * @param usage the normalized usage (authoritative or flagged-estimated, Doc 18 CV-5)
   * @return a cost result or a fail-closed {@code CostUnavailable}
   */
  ComputationOutcome compute(CostRequest request, NormalizedUsage usage);
}

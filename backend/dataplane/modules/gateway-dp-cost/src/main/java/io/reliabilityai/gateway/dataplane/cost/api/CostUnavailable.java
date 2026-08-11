package io.reliabilityai.gateway.dataplane.cost.api;

import io.reliabilityai.gateway.common.Preconditions;
import io.reliabilityai.gateway.dataplane.cost.domain.CostUnavailableReason;

/**
 * The fail-closed cost outcome (Doc 22 §39, CE-INV) — surfaced on any pricing/FX/usage/contract
 * uncertainty. Downstream, {@code CostUnavailable} ⇒ Governance denies admission ({@code
 * BUDGET_INDETERMINATE}), Reliability does not hedge, the Router treats the candidate as
 * cost-unknown — never a silent proceed on unknown cost (Doc 22 §PSC-12). It is <b>both</b> a
 * projection and a computation outcome. Content-free. Immutable.
 *
 * @param reason the neutral fail-closed reason
 */
public record CostUnavailable(CostUnavailableReason reason)
    implements ProjectionOutcome, ComputationOutcome {

  /** Compact constructor validating the reason. */
  public CostUnavailable {
    Preconditions.requireNonNull(reason, "reason");
  }
}

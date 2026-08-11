package io.reliabilityai.gateway.dataplane.cost.api;

import io.reliabilityai.gateway.canonical.identity.AttemptId;
import io.reliabilityai.gateway.canonical.identity.IdempotencyKey;
import io.reliabilityai.gateway.common.Preconditions;
import io.reliabilityai.gateway.dataplane.cost.domain.CostBreakdown;
import io.reliabilityai.gateway.dataplane.cost.domain.Money;
import io.reliabilityai.gateway.dataplane.cost.domain.Phase;
import io.reliabilityai.gateway.dataplane.cost.domain.PricingClass;
import io.reliabilityai.gateway.dataplane.cost.domain.UsageConfidence;
import java.util.Map;

/**
 * The provider-neutral, immutable actual cost result (Doc 22 §6/§CE-D10). Content-free (amounts +
 * versions + attribution, no prompt/completion). It stamps every version needed for exact
 * replay/dispute (pricing, FX, contract; Doc 22 §37 RI-1..10). The {@code delivered} flag +
 * idempotency key drive the exactly-once customer charge vs per-attempt provider spend attribution
 * (Doc 22 §24.1). Immutable.
 *
 * @param amount the cost in the canonical comparative unit
 * @param breakdown the per-component breakdown in the descriptor currency
 * @param pricingClass the resolved pricing class (precedence outcome)
 * @param phase the calculation phase (actual)
 * @param confidence the usage confidence class (authoritative/estimated, never conflated, §24)
 * @param idempotencyKey the request idempotency key (single customer charge, §CA-10)
 * @param attemptId the attempt id (per-attempt provider spend, §CA-1)
 * @param delivered whether this attempt is the delivered customer charge (§CA-11)
 * @param versions the stamped snapshot/FX/contract versions for replay (§37)
 */
public record CostResult(
    Money amount,
    CostBreakdown breakdown,
    PricingClass pricingClass,
    Phase phase,
    UsageConfidence confidence,
    IdempotencyKey idempotencyKey,
    AttemptId attemptId,
    boolean delivered,
    Map<String, String> versions)
    implements ComputationOutcome {

  /** Compact constructor validating fields and defensively copying versions. */
  public CostResult {
    Preconditions.requireNonNull(amount, "amount");
    Preconditions.requireNonNull(breakdown, "breakdown");
    Preconditions.requireNonNull(pricingClass, "pricingClass");
    Preconditions.requireNonNull(phase, "phase");
    Preconditions.requireNonNull(confidence, "confidence");
    Preconditions.requireNonNull(idempotencyKey, "idempotencyKey");
    Preconditions.requireNonNull(attemptId, "attemptId");
    versions = versions == null ? Map.of() : Map.copyOf(versions);
  }
}

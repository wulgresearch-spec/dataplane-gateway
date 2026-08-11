package io.reliabilityai.gateway.dataplane.cost.api;

import io.reliabilityai.gateway.common.Preconditions;
import io.reliabilityai.gateway.dataplane.cost.domain.Money;
import io.reliabilityai.gateway.dataplane.cost.domain.PricingClass;
import java.util.Map;

/**
 * A never-underestimated cost upper bound for admission (Doc 22 §CE-D5/§23) — always {@code
 * projection ≥ actual}. Computed from authoritative pricing × the request's max possible usage ×
 * conservative FX (Doc 22 §23.1/§FX-9); it is <b>not a charge</b> (Doc 22 §24). Governance's budget
 * check consumes it so it can never admit an over-budget request due to under-projection.
 * Version-stamped for replay. Immutable.
 *
 * @param upperBound the never-underestimated cost upper bound (canonical unit)
 * @param pricingClass the resolved pricing class
 * @param boundBasis a neutral description of the max-usage bound used (audit/replay, §37 RI-8)
 * @param versions the stamped snapshot/FX/contract versions
 */
public record CostProjection(
    Money upperBound, PricingClass pricingClass, String boundBasis, Map<String, String> versions)
    implements ProjectionOutcome {

  /** Compact constructor validating fields and defensively copying versions. */
  public CostProjection {
    Preconditions.requireNonNull(upperBound, "upperBound");
    Preconditions.requireNonNull(pricingClass, "pricingClass");
    Preconditions.requireNonBlank(boundBasis, "boundBasis");
    versions = versions == null ? Map.of() : Map.copyOf(versions);
  }
}

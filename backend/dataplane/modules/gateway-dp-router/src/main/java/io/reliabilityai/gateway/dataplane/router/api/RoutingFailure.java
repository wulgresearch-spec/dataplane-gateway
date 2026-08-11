package io.reliabilityai.gateway.dataplane.router.api;

import io.reliabilityai.gateway.common.Preconditions;

/**
 * A neutral, content-free routing failure naming the binding constraint that eliminated all
 * candidates (Doc 19 §18). The Router fails closed — it never guesses, downgrades, or crosses a
 * hard tier — and surfaces the highest-priority tier the request fundamentally conflicts with (Doc
 * 19 §11.1).
 *
 * @param reason the typed binding-constraint reason
 */
public record RoutingFailure(FailureReason reason) {

  /** Compact constructor validating the reason. */
  public RoutingFailure {
    Preconditions.requireNonNull(reason, "reason");
  }

  /** The content-free binding-constraint taxonomy (Doc 19 §18). */
  public enum FailureReason {
    /** Tenant policy (tier 1) excludes every candidate. */
    POLICY_CONFLICT("policy-conflict"),
    /** No candidate carries a required compliance attestation (tier 2). */
    COMPLIANCE_CONFLICT("compliance-conflict"),
    /** No candidate is permitted in the required region (tier 3). */
    RESIDENCY_CONFLICT("residency-conflict"),
    /** No candidate satisfies all required capabilities/context (tier 4). */
    CAPABILITY_UNSATISFIED("capability-unsatisfied"),
    /** Every candidate is below the availability floor (tier 5). */
    NO_AVAILABLE_PROVIDER("no-available-provider"),
    /** Every candidate is below the reliability floor / circuit open (tier 6). */
    RELIABILITY_FLOOR("reliability-floor"),
    /** Every candidate is over the cost ceiling / budget. */
    BUDGET_EXCEEDED("budget-exceeded"),
    /** A required hard-constraint snapshot is stale or missing (Doc 19 §17). */
    STALE_CONSTRAINT("stale-constraint"),
    /** No candidate descriptor exists in the snapshot (Doc 19 §18). */
    NO_ELIGIBLE_PROVIDER("no-eligible-provider");

    private final String code;

    FailureReason(final String code) {
      this.code = code;
    }

    /**
     * The stable, content-free reason code.
     *
     * @return the code
     */
    public String code() {
      return code;
    }
  }
}

package io.reliabilityai.gateway.dataplane.governance.api;

import io.reliabilityai.gateway.common.Preconditions;
import java.util.List;

/**
 * The aggregate blast radius of a candidate generation over a sample of requests.
 *
 * <p>Deliberately reports {@code newlyDenied} separately from every other change, because the two
 * are not comparable risks. A policy that admits something new is a widening an operator chose; a
 * policy that refuses something previously admitted is an outage for whoever was sending it. An
 * impact report that folded both into one "changed" number would let the dangerous case hide inside
 * the harmless one.
 *
 * <p>{@code newlyDeniedRuleIds} names the statements responsible, so a surprising number has
 * somewhere to be traced to rather than merely being alarming.
 *
 * @param evaluated how many requests were simulated
 * @param unchanged how many kept their verdict
 * @param newlyDenied how many go from admitted to refused
 * @param newlyAdmitted how many go from refused to admitted
 * @param newlyDeniedRuleIds the distinct statements causing the new refusals, sorted
 */
public record SimulationImpact(
    int evaluated,
    int unchanged,
    int newlyDenied,
    int newlyAdmitted,
    List<String> newlyDeniedRuleIds) {

  /** Validates the report. */
  public SimulationImpact {
    Preconditions.requireNonNegative(evaluated, "evaluated");
    Preconditions.requireNonNegative(unchanged, "unchanged");
    Preconditions.requireNonNegative(newlyDenied, "newlyDenied");
    Preconditions.requireNonNegative(newlyAdmitted, "newlyAdmitted");
    newlyDeniedRuleIds = newlyDeniedRuleIds == null ? List.of() : List.copyOf(newlyDeniedRuleIds);
  }

  /**
   * Whether the candidate can be rolled out without refusing anything currently admitted.
   *
   * @return {@code true} when no request in the sample becomes refused
   */
  public boolean isSafeRollout() {
    return newlyDenied == 0;
  }
}

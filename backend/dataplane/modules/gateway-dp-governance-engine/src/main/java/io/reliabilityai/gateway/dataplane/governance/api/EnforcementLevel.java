package io.reliabilityai.gateway.dataplane.governance.api;

/**
 * How hard a rule bites when it is violated — the Sentinel-style enforcement ladder, applied to
 * governance (Doc 21 §28.3 hard vs soft classification).
 *
 * <p>Declaration order is <b>increasing strictness</b>, which is what makes the merge total: when
 * the same policy type is set at several scopes, the strictest level wins, so a project can tighten
 * an organization's advisory rule into a mandatory one but can never do the reverse. Without that
 * rule an attacker (or a careless admin) with write access to a leaf scope could neutralise every
 * inherited control by re-declaring it in shadow.
 *
 * <p>The level is <b>authored by the control plane</b>, never inferred. The engine will not guess
 * that a cap is advisory because it looks advisory (Doc 21 §28.3: "the Engine never invents a
 * class").
 */
public enum EnforcementLevel {

  /**
   * Evaluate and record, but do not act. The intended use is rolling out a new rule against live
   * traffic to measure its blast radius before it bites. Never permitted on a hard tier.
   */
  SHADOW,

  /**
   * The request proceeds, but the violation is recorded, surfaced as an obligation and audited.
   * This is the soft cap of Doc 21 §28.3 — bounded, never silent. Never permitted on a hard tier.
   */
  ADVISORY,

  /**
   * The request is refused. The only level permitted on a hard tier, and the default everywhere.
   */
  MANDATORY;

  /**
   * The stricter of two levels — the merge combinator for enforcement (Doc 21 GV-D2).
   *
   * @param other the level to combine with
   * @return whichever level bites harder
   */
  public EnforcementLevel strictest(final EnforcementLevel other) {
    return compareTo(other) >= 0 ? this : other;
  }

  /**
   * The verdict a violation at this level produces.
   *
   * @return the corresponding verdict
   */
  public Verdict verdict() {
    return switch (this) {
      case MANDATORY -> Verdict.DENY;
      case ADVISORY -> Verdict.SOFT_DENY;
      case SHADOW -> Verdict.DRY_RUN;
    };
  }
}

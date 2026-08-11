package io.reliabilityai.gateway.dataplane.governance.api;

/**
 * The four outcomes a governance evaluation can produce. There is no fifth, and there is no null.
 *
 * <p>Declaration order is <b>increasing severity</b>, so combining the outcomes of several violated
 * rules is {@link #worst(Verdict)} — a max. That single fact is what makes the whole evaluation
 * order-independent: a request denied by rule A and shadowed by rule B is denied whichever rule the
 * evaluator happens to reach first.
 *
 * <p>Two of the four admit the request. That is deliberate and is not a hole in deny-by-default: a
 * {@link #SOFT_DENY} or {@link #DRY_RUN} can only be produced by a rule the control plane
 * explicitly authored as non-mandatory, on a tier that permits it, and both carry the violation
 * forward into the decision, the obligations and the audit trail. Nothing is ever admitted
 * <em>silently</em> (Doc 21 GV-INV).
 */
public enum Verdict {

  /** No rule was violated. The request proceeds with the resolved constraints. */
  ALLOW(true),

  /**
   * A rule was violated in shadow. The request proceeds; the would-be denial is recorded so an
   * operator can size the impact of promoting the rule to mandatory.
   */
  DRY_RUN(true),

  /**
   * An advisory rule was violated. The request proceeds, carrying the violation as an obligation,
   * and the breach is audited (Doc 21 §28.3 — a soft breach is never silent).
   */
  SOFT_DENY(true),

  /** A mandatory rule was violated. The request is refused and never reaches the Router. */
  DENY(false);

  private final boolean admits;

  Verdict(final boolean admits) {
    this.admits = admits;
  }

  /**
   * Whether a request with this verdict continues down the pipeline.
   *
   * @return {@code false} only for {@link #DENY}
   */
  public boolean admits() {
    return admits;
  }

  /**
   * The more severe of two verdicts — the combinator for multiple violations.
   *
   * @param other the verdict to combine with
   * @return whichever verdict is more restrictive
   */
  public Verdict worst(final Verdict other) {
    return compareTo(other) >= 0 ? this : other;
  }
}

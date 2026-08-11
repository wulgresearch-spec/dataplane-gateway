package io.reliabilityai.gateway.dataplane.cost.domain;

/**
 * The pricing precedence classes (Doc 22 §CE-D7/§15), highest precedence first. Resolution is
 * strict and deterministic — the highest-precedence <em>entitled</em> class with an authoritative
 * descriptor wins; a list-price fallback when a higher class is entitled-but-unresolved is
 * forbidden (Doc 22 §15, CE-INV).
 */
public enum PricingClass {
  /** Explicit per-tenant negotiated contract price — highest precedence (Doc 22 §16). */
  NEGOTIATED(5),
  /** Enterprise agreement override price (Doc 22 §17). */
  ENTERPRISE(4),
  /** Committed-use / reserved-capacity discount, up to the committed quantity (Doc 22 §18). */
  COMMITTED(3),
  /** Burst / on-demand price above the committed tier (Doc 22 §19). */
  BURST(2),
  /** List price — the default, lowest precedence (Doc 22 §CE-D7). */
  LIST(1);

  private final int precedence;

  PricingClass(final int precedence) {
    this.precedence = precedence;
  }

  /**
   * The precedence rank (higher wins, Doc 22 §15).
   *
   * @return the precedence rank
   */
  public int precedence() {
    return precedence;
  }
}

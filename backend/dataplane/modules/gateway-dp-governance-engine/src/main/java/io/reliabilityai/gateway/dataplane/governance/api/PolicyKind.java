package io.reliabilityai.gateway.dataplane.governance.api;

/**
 * The shape of a policy value, which fixes both how it merges down the hierarchy and how it is
 * checked against a request.
 *
 * <p>Every kind's merge is <b>idempotent, commutative and associative</b>, and every kind's merge
 * moves strictly toward more restrictive. Those two properties together are the whole reason the
 * hierarchy is unambiguous: the effective policy for a scope chain is a fold over the chain, the
 * fold is a semilattice meet, and so the answer cannot depend on evaluation order, on which scopes
 * happened to be populated, or on how many times a scope contributed. "Most restrictive wins" stops
 * being a slogan and becomes an algebraic property that a test can check exhaustively.
 */
public enum PolicyKind {

  /**
   * A set of permitted values; anything outside it is refused. Merges by <b>intersection</b>, so a
   * child scope can only ever narrow what its parent permitted.
   */
  ALLOW_LIST,

  /**
   * A set of forbidden values. Merges by <b>union</b>, so a denial added anywhere in the chain
   * sticks — the rule Google's organization policy calls "deny values always take precedence".
   */
  DENY_LIST,

  /**
   * A capability switch where {@code true} means permitted. Merges by <b>logical AND</b>: once any
   * scope turns a capability off, no descendant can turn it back on.
   */
  CAPABILITY,

  /**
   * A requirement switch where {@code true} means the request must have the property. Merges by
   * <b>logical OR</b>, because imposing a requirement is the restrictive direction.
   */
  REQUIREMENT,

  /**
   * A switch where {@code true} refuses everything in scope — a kill switch or suspension. Merges
   * by <b>logical OR</b>: any scope may pull the cord, no scope may un-pull it.
   */
  PROHIBITION,

  /**
   * A numeric upper bound. Merges by <b>minimum</b>, so the tightest ceiling anywhere in the chain
   * is the one enforced.
   */
  CEILING,

  /**
   * A set of half-open time intervals during which requests are refused. Merges by <b>union</b> of
   * intervals — more blocked time is the restrictive direction.
   */
  WINDOW
}

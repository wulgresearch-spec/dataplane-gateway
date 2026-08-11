package io.reliabilityai.gateway.dataplane.cost.domain;

/**
 * The cost calculation phase (Doc 22 §6/§CE-D5) — a pre-usage admission upper bound, or the exact
 * post-usage cost.
 */
public enum Phase {
  /** Admission upper bound, never-underestimated (Doc 22 §23). */
  PROJECTION,
  /** Exact cost from authoritative usage (Doc 22 §21). */
  ACTUAL
}

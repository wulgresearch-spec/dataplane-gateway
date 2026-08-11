package io.reliabilityai.gateway.dataplane.cost.domain;

/**
 * The three explicitly-separated usage classes (Doc 22 §24) — <b>never conflated</b>. A projection
 * is never emitted as an actual charge; an estimate is never silently treated as authoritative
 * (CE-INV).
 */
public enum UsageConfidence {
  /** Provider returned exact usage (Doc 18 CV-5) — compute actual cost exactly. */
  AUTHORITATIVE,
  /** Pre-usage admission upper bound — never a charge (Doc 22 §23). */
  PROJECTED,
  /** Provider returned only an estimate / usage missing — fail closed by default (Doc 22 §24). */
  ESTIMATED
}

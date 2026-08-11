package io.reliabilityai.gateway.dataplane.governance.api;

/**
 * The counters the Governance Engine publishes (Doc 21 §31).
 *
 * <p>Every label here is drawn from a closed enum, which bounds cardinality by construction. That
 * is not a performance nicety: a governance metric labelled by tenant or by rule id is a metric
 * that grows with the customer base, and the first outage it causes will be in the monitoring
 * system that was supposed to detect outages. High-cardinality decision detail belongs in the audit
 * stream, which is built for it.
 *
 * <p>{@code policyUnavailable} and {@code staleUsage} are the honest signals. They count the times
 * the engine refused because it could not confirm a request was within policy — the deliberate
 * false-denial of Doc 21 GC-10. An operator watching them is watching the snapshot pipeline's
 * health, and the correct response to a spike is to fix the pipeline, never to relax the gate.
 */
public interface PolicyMetrics {

  /** Metrics that go nowhere. */
  PolicyMetrics NO_OP = new PolicyMetrics() {};

  /**
   * Records one completed evaluation.
   *
   * @param verdict what was decided
   * @param latencyNanos the measured evaluation cost
   */
  default void decision(final Verdict verdict, final long latencyNanos) {
    // no-op by default
  }

  /**
   * Records the tier that bound a non-allow verdict.
   *
   * @param domain the binding precedence tier
   * @param verdict the verdict it produced
   */
  default void binding(final PolicyDomain domain, final Verdict verdict) {
    // no-op by default
  }

  /** Records a refusal caused by policy that could not be resolved at all. */
  default void policyUnavailable() {
    // no-op by default
  }

  /** Records a refusal caused by a consumption reading too old to enforce against. */
  default void staleUsage() {
    // no-op by default
  }

  /**
   * Records the outcome of an effective-policy cache lookup.
   *
   * @param hit whether the merged policy was already cached
   */
  default void cacheLookup(final boolean hit) {
    // no-op by default
  }

  /**
   * Records a snapshot installation.
   *
   * @param rollback whether the installation moved to an older generation
   */
  default void snapshotInstalled(final boolean rollback) {
    // no-op by default
  }

  /** Records a snapshot that was offered but rejected as stale or invalid. */
  default void snapshotRejected() {
    // no-op by default
  }
}

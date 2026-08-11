package io.reliabilityai.gateway.dataplane.governance.api;

/**
 * The sink every governance decision is recorded to (Doc 21 §33/§40).
 *
 * <p>Strictly outbound. The engine never reads back from audit, so an audit implementation cannot
 * influence a verdict — not by returning a value, and not by being slow. A sink that threw and was
 * allowed to propagate would let an audit outage start refusing traffic, which trades a
 * record-keeping failure for an availability failure and gets neither right.
 *
 * <p>Implementations must be safe to call from many virtual threads at once and must not block on
 * the request path; the emission itself belongs off the hot path (AD-005).
 */
@FunctionalInterface
public interface PolicyAudit {

  /** A sink that discards records. Correct only before a real audit exporter is wired. */
  PolicyAudit NO_OP = event -> {};

  /**
   * Records one governance decision.
   *
   * @param event the content-free decision fact
   */
  void record(PolicyAuditEvent event);
}

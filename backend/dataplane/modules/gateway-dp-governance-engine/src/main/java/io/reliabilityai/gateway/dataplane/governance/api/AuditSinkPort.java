package io.reliabilityai.gateway.dataplane.governance.api;

/**
 * Emits governance decision facts to the C10 audit stream (Doc 21 §7/§33).
 *
 * <p>Outbound and passive: the engine never reads back from audit, so the sink cannot influence a
 * decision. A sink failure must not change the verdict — the engine isolates it, because losing an
 * audit write is bad, but letting an audit outage decide admissions is worse.
 */
public interface AuditSinkPort {

  /** A sink that discards records — the correct default before an audit exporter is wired. */
  AuditSinkPort NO_OP = record -> {};

  /**
   * Records one governance decision.
   *
   * @param record the content-free decision fact
   */
  void record(GovernanceDecisionRecord record);
}

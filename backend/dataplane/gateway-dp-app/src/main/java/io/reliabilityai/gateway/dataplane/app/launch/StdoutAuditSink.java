package io.reliabilityai.gateway.dataplane.app.launch;

import io.reliabilityai.gateway.dataplane.governance.api.AuditSinkPort;
import io.reliabilityai.gateway.dataplane.governance.api.GovernanceDecisionRecord;
import java.io.PrintStream;
import java.util.Locale;

/**
 * Writes each governance decision to a stream as one line.
 *
 * <p>A real deployment exports these to an append-only store a compliance reviewer can query. On a
 * single node the useful property is simply that the decision is visible at all: every admission,
 * every denial, and the policy versions it was decided against.
 *
 * <p>The record is content-free by construction, so nothing printed here carries tenant prompt or
 * response content.
 */
final class StdoutAuditSink implements AuditSinkPort {

  private final PrintStream out;

  /**
   * Creates a sink writing to the given stream.
   *
   * @param out the destination stream
   */
  StdoutAuditSink(final PrintStream out) {
    this.out = out;
  }

  @Override
  public void record(final GovernanceDecisionRecord record) {
    out.printf(
        Locale.ROOT,
        "AUDIT decision=%s outcome=%s binding=%s tenant=%s correlation=%s versions=%s at=%s%n",
        record.decisionId(),
        record.outcome(),
        record.binding(),
        record.tenantScope(),
        record.correlationId(),
        record.policyVersions(),
        record.decidedAt());
  }
}

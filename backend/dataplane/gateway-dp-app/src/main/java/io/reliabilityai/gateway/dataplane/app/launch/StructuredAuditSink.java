package io.reliabilityai.gateway.dataplane.app.launch;

import io.reliabilityai.gateway.dataplane.governance.api.AuditSinkPort;
import io.reliabilityai.gateway.dataplane.governance.api.GovernanceDecisionRecord;
import java.util.Locale;

/**
 * Emits content-free governance decision facts as structured key-value log records.
 *
 * <p>The default single-node wiring has no durable audit exporter. A production deployment must
 * install an append-only audit sink appropriate for its compliance requirements.
 */
final class StructuredAuditSink implements AuditSinkPort {

  private static final System.Logger LOG =
      System.getLogger(StructuredAuditSink.class.getName());

  @Override
  public void record(final GovernanceDecisionRecord record) {
    LOG.log(
        System.Logger.Level.INFO,
        String.format(
            Locale.ROOT,
            "AUDIT decision=%s outcome=%s binding=%s tenant=%s correlation=%s versions=%s at=%s",
            record.decisionId(),
            record.outcome(),
            record.binding(),
            record.tenantScope(),
            record.correlationId(),
            record.policyVersions(),
            record.decidedAt()));
  }
}

package io.reliabilityai.gateway.dataplane.plugin.api;

import io.reliabilityai.gateway.canonical.audit.AuditRecord;

/**
 * Where content-free plugin audit records go (Doc 28 §46, C10).
 *
 * <p>Audit is a side effect of the runtime, never a gate on it: an implementation that throws or
 * blocks must not be able to fail an invocation, so the runtime swallows sink failures. Losing an
 * audit record is bad; letting an audit sink take down the request path is worse, and Doc 27 OT-A1
 * settles which way that trade goes.
 */
public interface PluginAuditSinkPort {

  /**
   * Records one content-free audit entry.
   *
   * @param record the audit record
   */
  void record(AuditRecord record);

  /** A sink that discards everything, for nodes with no audit destination wired. */
  PluginAuditSinkPort NO_OP = record -> {};
}

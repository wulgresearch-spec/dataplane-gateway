package io.reliabilityai.gateway.dataplane.config.api;

import io.reliabilityai.gateway.canonical.identity.CodeVersion;
import io.reliabilityai.gateway.canonical.identity.ExecutionIdentity;
import io.reliabilityai.gateway.canonical.identity.SnapshotVersion;

/**
 * Outbound seam for the consumer's content-free applied-version audit (Doc 36 §CAU). The consumer
 * records <b>which pinned version served a request</b> — never config content, never a secret,
 * never PII (Doc 36 CFG-A13, Doc 14 §7.1). It authors no audit policy; the record rides the frozen
 * audit mechanism (Doc 07/Doc 08 §10) via the implementing adapter.
 */
public interface ConfigAuditPort {

  /**
   * Records the recorded replay identity applied to a request (Doc 36 §CAU, Doc 29 §CVR) —
   * content-free. The {@code (codeVersion, configVersion, resolvedFlagSetId)} tuple, together with
   * the execution identity, is the recorded anchor a replay reconstructs from (Doc 36 §15).
   *
   * @param executionIdentity the request execution identity
   * @param codeVersion the running code version (part of the replay identity, Doc 29 §CVR)
   * @param configVersion the pinned config version
   * @param resolvedFlagSetId the deterministic resolved-flag-set identity (Doc 36 FFC-5)
   */
  void recordAppliedVersion(
      ExecutionIdentity executionIdentity,
      CodeVersion codeVersion,
      SnapshotVersion configVersion,
      String resolvedFlagSetId);
}

package io.reliabilityai.gateway.dataplane.secrets.api;

/**
 * Outbound seam for the content-free materialization audit (Doc 26 §24, SP-D12, SP-A12). Records
 * the fact/scope/version of each materialization to the frozen audit path (C10, WORM/Merkle, Doc 08
 * §10) — never the credential value (Doc 26 RED-6). Audit-sink failure must never block credential
 * sanitization (Doc 26 SP-D12): implementations are non-throwing toward the materialization flow.
 */
public interface SecretsAuditPort {

  /**
   * Records a content-free materialization record (Doc 26 §6/§24).
   *
   * @param record the content-free record (no credential value)
   */
  void record(MaterializationRecord record);
}

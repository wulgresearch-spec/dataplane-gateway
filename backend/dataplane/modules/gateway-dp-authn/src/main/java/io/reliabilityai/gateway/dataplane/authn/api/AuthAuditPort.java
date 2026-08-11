package io.reliabilityai.gateway.dataplane.authn.api;

/**
 * Outbound seam for the content-free authentication-decision audit (Doc 37 §15, IAU-D11/IAU-A11).
 * The node records the <b>decision</b> — scoped principal id, outcome, reason, key-snapshot version
 * — never the token content, claims-as-content, or any secret (Doc 14 §7.1). It authors no audit
 * policy; the record rides the frozen audit mechanism (Doc 07/Doc 08 §10). An audit-sink fault
 * never blocks the authentication decision (durability is the frozen RPO=0 mechanism's
 * responsibility).
 */
public interface AuthAuditPort {

  /**
   * Records a content-free authentication decision (Doc 37 §15).
   *
   * @param decision the content-free decision record
   */
  void record(AuthenticationDecisionRecord decision);
}

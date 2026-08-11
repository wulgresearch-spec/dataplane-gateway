package io.reliabilityai.gateway.dataplane.governance.api;

/**
 * The inbound port called by the data-plane pipeline after authentication and before routing (Doc
 * 21 §7, GV-D1). Deterministic and fail-closed: identical inputs always produce the identical
 * verdict, and any evaluation failure denies.
 */
public interface GovernanceEnginePort {

  /**
   * Decides whether the request is admitted.
   *
   * @param request the neutral admission question
   * @return permit with resolved constraints, an approval requirement, or a denial naming the
   *     domain
   */
  Decision govern(GovernanceRequest request);
}

package io.reliabilityai.gateway.ports;

import io.reliabilityai.gateway.canonical.context.PrincipalContext;
import io.reliabilityai.gateway.canonical.context.RequestContext;
import io.reliabilityai.gateway.canonical.context.TenantContext;
import io.reliabilityai.gateway.canonical.decision.GovernanceDecision;

/**
 * The inbound governance/PEP port implemented by the Governance Engine (C4, Doc 21, AD-018/019).
 * The sole authorization authority; deny-by-default. Enforces cached policy snapshots (AD-022);
 * never calls the PDP synchronously on the hot path.
 */
public interface GovernancePort {

  /**
   * Admits or denies the request (deny-by-default; Doc 21).
   *
   * @param requestContext the request context
   * @param principal the authenticated principal (from C6, Doc 37)
   * @param tenant the resolved tenant scope
   * @return the governance decision (permit/deny + obligations)
   */
  GovernanceDecision authorize(
      RequestContext requestContext, PrincipalContext principal, TenantContext tenant);
}

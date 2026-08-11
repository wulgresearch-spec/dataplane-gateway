package io.reliabilityai.gateway.dataplane.router.api;

import io.reliabilityai.gateway.canonical.identity.TenantScope;
import java.util.Optional;

/**
 * Outbound seam to the resolved routing policy (Doc 19 §7/§10, C4 Governance/Config, AD-019/022).
 * Read-only, cached per tenant; the Router consumes weights/floors/deny-list, never authors them.
 */
public interface RoutingPolicyPort {

  /**
   * Resolves the routing policy for a tenant (Doc 19 §10).
   *
   * @param tenantScope the tenant scope
   * @return the policy, or empty when none is resident (caller fails closed with STALE_CONSTRAINT)
   */
  Optional<RoutingPolicy> resolve(TenantScope tenantScope);
}

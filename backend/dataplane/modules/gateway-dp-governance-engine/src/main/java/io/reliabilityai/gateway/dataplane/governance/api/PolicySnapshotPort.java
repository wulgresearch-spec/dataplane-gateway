package io.reliabilityai.gateway.dataplane.governance.api;

import io.reliabilityai.gateway.canonical.identity.TenantScope;
import io.reliabilityai.gateway.dataplane.governance.domain.PolicySet;
import java.util.Optional;

/**
 * Reads the tenant-scoped policy snapshot published by the C4 control plane (Doc 21 §7,
 * AD-019/022).
 *
 * <p>A read, never a fetch: implementations serve an already-cached, versioned snapshot and must
 * not perform network I/O on the request path. An empty result means the tenant has no resolvable
 * policy, which the engine treats as {@link DenialReason#POLICY_NOT_FOUND} — never as "no
 * restrictions".
 */
public interface PolicySnapshotPort {

  /**
   * Resolves the policy set for a tenant.
   *
   * @param tenantScope the tenant to resolve policy for
   * @return the tenant's policy set, or empty when none is available
   */
  Optional<PolicySet> policyFor(TenantScope tenantScope);
}

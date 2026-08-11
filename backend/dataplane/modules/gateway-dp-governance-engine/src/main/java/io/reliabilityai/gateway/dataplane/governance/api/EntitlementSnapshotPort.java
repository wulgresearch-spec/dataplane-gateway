package io.reliabilityai.gateway.dataplane.governance.api;

import io.reliabilityai.gateway.canonical.identity.TenantScope;
import io.reliabilityai.gateway.dataplane.governance.domain.Entitlement;
import java.util.Optional;

/**
 * Reads the tenant's quota and budget ceilings from the C8/C5 entitlement snapshot (Doc 21 §7).
 *
 * <p>The engine consumes these limits; it never computes, derives or adjusts them (GV-D6). An empty
 * result means the ceilings are unknown, and an unknown ceiling cannot be enforced — the engine
 * denies rather than admitting spend it cannot bound.
 */
public interface EntitlementSnapshotPort {

  /**
   * Resolves the entitlement for a tenant.
   *
   * @param tenantScope the tenant to resolve entitlement for
   * @return the tenant's quota/budget ceilings, or empty when unavailable
   */
  Optional<Entitlement> entitlementFor(TenantScope tenantScope);
}

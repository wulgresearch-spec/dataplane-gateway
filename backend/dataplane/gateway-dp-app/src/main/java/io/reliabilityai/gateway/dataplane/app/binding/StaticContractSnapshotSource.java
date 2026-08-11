package io.reliabilityai.gateway.dataplane.app.binding;

import io.reliabilityai.gateway.canonical.identity.CanonicalModelId;
import io.reliabilityai.gateway.canonical.identity.Region;
import io.reliabilityai.gateway.canonical.identity.TenantScope;
import io.reliabilityai.gateway.common.Preconditions;
import io.reliabilityai.gateway.dataplane.cost.api.ContractSnapshotPort;
import io.reliabilityai.gateway.dataplane.cost.domain.ContractEntitlement;
import java.util.Map;

/**
 * Resolves a tenant's pricing-class entitlement from an immutable operator-supplied table (Doc 22).
 * A tenant with no explicit contract gets the supplied default entitlement — typically list pricing
 * only, never a discounted class, so a missing contract can never under-charge.
 */
public final class StaticContractSnapshotSource implements ContractSnapshotPort {

  private final Map<TenantScope, ContractEntitlement> byTenant;
  private final ContractEntitlement defaultEntitlement;

  /**
   * Creates the source.
   *
   * @param byTenant the immutable per-tenant entitlement table
   * @param defaultEntitlement the entitlement applied when a tenant has no contract
   */
  public StaticContractSnapshotSource(
      final Map<TenantScope, ContractEntitlement> byTenant,
      final ContractEntitlement defaultEntitlement) {
    this.byTenant = Map.copyOf(Preconditions.requireNonNull(byTenant, "byTenant"));
    this.defaultEntitlement =
        Preconditions.requireNonNull(defaultEntitlement, "defaultEntitlement");
  }

  @Override
  public ContractEntitlement entitlementFor(
      final TenantScope tenantScope, final CanonicalModelId canonicalModelId, final Region region) {
    if (tenantScope == null) {
      return defaultEntitlement;
    }
    return byTenant.getOrDefault(tenantScope, defaultEntitlement);
  }
}

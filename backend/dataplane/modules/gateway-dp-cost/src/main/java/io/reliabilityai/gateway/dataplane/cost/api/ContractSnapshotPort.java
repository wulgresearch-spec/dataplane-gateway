package io.reliabilityai.gateway.dataplane.cost.api;

import io.reliabilityai.gateway.canonical.identity.CanonicalModelId;
import io.reliabilityai.gateway.canonical.identity.Region;
import io.reliabilityai.gateway.canonical.identity.TenantScope;
import io.reliabilityai.gateway.dataplane.cost.domain.ContractEntitlement;

/**
 * The contract-snapshot seam (Doc 22 §16/§17). Delivers the per-tenant {@link ContractEntitlement}
 * (negotiated/enterprise/committed terms) from the C8/C5 contract snapshot (authored upstream). The
 * engine <b>applies</b> entitlements at their precedence — it never negotiates, computes discounts,
 * or infers a contract (Doc 22 §CE-D7). Returns a {@code listOnly} entitlement when the tenant has
 * no higher-precedence terms (LIST is always available).
 */
public interface ContractSnapshotPort {

  /**
   * Returns the tenant's contract entitlement for a model/region (Doc 22 §16).
   *
   * @param tenantScope the tenant scope
   * @param canonicalModelId the canonical model id
   * @param region the region
   * @return the entitlement (never null; list-only when no contract applies)
   */
  ContractEntitlement entitlementFor(
      TenantScope tenantScope, CanonicalModelId canonicalModelId, Region region);
}

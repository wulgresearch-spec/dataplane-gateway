package io.reliabilityai.gateway.canonical.snapshot;

import io.reliabilityai.gateway.canonical.identity.Region;
import io.reliabilityai.gateway.canonical.identity.SnapshotVersion;
import io.reliabilityai.gateway.canonical.identity.TenantScope;
import io.reliabilityai.gateway.common.ContentFree;
import io.reliabilityai.gateway.common.Preconditions;
import java.util.Map;

/**
 * A cached, versioned C6/C7 tenant-scope snapshot (Doc 37 §10/§TRF, AD-022). The IR-6 snapshot
 * contract for tenant resolution (Doc 38 §IR-6): frozen-in-code before the AuthN node consumes it.
 * Maps an authenticated {@code principalId} to its resolved {@link TenantScope} ({@code org →
 * tenant → workspace → project}). Owned by the Identity &amp; Tenancy Service (Doc 06 §9.5);
 * consumed read-only, resolved <b>only after</b> successful authentication (Doc 32 §SPT, Doc 37
 * TRF-1). Region-confined (AD-014). Content-free — scoped ids only.
 *
 * @param version the pinned snapshot version
 * @param region the residency region (AD-014)
 * @param principalToTenant {@code principalId → tenant scope} (defensively copied)
 */
public record TenantScopeSnapshot(
    SnapshotVersion version, Region region, Map<String, TenantScope> principalToTenant)
    implements ContentFree {

  /** Compact constructor validating identity and defensively copying the mapping. */
  public TenantScopeSnapshot {
    Preconditions.requireNonNull(version, "version");
    Preconditions.requireNonNull(region, "region");
    // Inlined copy, not Preconditions.immutableMap — see that method's javadoc (EI_EXPOSE_REP).
    principalToTenant = principalToTenant == null ? Map.of() : Map.copyOf(principalToTenant);
  }
}

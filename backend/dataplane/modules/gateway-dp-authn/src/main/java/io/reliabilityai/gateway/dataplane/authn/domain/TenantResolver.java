package io.reliabilityai.gateway.dataplane.authn.domain;

import io.reliabilityai.gateway.canonical.identity.PrincipalId;
import io.reliabilityai.gateway.canonical.identity.TenantScope;
import io.reliabilityai.gateway.canonical.snapshot.TenantScopeSnapshot;
import io.reliabilityai.gateway.common.Preconditions;
import java.util.Optional;

/**
 * Resolves an authenticated principal's tenant scope from the pinned tenant-scope snapshot (Doc 37
 * §10/§TRF). Pure and deterministic — a lookup in the cached snapshot, never an online call.
 * Resolution happens <b>only after</b> successful authentication (Doc 32 §SPT, Doc 37 TRF-1); an
 * unresolvable principal yields empty, and the caller fails closed (Doc 37 TRF-3).
 */
public final class TenantResolver {

  /**
   * Resolves the tenant scope for a principal, if the snapshot maps it (Doc 37 §10).
   *
   * @param snapshot the pinned tenant-scope snapshot
   * @param principalId the authenticated principal id
   * @return the tenant scope, or empty when the principal has no resolvable scope
   */
  public Optional<TenantScope> resolve(
      final TenantScopeSnapshot snapshot, final PrincipalId principalId) {
    Preconditions.requireNonNull(snapshot, "snapshot");
    Preconditions.requireNonNull(principalId, "principalId");
    return Optional.ofNullable(snapshot.principalToTenant().get(principalId.value()));
  }
}

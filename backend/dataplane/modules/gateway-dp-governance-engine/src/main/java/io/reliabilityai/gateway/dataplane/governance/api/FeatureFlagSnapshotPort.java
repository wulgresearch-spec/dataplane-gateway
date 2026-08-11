package io.reliabilityai.gateway.dataplane.governance.api;

import io.reliabilityai.gateway.canonical.identity.TenantScope;
import java.util.Optional;

/**
 * Reads flag state from the C15 Configuration snapshot (Doc 21 §7, GV-D7).
 *
 * <p>The engine evaluates flags; it never defines or manages them. The result is an {@link
 * Optional} rather than a bare boolean because "flag not present" and "flag off" must stay
 * distinguishable for audit — both fail safe to off, but only one indicates a distribution problem.
 * A flag can never enable a bypass of a hard invariant; compliance, residency and security are not
 * flag-gated.
 */
public interface FeatureFlagSnapshotPort {

  /**
   * Resolves whether a feature is enabled for a tenant.
   *
   * @param tenantScope the tenant the flag is evaluated for
   * @param feature the canonical feature name
   * @return the flag state, or empty when the flag is absent from the snapshot
   */
  Optional<Boolean> enabled(TenantScope tenantScope, String feature);
}

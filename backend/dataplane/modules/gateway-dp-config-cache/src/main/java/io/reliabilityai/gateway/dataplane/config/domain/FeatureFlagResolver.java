package io.reliabilityai.gateway.dataplane.config.domain;

import io.reliabilityai.gateway.canonical.identity.TenantScope;
import io.reliabilityai.gateway.canonical.snapshot.FeatureFlagDefinition;
import io.reliabilityai.gateway.common.Preconditions;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Resolves feature-flag definitions once, deterministically, at pin time (Doc 36 §FFC). A flag
 * resolves {@code true} for a tenant iff a deterministic bucket of {@code (flagId, org, tenant)} is
 * strictly less than its rollout basis points (Doc 36 FFC-3) — tenant-scoped (Doc 36 §TFI, AD-021).
 *
 * <p>Resolution uses no {@code Math.random}, no {@code Random}, and no wall-clock (Doc 11 R-063),
 * so identical inputs always yield an identical {@link ResolvedFlagSet}, which is then recorded for
 * replay (Doc 36 FFC-5, Doc 32 §CRS). This resolver authors no flag and no default — it only
 * applies the C15-authored definitions (Doc 36 §CAB).
 */
public final class FeatureFlagResolver {

  /**
   * Resolves all flag definitions for a tenant scope into an immutable, recorded set (Doc 36
   * FFC-2).
   *
   * @param definitions the C15-authored flag definitions from the pinned snapshot
   * @param tenantScope the pinned tenant scope (present only post-C6, Doc 32 §SPT)
   * @return the deterministic resolved flag set
   */
  public ResolvedFlagSet resolve(
      final List<FeatureFlagDefinition> definitions, final TenantScope tenantScope) {
    Preconditions.requireNonNull(definitions, "definitions");
    Preconditions.requireNonNull(tenantScope, "tenantScope");
    final Map<String, Boolean> resolved = new LinkedHashMap<>();
    for (final FeatureFlagDefinition def : definitions) {
      resolved.put(def.flagId(), resolveOne(def, tenantScope));
    }
    return new ResolvedFlagSet(resolved);
  }

  private static boolean resolveOne(
      final FeatureFlagDefinition def, final TenantScope tenantScope) {
    if (def.rolloutBasisPoints() <= 0) {
      return false;
    }
    if (def.rolloutBasisPoints() >= FeatureFlagDefinition.FULLY_ON) {
      return true;
    }
    final int bucket =
        DeterministicDigest.bucket(def.flagId(), tenantScope.org(), tenantScope.tenant());
    return bucket < def.rolloutBasisPoints();
  }
}

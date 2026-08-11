package io.reliabilityai.gateway.dataplane.governance.domain;

import io.reliabilityai.gateway.canonical.identity.CanonicalModelId;
import io.reliabilityai.gateway.canonical.identity.SnapshotVersion;
import io.reliabilityai.gateway.common.Preconditions;
import java.util.Set;

/**
 * The tenant-scoped, immutable policy snapshot the engine consumes (Doc 21 §6, GV-D3).
 *
 * <p>Read-only and tenant-scoped by construction: one tenant's policy set contains no reference to
 * another, so a tenant's policy cannot influence another's decision (AD-021). A policy change is a
 * new snapshot version, never an in-place edit (GV-D8 §8) — nothing here is mutable.
 *
 * <p>Every collection is an <b>allow-list</b>. That is deliberate: a missing entry denies rather
 * than permits, so a policy that failed to distribute fully cannot silently widen access.
 *
 * @param version the policy snapshot version this set came from
 * @param tenantEnabled whether the tenant is active at all
 * @param allowedModels canonical models the tenant may use
 * @param allowedRegions regions the tenant may execute in (residency scope, GV-D9)
 * @param allowedCapabilities canonical capabilities the tenant may request
 * @param allowedTools canonical tools the tenant may invoke (GV-D10)
 * @param complianceRegimes regimes the tenant is attested for (GV-D8)
 * @param approvalRequiredCapabilities capabilities admitted only after human approval
 * @param gatedFeatures features whose flag must be enabled before admission (GV-D7)
 * @param maxRequestsPerWindow the admission-time request-rate ceiling
 */
public record PolicySet(
    SnapshotVersion version,
    boolean tenantEnabled,
    Set<CanonicalModelId> allowedModels,
    Set<String> allowedRegions,
    Set<String> allowedCapabilities,
    Set<String> allowedTools,
    Set<String> complianceRegimes,
    Set<String> approvalRequiredCapabilities,
    Set<String> gatedFeatures,
    long maxRequestsPerWindow) {

  /** Validates the policy set. */
  public PolicySet {
    Preconditions.requireNonNull(version, "version");
    allowedModels = Set.copyOf(Preconditions.requireNonNull(allowedModels, "allowedModels"));
    allowedRegions = Set.copyOf(Preconditions.requireNonNull(allowedRegions, "allowedRegions"));
    allowedCapabilities =
        Set.copyOf(Preconditions.requireNonNull(allowedCapabilities, "allowedCapabilities"));
    allowedTools = Set.copyOf(Preconditions.requireNonNull(allowedTools, "allowedTools"));
    complianceRegimes =
        Set.copyOf(Preconditions.requireNonNull(complianceRegimes, "complianceRegimes"));
    approvalRequiredCapabilities =
        Set.copyOf(
            Preconditions.requireNonNull(
                approvalRequiredCapabilities, "approvalRequiredCapabilities"));
    gatedFeatures = Set.copyOf(Preconditions.requireNonNull(gatedFeatures, "gatedFeatures"));
    Preconditions.requireNonNegative(maxRequestsPerWindow, "maxRequestsPerWindow");
  }
}

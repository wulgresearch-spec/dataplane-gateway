package io.reliabilityai.gateway.canonical.identity;

import io.reliabilityai.gateway.common.Preconditions;

/**
 * Snapshot version — a pinned, immutable version of a cached snapshot (AD-022, Doc 29 §SCC).
 * Recorded per request for deterministic replay (Doc 29 §CVR, Doc 32 §CRS).
 *
 * @param name the snapshot name (e.g. config, policy, capability, verification-key)
 * @param version the immutable version identifier
 */
public record SnapshotVersion(String name, String version) {

  /** Compact constructor validating name and version. */
  public SnapshotVersion {
    Preconditions.requireNonBlank(name, "name");
    Preconditions.requireNonBlank(version, "version");
  }
}

package io.reliabilityai.gateway.dataplane.router.api;

import io.reliabilityai.gateway.canonical.identity.SnapshotVersion;
import io.reliabilityai.gateway.common.Preconditions;
import java.util.List;

/**
 * A cached, versioned snapshot of the canonical provider-capability registry (Doc 19 §6/§9,
 * AD-022). Owned by the Provider Registry (C1); consumed read-only by the Router with no hot-path
 * network call. Immutable; descriptors defensively copied.
 *
 * @param version the pinned snapshot version (recorded in the decision for reproducibility, Doc 19
 *     §14)
 * @param descriptors the canonical capability descriptors (the bounded candidate set, Doc 19 §9)
 */
public record CapabilityRegistrySnapshot(
    SnapshotVersion version, List<CapabilityDescriptor> descriptors) {

  /** Compact constructor validating the version and defensively copying descriptors. */
  public CapabilityRegistrySnapshot {
    Preconditions.requireNonNull(version, "version");
    descriptors = descriptors == null ? List.of() : List.copyOf(descriptors);
  }
}

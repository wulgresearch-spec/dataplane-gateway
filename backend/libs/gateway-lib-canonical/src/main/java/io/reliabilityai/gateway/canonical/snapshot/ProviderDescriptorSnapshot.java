package io.reliabilityai.gateway.canonical.snapshot;

import io.reliabilityai.gateway.canonical.identity.SnapshotVersion;
import io.reliabilityai.gateway.common.Preconditions;
import java.util.Map;

/**
 * A cached, versioned provider-descriptor snapshot (Doc 33 §10.6, Doc 06 §9.2, AD-022). Owned by
 * the Provider Registry (C1 control plane); consumed read-only by the Router/Adapter; pinned per
 * request (Doc 29 §SCC). Immutable; descriptors copied.
 *
 * @param version the pinned snapshot version
 * @param descriptors canonical-model-id → opaque descriptor (read-only)
 */
public record ProviderDescriptorSnapshot(SnapshotVersion version, Map<String, String> descriptors) {

  /** Compact constructor validating the version and defensively copying descriptors. */
  public ProviderDescriptorSnapshot {
    Preconditions.requireNonNull(version, "version");
    // Inlined copy, not Preconditions.immutableMap — see that method's javadoc (EI_EXPOSE_REP).
    descriptors = descriptors == null ? Map.of() : Map.copyOf(descriptors);
  }
}

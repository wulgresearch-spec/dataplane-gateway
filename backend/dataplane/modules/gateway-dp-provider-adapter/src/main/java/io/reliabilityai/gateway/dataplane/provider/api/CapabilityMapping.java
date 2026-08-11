package io.reliabilityai.gateway.dataplane.provider.api;

import io.reliabilityai.gateway.canonical.identity.CanonicalModelId;
import io.reliabilityai.gateway.canonical.identity.SnapshotVersion;
import io.reliabilityai.gateway.common.Preconditions;
import java.util.Set;

/**
 * A <em>read-only</em> capability projection the adapter <b>consumes</b> from the Provider Registry
 * snapshot (Doc 25 §14.1 CAP-1..6, AD-022). The Provider Registry (C1 control plane, Doc 06 §9.2)
 * is the sole owner/author of capability descriptors; the adapter <b>never creates, authors,
 * discovers, infers, probes, or mutates</b> one (CAP-3). A missing/stale mapping ⇒ fail closed
 * (CAP-5). Immutable; the capability set is defensively copied.
 *
 * @param canonicalModelId the canonical model this mapping is pinned to (Doc 19 §9.2)
 * @param snapshotVersion the pinned capability-snapshot version (stamped for replay, Doc 25 §25)
 * @param providerApiVersion the pinned provider API version (Doc 25 PA-D10)
 * @param capabilities the canonical capability names this route supports (read-only)
 */
public record CapabilityMapping(
    CanonicalModelId canonicalModelId,
    SnapshotVersion snapshotVersion,
    PinnedVersion providerApiVersion,
    Set<String> capabilities) {

  /** Compact constructor validating fields and defensively copying the capability set. */
  public CapabilityMapping {
    Preconditions.requireNonNull(canonicalModelId, "canonicalModelId");
    Preconditions.requireNonNull(snapshotVersion, "snapshotVersion");
    Preconditions.requireNonNull(providerApiVersion, "providerApiVersion");
    capabilities = capabilities == null ? Set.of() : Set.copyOf(capabilities);
  }

  /**
   * Whether this route supports the given canonical capability (read-only consumption, CAP-2).
   *
   * @param capability the canonical capability name
   * @return {@code true} if present in the snapshot projection
   */
  public boolean supports(final String capability) {
    return capabilities.contains(capability);
  }
}

package io.reliabilityai.gateway.canonical.snapshot;

import io.reliabilityai.gateway.canonical.identity.SnapshotVersion;
import io.reliabilityai.gateway.common.Preconditions;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * A cached, versioned provider-capabilities snapshot (Doc 33 §10.6, Doc 06 §9.2, AD-022). The
 * Provider Registry (C1 control plane) is the sole owner/author; the runtime consumes it read-only
 * (Doc 25 §14.1 CAP-1..6) and never authors/discovers capabilities. Immutable; capabilities copied.
 *
 * @param version the pinned snapshot version
 * @param capabilities canonical-model-id → capability names (read-only)
 */
public record ProviderCapabilitiesSnapshot(
    SnapshotVersion version, Map<String, List<String>> capabilities) {

  /** Compact constructor validating the version and defensively deep-copying capabilities. */
  public ProviderCapabilitiesSnapshot {
    Preconditions.requireNonNull(version, "version");
    // The copy is deep, and that is not incidental. This is the one canonical type whose values are
    // themselves collections: a shallow Map.copyOf freezes the outer map but leaves every
    // capability list writable through the caller's own reference, and the runtime consumes
    // capabilities read-only and must never author them (Doc 25 §14.1 CAP-1..6). The copy is also
    // inlined rather than routed through Preconditions; see that class's javadoc (EI_EXPOSE_REP).
    final Map<String, List<String>> copied = new HashMap<>();
    if (capabilities != null) {
      for (final Map.Entry<String, List<String>> entry : capabilities.entrySet()) {
        copied.put(entry.getKey(), List.copyOf(entry.getValue()));
      }
    }
    capabilities = Map.copyOf(copied);
  }
}

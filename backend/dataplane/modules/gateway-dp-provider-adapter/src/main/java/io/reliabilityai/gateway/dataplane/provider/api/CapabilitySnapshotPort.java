package io.reliabilityai.gateway.dataplane.provider.api;

import io.reliabilityai.gateway.canonical.decision.RouteTarget;
import java.util.Optional;

/**
 * The read-only capability seam (Doc 25 §14.1 CAP-1..6, PA-D3, AD-022). The adapter <b>consumes
 * immutable capability snapshots only</b>, pinned per invocation, and derives a read-only {@link
 * CapabilityMapping}. It <b>never creates, authors, discovers, infers, probes, or mutates</b> a
 * capability descriptor (CAP-3); the Provider Registry (C1 control plane, Doc 06 §9.2) is the sole
 * owner. A missing/stale snapshot ⇒ <b>fail closed</b> (CAP-5) — the adapter never guesses or
 * probes.
 */
public interface CapabilitySnapshotPort {

  /**
   * Returns the read-only capability mapping for the given route from the pinned snapshot (CAP-2).
   *
   * @param routeTarget the selected route target
   * @return the read-only capability mapping, or empty ⇒ fail closed (CAP-5)
   */
  Optional<CapabilityMapping> mappingFor(RouteTarget routeTarget);
}

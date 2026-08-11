package io.reliabilityai.gateway.dataplane.router.api;

import java.util.Optional;

/**
 * Outbound seam to the cached canonical capability registry (Doc 19 §7, C1, AD-022). Read-only; the
 * Router makes no hot-path network call — an empty snapshot fails closed (Doc 19 §17/§18).
 */
public interface CapabilityRegistryPort {

  /**
   * Returns the current last-known-good capability snapshot (AD-022).
   *
   * @return the snapshot, or empty when none is resident (caller fails closed with
   *     STALE_CONSTRAINT)
   */
  Optional<CapabilityRegistrySnapshot> currentSnapshot();
}

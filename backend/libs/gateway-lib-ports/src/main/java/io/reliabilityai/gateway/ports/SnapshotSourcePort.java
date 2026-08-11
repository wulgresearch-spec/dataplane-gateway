package io.reliabilityai.gateway.ports;

import io.reliabilityai.gateway.canonical.identity.SnapshotVersion;
import java.util.Optional;

/**
 * A read-only source of cached, versioned, last-known-good snapshots (AD-022, Doc 36). The data
 * plane consumes snapshots via this port and NEVER makes a synchronous control-plane call on the
 * hot path (Doc 36 CGA-6). Snapshots are pinned per request after C6 (Doc 32 §SPT).
 *
 * @param <T> the snapshot type (e.g. config, policy, provider-capability, verification-key)
 */
public interface SnapshotSourcePort<T> {

  /**
   * Returns the current last-known-good snapshot, if available (AD-017).
   *
   * @return the snapshot, or empty when none is available (caller fails closed on a required
   *     snapshot)
   */
  Optional<T> current();

  /**
   * Returns the pinned snapshot for a specific version (for per-request pinning / replay; Doc 29
   * §CVR).
   *
   * @param version the pinned snapshot version
   * @return the snapshot for that version, or empty if not resident
   */
  Optional<T> pinned(SnapshotVersion version);
}

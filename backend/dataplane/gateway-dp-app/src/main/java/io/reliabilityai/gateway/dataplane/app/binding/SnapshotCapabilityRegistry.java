package io.reliabilityai.gateway.dataplane.app.binding;

import io.reliabilityai.gateway.common.Preconditions;
import io.reliabilityai.gateway.dataplane.router.api.CapabilityRegistryPort;
import io.reliabilityai.gateway.dataplane.router.api.CapabilityRegistrySnapshot;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

/**
 * The capability registry for the single VPS: an immutable {@link CapabilityRegistrySnapshot}
 * pinned in memory and swapped atomically on republish (Doc 19). Reads are lock-free and always
 * observe one whole snapshot — never a half-applied one — so routing decisions within a request are
 * self-consistent.
 *
 * <p>Fail-closed: before any snapshot is applied {@link #currentSnapshot()} is empty, and the
 * router surfaces {@code STALE_CONSTRAINT} rather than routing against an unknown capability set.
 */
public final class SnapshotCapabilityRegistry implements CapabilityRegistryPort {

  private final AtomicReference<CapabilityRegistrySnapshot> pinned = new AtomicReference<>();

  /**
   * Creates the registry with an initial pinned snapshot.
   *
   * @param initial the capability snapshot to pin at startup
   */
  public SnapshotCapabilityRegistry(final CapabilityRegistrySnapshot initial) {
    pinned.set(Preconditions.requireNonNull(initial, "initial"));
  }

  @Override
  public Optional<CapabilityRegistrySnapshot> currentSnapshot() {
    return Optional.ofNullable(pinned.get());
  }

  /**
   * Atomically replaces the pinned snapshot (control-plane republish).
   *
   * @param snapshot the newly validated capability snapshot
   */
  public void applyPublished(final CapabilityRegistrySnapshot snapshot) {
    pinned.set(Preconditions.requireNonNull(snapshot, "snapshot"));
  }
}

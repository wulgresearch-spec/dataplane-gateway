package io.reliabilityai.gateway.dataplane.app.binding;

import io.reliabilityai.gateway.canonical.identity.SnapshotVersion;
import io.reliabilityai.gateway.common.Preconditions;
import io.reliabilityai.gateway.ports.SnapshotSourcePort;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

/**
 * A generic pinned snapshot source: holds one immutable snapshot and its version, swapped
 * atomically on republish (Doc 15 §snapshot-pinning). Used wherever a module needs a versioned
 * snapshot that the operator supplies at startup rather than fetches on the request path —
 * verification keys and tenant scopes today.
 *
 * <p>{@link #pinned(SnapshotVersion)} answers only for the version actually held. A caller asking
 * for a superseded version gets empty rather than a silently newer snapshot, which is what makes a
 * pinned request reproducible.
 *
 * @param <T> the snapshot type
 */
public final class PinnedSnapshotSource<T> implements SnapshotSourcePort<T> {

  private record Held<T>(SnapshotVersion version, T value) {}

  private final AtomicReference<Held<T>> held = new AtomicReference<>();

  /**
   * Creates the source with an initial pinned snapshot.
   *
   * @param version the snapshot version
   * @param value the snapshot value
   */
  public PinnedSnapshotSource(final SnapshotVersion version, final T value) {
    applyPublished(version, value);
  }

  @Override
  public Optional<T> current() {
    final Held<T> snapshot = held.get();
    return snapshot == null ? Optional.empty() : Optional.of(snapshot.value());
  }

  @Override
  public Optional<T> pinned(final SnapshotVersion version) {
    Preconditions.requireNonNull(version, "version");
    final Held<T> snapshot = held.get();
    if (snapshot != null && snapshot.version().equals(version)) {
      return Optional.of(snapshot.value());
    }
    return Optional.empty();
  }

  /**
   * Atomically replaces the pinned snapshot.
   *
   * @param version the new snapshot version
   * @param value the new snapshot value
   */
  public void applyPublished(final SnapshotVersion version, final T value) {
    Preconditions.requireNonNull(version, "version");
    Preconditions.requireNonNull(value, "value");
    held.set(new Held<>(version, value));
  }
}

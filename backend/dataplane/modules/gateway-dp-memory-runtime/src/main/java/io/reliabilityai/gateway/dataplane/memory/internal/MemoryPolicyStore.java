package io.reliabilityai.gateway.dataplane.memory.internal;

import io.reliabilityai.gateway.common.Preconditions;
import io.reliabilityai.gateway.dataplane.memory.domain.MemoryPolicySnapshot;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Holds the live policy snapshot and swaps it atomically (MEM-18, MEM-27).
 *
 * <p><b>Lock-free on read.</b> A reader performs one volatile read of an {@link AtomicReference}
 * and walks an immutable structure. There is no lock, no copy and no possibility of observing a
 * half-installed snapshot — a reader sees the old snapshot or the new one, never a mixture. On the
 * hot path this is a few nanoseconds, which is what lets policy be consulted on every single memory
 * operation rather than cached and allowed to go stale.
 *
 * <p>Deliberately the same shape as the governance engine's {@code PolicyStore}. An operator who
 * has learned how policy is installed and rolled back there should not have to learn a second
 * mechanism here, and a reviewer comparing the two should find them boringly similar.
 *
 * <p>Installation is serialised — it is rare, off the hot path, and needs to be — but installation
 * never blocks a reader.
 */
public final class MemoryPolicyStore {

  private final AtomicReference<MemoryPolicySnapshot> current =
      new AtomicReference<>(MemoryPolicySnapshot.EMPTY);
  private final Deque<MemoryPolicySnapshot> history = new ArrayDeque<>();
  private final int historyDepth;

  /**
   * Creates a store that retains a bounded rollback history.
   *
   * @param historyDepth how many superseded snapshots to keep for rollback; zero keeps none
   */
  public MemoryPolicyStore(final int historyDepth) {
    if (historyDepth < 0) {
      throw new IllegalArgumentException("historyDepth must not be negative");
    }
    this.historyDepth = historyDepth;
  }

  /**
   * Returns the live snapshot.
   *
   * <p>The hot-path method. One volatile read, no allocation, no lock.
   *
   * @return the current snapshot, never null
   */
  public MemoryPolicySnapshot current() {
    return current.get();
  }

  /**
   * Installs a snapshot, if it is newer than the one in force.
   *
   * <p>Refuses to go backwards. A stale snapshot arriving late — a slow control-plane push, a
   * retried delivery — would otherwise silently revert policy, and reverting policy is how a
   * tightening made after an incident gets undone by a network hiccup.
   *
   * @param snapshot the compiled snapshot to install
   * @return true when it was installed, false when it was not newer
   */
  public synchronized boolean install(final MemoryPolicySnapshot snapshot) {
    Preconditions.requireNonNull(snapshot, "snapshot");
    final MemoryPolicySnapshot live = current.get();
    if (snapshot.version() <= live.version()) {
      return false;
    }
    if (historyDepth > 0) {
      history.addFirst(live);
      while (history.size() > historyDepth) {
        history.removeLast();
      }
    }
    current.set(snapshot);
    return true;
  }

  /**
   * Rolls back to a retained snapshot.
   *
   * <p>The rolled-back snapshot is re-stamped with a version above the live one, rather than
   * restoring its original version. Otherwise the rollback would itself be refused by {@link
   * #install}'s monotonicity rule, and a rollback that cannot be installed is not a rollback.
   *
   * @param version the version to return to
   * @return the newly live snapshot, or empty when that version is no longer retained
   */
  public synchronized Optional<MemoryPolicySnapshot> rollbackTo(final long version) {
    for (final MemoryPolicySnapshot candidate : history) {
      if (candidate.version() == version) {
        final MemoryPolicySnapshot restamped =
            new MemoryPolicySnapshot(
                current.get().version() + 1,
                candidate.byScope(),
                candidate.byType(),
                candidate.fallback());
        history.addFirst(current.get());
        while (history.size() > historyDepth) {
          history.removeLast();
        }
        current.set(restamped);
        return Optional.of(restamped);
      }
    }
    return Optional.empty();
  }

  /**
   * Reports whether any policy has been installed.
   *
   * @return false while the store is still serving the empty, fail-closed snapshot
   */
  public boolean installed() {
    return current.get().installed();
  }

  /**
   * Returns the retained snapshots, newest first.
   *
   * @return the rollback history
   */
  public synchronized List<MemoryPolicySnapshot> history() {
    return List.copyOf(history);
  }
}

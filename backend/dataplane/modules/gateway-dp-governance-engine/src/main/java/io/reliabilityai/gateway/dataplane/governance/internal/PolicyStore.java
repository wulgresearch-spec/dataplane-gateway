package io.reliabilityai.gateway.dataplane.governance.internal;

import io.reliabilityai.gateway.common.Preconditions;
import io.reliabilityai.gateway.dataplane.governance.api.PolicyVersion;
import io.reliabilityai.gateway.dataplane.governance.domain.PolicySnapshot;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Holds the generation currently in force, plus enough recent history to roll back to.
 *
 * <p><b>Not a persistent store.</b> The Governance Engine owns no durable state (Doc 21 §34,
 * GV-A10); this is an in-memory reference cell. Everything it holds is reconstructed at startup by
 * asking the control plane for the current bundle, which is what makes a restarted node correct
 * rather than merely fast.
 *
 * <p><b>Thread model.</b> One {@link AtomicReference} to an immutable {@link Generation}. Readers
 * do a single volatile read and are done — no lock, no version check, no coordination with a
 * writer. Installation is a {@code compareAndSet} that publishes the snapshot and its cache
 * together as one object, so no thread can ever observe a new snapshot paired with the previous
 * snapshot's cache. Requests in flight when a swap happens finish against the generation they
 * started on, which is why the version each one recorded is a true statement about what governed
 * it.
 *
 * <p><b>Monotonic by default, backwards only on request.</b> {@link #install} refuses a generation
 * that is not newer than the one in force. Without that rule, a slow or replaying publisher could
 * re-deliver an older bundle and silently undo a tightening — reopening the exposure window that
 * Doc 21 §12.1 requires to be bounded. Rolling back is a real operational need, so {@link
 * #rollbackTo} exists, but it is a separate, deliberate act that an operator has to ask for by
 * name.
 */
public final class PolicyStore {

  private final AtomicReference<Generation> current;
  private final Deque<PolicySnapshot> history = new ArrayDeque<>();
  private final int cacheCapacity;
  private final int historyDepth;

  /**
   * Creates a store holding the empty generation.
   *
   * @param cacheCapacity the per-generation effective-policy cache bound
   * @param historyDepth how many superseded generations stay available to roll back to
   */
  public PolicyStore(final int cacheCapacity, final int historyDepth) {
    if (cacheCapacity <= 0) {
      throw new IllegalArgumentException("cacheCapacity must be positive");
    }
    if (historyDepth < 0) {
      throw new IllegalArgumentException("historyDepth must not be negative");
    }
    this.cacheCapacity = cacheCapacity;
    this.historyDepth = historyDepth;
    this.current = new AtomicReference<>(Generation.of(PolicySnapshot.empty(), cacheCapacity));
  }

  /**
   * The generation in force. A single volatile read; the request path's only interaction with
   * mutable state.
   *
   * @return the current generation
   */
  public Generation current() {
    return current.get();
  }

  /**
   * Whether any real generation has been installed yet.
   *
   * <p>The engine needs this to tell "policy says nothing about this request" apart from "no policy
   * has arrived on this node". The first is a legitimate answer; the second is an unconfigured
   * gate, and admitting traffic through one would be the silent bypass GV-INV exists to prevent.
   *
   * @return {@code true} once a generation has been installed
   */
  public boolean isInstalled() {
    return !PolicyVersion.NONE.equals(current.get().snapshot().version());
  }

  /**
   * Installs a newer generation, publishing snapshot and cache atomically.
   *
   * @param snapshot the compiled generation to put in force
   * @return {@code true} when installed, {@code false} when refused as not newer
   */
  public boolean install(final PolicySnapshot snapshot) {
    Preconditions.requireNonNull(snapshot, "snapshot");
    while (true) {
      final Generation existing = current.get();
      if (!snapshot.version().isNewerThan(existing.snapshot().version())) {
        return false;
      }
      if (current.compareAndSet(existing, Generation.of(snapshot, cacheCapacity))) {
        remember(existing.snapshot());
        return true;
      }
    }
  }

  /**
   * Rolls back to a previously-installed generation.
   *
   * <p>Deliberately bypasses the monotonicity rule, because that is the entire point of a rollback.
   * The target must be one this node actually served, so an operator under pressure cannot roll
   * "back" to a generation that was never in force here and quietly change policy while appearing
   * to revert it.
   *
   * @param version the generation to restore
   * @return the restored snapshot, or empty when that generation is not in history
   */
  public Optional<PolicySnapshot> rollbackTo(final PolicyVersion version) {
    Preconditions.requireNonNull(version, "version");
    synchronized (history) {
      for (final PolicySnapshot candidate : history) {
        if (candidate.version().equals(version)) {
          current.set(Generation.of(candidate, cacheCapacity));
          return Optional.of(candidate);
        }
      }
    }
    return Optional.empty();
  }

  /**
   * Finds a generation this node has served — the one in force, or a retained predecessor.
   *
   * <p>Exists so that simulation can ask "what would this request have done under last week's
   * policy" against a generation that genuinely was in force here, rather than against a
   * reconstruction that might differ from it in ways nobody would notice.
   *
   * @param version the generation wanted
   * @return the snapshot, or empty when it is neither current nor retained
   */
  public Optional<PolicySnapshot> snapshotOf(final PolicyVersion version) {
    Preconditions.requireNonNull(version, "version");
    final PolicySnapshot inForce = current.get().snapshot();
    if (inForce.version().equals(version)) {
      return Optional.of(inForce);
    }
    synchronized (history) {
      for (final PolicySnapshot candidate : history) {
        if (candidate.version().equals(version)) {
          return Optional.of(candidate);
        }
      }
    }
    return Optional.empty();
  }

  /**
   * The superseded generations still available to roll back to, most recent first.
   *
   * @return the retained versions
   */
  public List<PolicyVersion> rollbackTargets() {
    synchronized (history) {
      return history.stream().map(PolicySnapshot::version).toList();
    }
  }

  /**
   * Retains a superseded generation. Guarded by a lock, which is safe because this runs only on the
   * reload path — never while serving a request — so it can neither contend with readers nor pin a
   * virtual thread carrying one.
   */
  private void remember(final PolicySnapshot superseded) {
    if (historyDepth == 0 || PolicyVersion.NONE.equals(superseded.version())) {
      return;
    }
    synchronized (history) {
      history.addFirst(superseded);
      while (history.size() > historyDepth) {
        history.removeLast();
      }
    }
  }

  /**
   * A snapshot and the fold cache that belongs to it, published as one indivisible unit.
   *
   * @param snapshot the compiled generation
   * @param cache the fold cache for that generation
   */
  public record Generation(PolicySnapshot snapshot, PolicyCache cache) {

    /** Validates the pairing. */
    public Generation {
      Preconditions.requireNonNull(snapshot, "snapshot");
      Preconditions.requireNonNull(cache, "cache");
      if (cache.snapshot() != snapshot) {
        throw new IllegalArgumentException("cache does not belong to this snapshot");
      }
    }

    /**
     * Pairs a snapshot with a fresh cache.
     *
     * @param snapshot the compiled generation
     * @param cacheCapacity the fold cache bound
     * @return the generation
     */
    public static Generation of(final PolicySnapshot snapshot, final int cacheCapacity) {
      return new Generation(snapshot, new PolicyCache(snapshot, cacheCapacity));
    }
  }
}

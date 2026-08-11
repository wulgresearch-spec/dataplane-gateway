package io.reliabilityai.gateway.dataplane.governance.internal;

import io.reliabilityai.gateway.common.Preconditions;
import io.reliabilityai.gateway.dataplane.governance.api.ScopeChain;
import io.reliabilityai.gateway.dataplane.governance.domain.EffectivePolicy;
import io.reliabilityai.gateway.dataplane.governance.domain.PolicySnapshot;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Memoises the hierarchy fold for one snapshot: scope chain in, merged effective policy out.
 *
 * <p><b>Bound to a single snapshot.</b> A cache instance belongs to exactly one {@link
 * PolicySnapshot} and is replaced wholesale when the snapshot is, which makes invalidation
 * structurally impossible to get wrong. There is no eviction-on-reload to forget, no version check
 * on the read path, and no window in which a fresh snapshot could be served through a stale merge —
 * a whole category of "the policy change didn't take effect on one node" incidents that simply
 * cannot happen here.
 *
 * <p><b>No locking on the read path.</b> Lookup is a plain {@code get}. On a miss the fold is
 * computed <em>outside</em> the map and then offered with {@code putIfAbsent}, rather than through
 * {@code computeIfAbsent}, which would hold a bin lock across the computation — a {@code
 * synchronized} block on the request path is exactly what pins a virtual thread (Doc 11 R-049, Doc
 * 21 §29). Two threads racing on the same cold chain may both compute the fold; that is harmless,
 * because the fold is a pure function and both compute the same answer.
 *
 * <p><b>Bounded, and honest about it.</b> Chains can include per-user and per-request nodes, so the
 * key space is unbounded in principle and an unbounded cache would be a memory leak with a
 * governance label on it. Past the cap the cache stops admitting new entries and those chains
 * simply fold on every request — slower for the tail, but the node stays up. Deliberately not an
 * LRU: tracking recency needs either a lock or a concurrent queue on the hot path, which costs more
 * than the miss it would save for the overwhelmingly common case of a small, stable set of chains.
 */
public final class PolicyCache {

  private final PolicySnapshot snapshot;
  private final ConcurrentHashMap<ScopeChain, EffectivePolicy> merged;
  private final int capacity;

  /**
   * Creates a cache in front of one snapshot.
   *
   * @param snapshot the generation this cache memoises folds of
   * @param capacity the maximum number of distinct chains held
   */
  public PolicyCache(final PolicySnapshot snapshot, final int capacity) {
    this.snapshot = Preconditions.requireNonNull(snapshot, "snapshot");
    if (capacity <= 0) {
      throw new IllegalArgumentException("capacity must be positive");
    }
    this.capacity = capacity;
    this.merged = new ConcurrentHashMap<>(Math.min(capacity, 1024));
  }

  /**
   * The snapshot this cache belongs to.
   *
   * @return the snapshot
   */
  public PolicySnapshot snapshot() {
    return snapshot;
  }

  /**
   * Resolves the effective policy for a chain, folding it if this is the first time.
   *
   * @param chain the request's hierarchy address
   * @param onLookup notified with whether the lookup hit, for metrics
   * @return the merged policy governing that chain
   */
  public EffectivePolicy effectiveFor(final ScopeChain chain, final HitListener onLookup) {
    Preconditions.requireNonNull(chain, "chain");
    final EffectivePolicy cached = merged.get(chain);
    if (cached != null) {
      onLookup.observed(true);
      return cached;
    }
    onLookup.observed(false);
    final EffectivePolicy computed = snapshot.effectiveFor(chain);
    if (merged.size() < capacity) {
      final EffectivePolicy raced = merged.putIfAbsent(chain, computed);
      return raced == null ? computed : raced;
    }
    return computed;
  }

  /**
   * How many chains are currently memoised.
   *
   * @return the entry count
   */
  public int size() {
    return merged.size();
  }

  /** Notified of each lookup's outcome so the cache itself stays free of a metrics dependency. */
  @FunctionalInterface
  public interface HitListener {

    /** A listener that ignores every lookup. */
    HitListener IGNORE = hit -> {};

    /**
     * Observes one lookup.
     *
     * @param hit whether the fold was already cached
     */
    void observed(boolean hit);
  }
}

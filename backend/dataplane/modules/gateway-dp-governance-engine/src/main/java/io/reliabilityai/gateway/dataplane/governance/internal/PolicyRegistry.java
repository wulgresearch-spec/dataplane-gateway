package io.reliabilityai.gateway.dataplane.governance.internal;

import io.reliabilityai.gateway.common.Preconditions;
import io.reliabilityai.gateway.dataplane.governance.api.PolicyMetrics;
import io.reliabilityai.gateway.dataplane.governance.api.PolicyVersion;
import io.reliabilityai.gateway.dataplane.governance.api.ScopeChain;
import io.reliabilityai.gateway.dataplane.governance.domain.EffectivePolicy;
import io.reliabilityai.gateway.dataplane.governance.domain.PolicySnapshot;
import java.util.List;
import java.util.Optional;

/**
 * The single point through which policy is read and replaced.
 *
 * <p>Everything the request path needs is one call: {@link #effectiveFor(ScopeChain)} returns the
 * merged policy for a request's hierarchy address. Behind it, a volatile read of the current
 * generation and a lookup in that generation's fold cache — no locks, no allocation on the hit
 * path, no awareness anywhere that a reload might be happening concurrently.
 *
 * <p>Keeping the read path and the lifecycle operations on the same object is deliberate: it is the
 * reason a reload cannot forget to invalidate a cache, because the cache is not a separate thing to
 * invalidate. Installing a generation replaces the {snapshot, cache} pair wholesale.
 */
public final class PolicyRegistry {

  private final PolicyStore store;
  private final PolicyMetrics metrics;
  private final PolicyCache.HitListener cacheListener;

  /**
   * Creates the registry.
   *
   * @param store the generation holder
   * @param metrics the counters to publish to
   */
  public PolicyRegistry(final PolicyStore store, final PolicyMetrics metrics) {
    this.store = Preconditions.requireNonNull(store, "store");
    this.metrics = Preconditions.requireNonNull(metrics, "metrics");
    this.cacheListener = metrics::cacheLookup;
  }

  /**
   * Resolves the merged policy governing a scope chain.
   *
   * @param chain the request's hierarchy address
   * @return the effective policy
   */
  public EffectivePolicy effectiveFor(final ScopeChain chain) {
    return store.current().cache().effectiveFor(chain, cacheListener);
  }

  /**
   * Whether any generation has been installed on this node.
   *
   * @return {@code true} once policy has arrived
   */
  public boolean isInstalled() {
    return store.isInstalled();
  }

  /**
   * The generation currently in force.
   *
   * @return the current snapshot
   */
  public PolicySnapshot current() {
    return store.current().snapshot();
  }

  /**
   * The version currently in force.
   *
   * @return the current policy version
   */
  public PolicyVersion currentVersion() {
    return current().version();
  }

  /**
   * Puts a newer generation in force without restarting anything.
   *
   * @param snapshot the compiled generation
   * @return {@code true} when installed, {@code false} when refused as not newer
   */
  public boolean install(final PolicySnapshot snapshot) {
    final boolean installed = store.install(snapshot);
    if (installed) {
      metrics.snapshotInstalled(false);
    } else {
      metrics.snapshotRejected();
    }
    return installed;
  }

  /**
   * Restores a previously-served generation.
   *
   * @param version the generation to restore
   * @return the restored snapshot, or empty when it is not in history
   */
  public Optional<PolicySnapshot> rollbackTo(final PolicyVersion version) {
    final Optional<PolicySnapshot> restored = store.rollbackTo(version);
    if (restored.isPresent()) {
      metrics.snapshotInstalled(true);
    } else {
      metrics.snapshotRejected();
    }
    return restored;
  }

  /**
   * The generations still available to roll back to, most recent first.
   *
   * @return the retained versions
   */
  public List<PolicyVersion> rollbackTargets() {
    return store.rollbackTargets();
  }

  /**
   * Finds a generation this node has served, for simulating against the past.
   *
   * @param version the generation wanted
   * @return the snapshot, or empty when it is neither current nor retained
   */
  public Optional<PolicySnapshot> snapshotOf(final PolicyVersion version) {
    return store.snapshotOf(version);
  }
}

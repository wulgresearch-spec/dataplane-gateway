package io.reliabilityai.gateway.dataplane.plugin.domain;

import io.reliabilityai.gateway.common.Preconditions;
import io.reliabilityai.gateway.dataplane.plugin.api.PluginDependency;
import io.reliabilityai.gateway.dataplane.plugin.api.PluginId;
import io.reliabilityai.gateway.dataplane.plugin.api.PluginManifest;
import io.reliabilityai.gateway.dataplane.plugin.api.PluginVersion;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * Resolves plugin start and stop order from declared dependencies (Doc 28 §14).
 *
 * <p>Pure and immutable: built from a set of manifests, answers questions, holds no runtime state.
 *
 * <p>The topological sort is <b>deterministic</b>. Kahn's algorithm normally makes an arbitrary
 * choice whenever several nodes are simultaneously ready, and an arbitrary choice here would mean
 * startup order varying between two nodes running identical config — which Doc 28 POC-1 forbids and
 * which is miserable to debug. Ready nodes are therefore taken in plugin-id order, so the same
 * manifest set always produces the same sequence.
 */
public final class DependencyGraph {

  private final Map<PluginId, PluginManifest> manifests;

  /**
   * Builds a graph over the given manifests.
   *
   * @param manifests the manifests to resolve over
   */
  public DependencyGraph(final Collection<PluginManifest> manifests) {
    Preconditions.requireNonNull(manifests, "manifests");
    // Sorted by id so every derived answer is order-independent of the input collection.
    final Map<PluginId, PluginManifest> byId = new TreeMap<>();
    for (final PluginManifest manifest : manifests) {
      byId.put(manifest.id(), manifest);
    }
    this.manifests = Map.copyOf(byId);
  }

  /**
   * The dependencies a manifest declares that are absent from this graph, or present at an
   * incompatible version.
   *
   * @param manifest the manifest to check
   * @return the unsatisfied dependencies, in declaration order; empty when all are satisfied
   */
  public List<PluginDependency> unsatisfied(final PluginManifest manifest) {
    Preconditions.requireNonNull(manifest, "manifest");
    final List<PluginDependency> missing = new ArrayList<>();
    for (final PluginDependency dependency : manifest.dependencies()) {
      final PluginManifest resolved = manifests.get(dependency.pluginId());
      if (resolved == null) {
        missing.add(dependency);
        continue;
      }
      final PluginVersion available = resolved.version();
      if (!dependency.satisfiedBy(available)) {
        missing.add(dependency);
      }
    }
    return List.copyOf(missing);
  }

  /**
   * Whether adding this manifest would create a cycle.
   *
   * <p>Checked before binding rather than at startup, because a cycle discovered at startup means
   * the node cannot come up at all — far better to refuse the one plugin that closed the loop.
   *
   * @param candidate the manifest about to be bound
   * @return true if binding it would make the graph cyclic
   */
  public boolean introducesCycle(final PluginManifest candidate) {
    Preconditions.requireNonNull(candidate, "candidate");
    final Map<PluginId, PluginManifest> extended = new TreeMap<>(manifests);
    extended.put(candidate.id(), candidate);
    return reachable(extended, candidate.id()).contains(candidate.id());
  }

  /**
   * Whether the graph as it stands is acyclic.
   *
   * @return true if every plugin can be ordered
   */
  public boolean acyclic() {
    return startOrder().size() == manifests.size();
  }

  /**
   * The deterministic startup order: every plugin appears after each of its dependencies.
   *
   * <p>If the graph is cyclic, the plugins caught in the cycle are simply absent from the result —
   * the caller compares sizes to detect it. Returning a partial order rather than throwing lets the
   * caller start everything that <em>can</em> start.
   *
   * @return the plugins in start order
   */
  public List<PluginId> startOrder() {
    final Map<PluginId, Integer> pending = new TreeMap<>();
    final Map<PluginId, Set<PluginId>> dependents = new LinkedHashMap<>();

    for (final PluginManifest manifest : manifests.values()) {
      int edges = 0;
      for (final PluginDependency dependency : manifest.dependencies()) {
        if (manifests.containsKey(dependency.pluginId())) {
          edges++;
          dependents
              .computeIfAbsent(dependency.pluginId(), key -> new LinkedHashSet<>())
              .add(manifest.id());
        }
      }
      pending.put(manifest.id(), edges);
    }

    // Ready nodes are drained in id order, which is what makes the sort deterministic.
    final Deque<PluginId> ready = new ArrayDeque<>();
    for (final Map.Entry<PluginId, Integer> entry : pending.entrySet()) {
      if (entry.getValue() == 0) {
        ready.addLast(entry.getKey());
      }
    }

    final List<PluginId> ordered = new ArrayList<>();
    while (!ready.isEmpty()) {
      final PluginId next = ready.pollFirst();
      ordered.add(next);
      final Set<PluginId> waiting = dependents.getOrDefault(next, Set.of());
      final List<PluginId> unlocked = new ArrayList<>();
      for (final PluginId dependent : waiting) {
        final int remaining = pending.merge(dependent, -1, Integer::sum);
        if (remaining == 0) {
          unlocked.add(dependent);
        }
      }
      unlocked.sort(PluginId::compareTo);
      for (final PluginId dependent : unlocked) {
        ready.addLast(dependent);
      }
    }
    return List.copyOf(ordered);
  }

  /**
   * The deterministic shutdown order — the exact reverse of {@link #startOrder()}.
   *
   * <p>Reverse, not arbitrary: stopping a plugin before something that depends on it would tear a
   * dependency out from under a plugin still running.
   *
   * @return the plugins in stop order
   */
  public List<PluginId> stopOrder() {
    final List<PluginId> reversed = new ArrayList<>(startOrder());
    java.util.Collections.reverse(reversed);
    return List.copyOf(reversed);
  }

  /**
   * The plugins this graph knows about.
   *
   * @return the ids, in id order
   */
  public Set<PluginId> plugins() {
    return manifests.keySet();
  }

  private static Set<PluginId> reachable(
      final Map<PluginId, PluginManifest> graph, final PluginId from) {
    final Set<PluginId> seen = new LinkedHashSet<>();
    final Deque<PluginId> frontier = new ArrayDeque<>();
    final PluginManifest origin = graph.get(from);
    if (origin != null) {
      for (final PluginDependency dependency : origin.dependencies()) {
        frontier.addLast(dependency.pluginId());
      }
    }
    while (!frontier.isEmpty()) {
      final PluginId next = frontier.pollFirst();
      if (!seen.add(next)) {
        continue;
      }
      final PluginManifest manifest = graph.get(next);
      if (manifest == null) {
        continue;
      }
      for (final PluginDependency dependency : manifest.dependencies()) {
        frontier.addLast(dependency.pluginId());
      }
    }
    return seen;
  }
}

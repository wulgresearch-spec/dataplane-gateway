package io.reliabilityai.gateway.dataplane.memory.domain;

import io.reliabilityai.gateway.common.Preconditions;
import io.reliabilityai.gateway.dataplane.memory.api.MemoryScope;
import io.reliabilityai.gateway.dataplane.memory.api.MemoryType;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Memory policy, compiled once and immutable thereafter (MEM-18).
 *
 * <p>The hot path does not merge, parse or allocate policy. It performs one lookup against a
 * pre-resolved map and gets an {@link EffectiveMemoryPolicy} that was computed at install time.
 * That is the difference between a policy engine that costs microseconds per request and one that
 * costs milliseconds, and the reason this mirrors the governance engine's {@code PolicySnapshot}
 * rather than inventing a second approach an operator would have to learn.
 *
 * <p><b>Resolution is by longest matching prefix of the scope chain.</b> A record in a session
 * under a user under a workspace resolves against the most specific entry that exists, having
 * merged everything broader into it at compile time — so a lookup is a map read, not a walk.
 *
 * @param version a monotonically increasing version, so an operator can tell which snapshot is live
 * @param byScope the pre-merged policy for every scope key the source declared
 * @param byType per-type overrides, applied on top of the scope policy at compile time
 * @param fallback the policy used when no scope entry matches
 */
public record MemoryPolicySnapshot(
    long version,
    Map<String, EffectiveMemoryPolicy> byScope,
    Map<MemoryType, EffectiveMemoryPolicy> byType,
    EffectiveMemoryPolicy fallback) {

  /**
   * The snapshot in force before anything is installed.
   *
   * <p>Its fallback is {@link EffectiveMemoryPolicy#UNENFORCEABLE}, so a runtime whose policy
   * source has never loaded refuses every write rather than accepting every write. A node that
   * comes up before its configuration is a node that must not serve.
   */
  public static final MemoryPolicySnapshot EMPTY =
      new MemoryPolicySnapshot(0L, Map.of(), Map.of(), EffectiveMemoryPolicy.UNENFORCEABLE);

  /**
   * Validates and freezes the snapshot.
   *
   * @param version the snapshot version
   * @param byScope the pre-merged per-scope policies
   * @param byType the per-type overrides
   * @param fallback the no-match policy
   */
  public MemoryPolicySnapshot {
    Preconditions.requireNonNull(fallback, "fallback");
    Preconditions.requireNonNegative(version, "version");
    byScope = Map.copyOf(Preconditions.requireNonNull(byScope, "byScope"));
    byType = Map.copyOf(Preconditions.requireNonNull(byType, "byType"));
  }

  /**
   * Resolves the policy for a scope and memory type.
   *
   * <p>Walks the scope chain from narrowest to broadest and returns the first entry found, because
   * the chain was merged at compile time and a narrower entry already includes everything above it.
   * Falls back to the per-type override, then to the snapshot's fallback.
   *
   * <p>Allocation-free on the hit path: no list is built, no policy is merged, nothing is parsed.
   *
   * @param scope the scope being written to or read from
   * @param type the memory kind
   * @return the resolved policy, never null and never silently permissive
   */
  public EffectiveMemoryPolicy resolve(final MemoryScope scope, final MemoryType type) {
    Preconditions.requireNonNull(scope, "scope");
    Preconditions.requireNonNull(type, "type");

    final java.util.List<MemoryScope> chain = scope.chain();
    for (int i = chain.size() - 1; i >= 0; i--) {
      final EffectiveMemoryPolicy found = byScope.get(chain.get(i).key());
      if (found != null) {
        return found;
      }
    }
    final EffectiveMemoryPolicy typed = byType.get(type);
    return typed != null ? typed : fallback;
  }

  /**
   * Reports whether any policy has actually been installed.
   *
   * @return false while the runtime is still running on {@link #EMPTY}
   */
  public boolean installed() {
    return version > 0L;
  }

  /**
   * Returns how many scopes this snapshot resolves.
   *
   * @return the entry count, used as an operator-facing gauge
   */
  public int scopeCount() {
    return byScope.size();
  }

  /**
   * Builds a snapshot by merging declared policies down each scope chain.
   *
   * <p>This is where the merging happens — once, at install, off the hot path. A declared policy at
   * a broad scope is merged into every narrower scope beneath it, so the resulting map holds fully
   * resolved answers and {@link #resolve} never has to combine anything.
   *
   * @param version the version to stamp
   * @param declared policy as authored, keyed by the scope it was authored at
   * @param byType per-type overrides
   * @param fallback the policy for scopes nothing was authored at
   * @return the compiled snapshot
   */
  public static MemoryPolicySnapshot compile(
      final long version,
      final Map<MemoryScope, EffectiveMemoryPolicy> declared,
      final Map<MemoryType, EffectiveMemoryPolicy> byType,
      final EffectiveMemoryPolicy fallback) {

    Preconditions.requireNonNull(declared, "declared");
    Preconditions.requireNonNull(byType, "byType");
    Preconditions.requireNonNull(fallback, "fallback");

    // Key the authored policies by scope key first, so the merge below is a map lookup rather than
    // a
    // scan over every declaration for every scope.
    final Map<String, EffectiveMemoryPolicy> authored = new HashMap<>();
    for (final Map.Entry<MemoryScope, EffectiveMemoryPolicy> entry : declared.entrySet()) {
      authored.put(entry.getKey().key(), entry.getValue());
    }

    final Map<String, EffectiveMemoryPolicy> resolved = new LinkedHashMap<>();
    for (final MemoryScope scope : declared.keySet()) {
      EffectiveMemoryPolicy merged = null;
      // Broadest to narrowest, so each step can only tighten what came before it (MEM-19).
      for (final MemoryScope step : scope.chain()) {
        final EffectiveMemoryPolicy at = authored.get(step.key());
        if (at == null) {
          continue;
        }
        merged = merged == null ? at : merged.mergeWith(at);
      }
      if (merged != null) {
        resolved.put(scope.key(), merged);
      }
    }
    return new MemoryPolicySnapshot(version, resolved, byType, fallback);
  }
}

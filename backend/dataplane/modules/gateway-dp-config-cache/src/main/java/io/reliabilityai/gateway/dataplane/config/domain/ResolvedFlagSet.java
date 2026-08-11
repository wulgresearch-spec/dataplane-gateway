package io.reliabilityai.gateway.dataplane.config.domain;

import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * The immutable set of feature flags resolved once at pin time (Doc 36 FFC-2/FFC-4/FFC-5).
 * Resolution is deterministic and recorded: the {@link #id()} is a stable digest of the resolved
 * values so replay reproduces from the recorded resolution, never from live re-evaluation (Doc 36
 * FFC-5, Doc 32 §CRS). Immutable for the request; a new snapshot never mutates an in-flight
 * resolved set.
 *
 * @param resolved flag id → resolved boolean (defensively copied, order-independent)
 */
public record ResolvedFlagSet(Map<String, Boolean> resolved) {

  /** Compact constructor defensively copying the resolved map. */
  public ResolvedFlagSet {
    resolved = resolved == null ? Map.of() : Map.copyOf(resolved);
  }

  /** An empty resolved set. */
  public static ResolvedFlagSet empty() {
    return new ResolvedFlagSet(Map.of());
  }

  /**
   * Returns the resolved value of a flag, deny-by-default for an unknown flag (Doc 36 §SDS: no
   * permissive fallback — an undefined flag is treated as {@code false}).
   *
   * @param flagId the flag id
   * @return the resolved boolean, or {@code false} when the flag is not defined
   */
  public boolean value(final String flagId) {
    return resolved.getOrDefault(flagId, Boolean.FALSE);
  }

  /**
   * Returns the stable, recorded identity of this resolved set (Doc 36 FFC-5), computed as a
   * deterministic digest over the flags in a canonical (sorted) order.
   *
   * @return a stable 16-hex-character identity for replay correlation
   */
  public String id() {
    final StringBuilder canonical = new StringBuilder();
    // TreeMap gives order-stable iteration regardless of insertion order.
    for (final Map.Entry<String, Boolean> e : new TreeMap<>(resolved).entrySet()) {
      canonical.append(e.getKey()).append('=').append(e.getValue()).append(';');
    }
    return DeterministicDigest.hexId(canonical.toString());
  }

  /**
   * Returns the flag ids present in this resolved set, sorted for stable iteration.
   *
   * @return the sorted flag ids
   */
  public List<String> flagIds() {
    return List.copyOf(new TreeMap<>(resolved).keySet());
  }
}

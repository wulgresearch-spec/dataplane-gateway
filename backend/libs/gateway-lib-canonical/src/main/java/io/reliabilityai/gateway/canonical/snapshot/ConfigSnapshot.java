package io.reliabilityai.gateway.canonical.snapshot;

import io.reliabilityai.gateway.canonical.identity.Region;
import io.reliabilityai.gateway.canonical.identity.SnapshotVersion;
import io.reliabilityai.gateway.common.Preconditions;
import java.util.List;
import java.util.Map;

/**
 * A cached, versioned, C15-authored configuration snapshot (Doc 36 §5, AD-013/AD-022). The IR-6
 * snapshot contract for configuration (Doc 38 §IR-6): frozen-in-code before any consumer builds
 * against it. The runtime consumes it read-only and never authors, validates content, or mutates it
 * (Doc 36 CFG-INV, §CAB); it carries <b>no secret</b> (Doc 36 CFG-A7) and no request content.
 *
 * <p>Fields (Doc 36 §5):
 *
 * <ul>
 *   <li>{@code version} — pinned version identity, recorded for replay (Doc 36 §15, Doc 29 §CVR).
 *   <li>{@code schemaVersion} — the schema-compatibility anchor validated at pin against the code's
 *       supported range (Doc 36 §SCA, Doc 29 §SCC); an out-of-range pin fails closed.
 *   <li>{@code region} — residency region; snapshots are region-scoped and read region-local (Doc
 *       36 §14, AD-014).
 *   <li>{@code entries} — C15-validated config entries, including operational-baseline numerics
 *       (Doc 36 §OBB, Doc 16 §I.1) — the runtime hard-codes none.
 *   <li>{@code flags} — feature-flag definitions resolved once at pin (Doc 36 §FFC).
 *   <li>{@code secureDefaults} — C15-authored deny-by-default fallbacks applied, never synthesized
 *       (Doc 36 §SDS).
 * </ul>
 *
 * Immutable; all collections defensively copied (Doc 11 R-005).
 *
 * @param version the pinned snapshot version
 * @param schemaVersion the C15 schema version (Doc 29 §SCC compatibility anchor)
 * @param region the residency region
 * @param entries C15-validated config entries (key → value; includes operational baselines)
 * @param flags feature-flag definitions (Doc 36 §FFC)
 * @param secureDefaults C15-authored secure defaults (Doc 36 §SDS)
 */
public record ConfigSnapshot(
    SnapshotVersion version,
    int schemaVersion,
    Region region,
    Map<String, String> entries,
    List<FeatureFlagDefinition> flags,
    Map<String, String> secureDefaults) {

  /** Compact constructor validating required identity and defensively copying content. */
  public ConfigSnapshot {
    Preconditions.requireNonNull(version, "version");
    Preconditions.requireNonNull(region, "region");
    if (schemaVersion < 1) {
      throw new IllegalArgumentException("schemaVersion must be >= 1: " + schemaVersion);
    }
    // Inlined copies, not Preconditions.immutableList/Map — see their javadoc (EI_EXPOSE_REP).
    entries = entries == null ? Map.of() : Map.copyOf(entries);
    flags = flags == null ? List.of() : List.copyOf(flags);
    secureDefaults = secureDefaults == null ? Map.of() : Map.copyOf(secureDefaults);
  }
}

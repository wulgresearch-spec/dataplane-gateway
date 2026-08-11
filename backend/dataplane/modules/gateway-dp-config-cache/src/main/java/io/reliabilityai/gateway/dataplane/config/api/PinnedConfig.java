package io.reliabilityai.gateway.dataplane.config.api;

import io.reliabilityai.gateway.canonical.identity.Region;
import io.reliabilityai.gateway.canonical.identity.SnapshotVersion;
import io.reliabilityai.gateway.common.Preconditions;
import io.reliabilityai.gateway.dataplane.config.domain.ResolvedFlagSet;
import java.util.Map;
import java.util.Optional;

/**
 * The immutable configuration pinned for a single request (Doc 36 §7). Pinned once, after C6 (Doc
 * 32 §SPT), and read identically by every downstream stage — there is no mid-request re-read (Doc
 * 36 CFG-D3). Carries the pinned version, resolved flags, and the C15-authored secure defaults so a
 * missing required entry fails closed to a snapshot default, never a locally-synthesized one (Doc
 * 36 §SDS). Immutable; collections defensively copied.
 *
 * @param configVersion the pinned config version, recorded for replay (Doc 36 §15)
 * @param schemaVersion the validated schema version (Doc 29 §SCC)
 * @param region the residency region (Doc 36 §14)
 * @param entries the C15-validated config entries (Doc 36 §5)
 * @param resolvedFlags the once-resolved, recorded flag set (Doc 36 FFC-5)
 * @param secureDefaults the C15-authored secure defaults (Doc 36 §SDS)
 */
public record PinnedConfig(
    SnapshotVersion configVersion,
    int schemaVersion,
    Region region,
    Map<String, String> entries,
    ResolvedFlagSet resolvedFlags,
    Map<String, String> secureDefaults) {

  /** Compact constructor validating identity and defensively copying content. */
  public PinnedConfig {
    Preconditions.requireNonNull(configVersion, "configVersion");
    Preconditions.requireNonNull(region, "region");
    Preconditions.requireNonNull(resolvedFlags, "resolvedFlags");
    entries = entries == null ? Map.of() : Map.copyOf(entries);
    secureDefaults = secureDefaults == null ? Map.of() : Map.copyOf(secureDefaults);
  }

  /**
   * Returns a config entry if present (Doc 36 §5); does not fall back to a default.
   *
   * @param key the config key
   * @return the value, or empty when absent
   */
  public Optional<String> entry(final String key) {
    return Optional.ofNullable(entries.get(Preconditions.requireNonBlank(key, "key")));
  }

  /**
   * Returns a required config entry, falling back to the C15-authored secure default and failing
   * closed if neither is present (Doc 36 §SDS SDS-3) — the runtime never synthesizes a default.
   *
   * @param key the required config key
   * @return the entry value, or the snapshot secure default
   * @throws IllegalStateException fail-closed when the required entry has no value and no default
   */
  public String requiredEntry(final String key) {
    Preconditions.requireNonBlank(key, "key");
    final String value = entries.get(key);
    if (value != null) {
      return value;
    }
    final String secureDefault = secureDefaults.get(key);
    if (secureDefault != null) {
      return secureDefault;
    }
    throw new IllegalStateException("required config missing and no secure default: " + key);
  }

  /**
   * Returns a required operational-baseline numeric from the pinned snapshot (Doc 36 §OBB) — the
   * runtime hard-codes no operational numeric; every threshold is a governed config entry.
   *
   * @param key the baseline key
   * @return the parsed long value
   * @throws IllegalStateException fail-closed when the value is missing or not a valid long
   */
  public long requiredLong(final String key) {
    final String raw = requiredEntry(key);
    try {
      return Long.parseLong(raw.trim());
    } catch (final NumberFormatException e) {
      throw new IllegalStateException("operational baseline is not a valid long: " + key, e);
    }
  }

  /**
   * Returns the resolved value of a feature flag, deny-by-default for an unknown flag (Doc 36
   * §FFC).
   *
   * @param flagId the flag id
   * @return the resolved boolean
   */
  public boolean flag(final String flagId) {
    return resolvedFlags.value(Preconditions.requireNonBlank(flagId, "flagId"));
  }
}

package io.reliabilityai.gateway.dataplane.config.application;

import io.reliabilityai.gateway.canonical.identity.CodeVersion;
import io.reliabilityai.gateway.canonical.identity.ExecutionIdentity;
import io.reliabilityai.gateway.canonical.identity.Region;
import io.reliabilityai.gateway.canonical.identity.TenantScope;
import io.reliabilityai.gateway.canonical.snapshot.ConfigSnapshot;
import io.reliabilityai.gateway.common.Preconditions;
import io.reliabilityai.gateway.dataplane.config.api.ConfigAuditPort;
import io.reliabilityai.gateway.dataplane.config.api.ConfigMetricsPort;
import io.reliabilityai.gateway.dataplane.config.api.ConfigPinPort;
import io.reliabilityai.gateway.dataplane.config.api.PinnedConfig;
import io.reliabilityai.gateway.dataplane.config.domain.FeatureFlagResolver;
import io.reliabilityai.gateway.dataplane.config.domain.ResolvedFlagSet;
import io.reliabilityai.gateway.dataplane.config.domain.SchemaCompatibility;
import io.reliabilityai.gateway.ports.SnapshotSourcePort;
import java.util.Optional;

/**
 * The configuration pinning use-case (Doc 36 §7). Reads only the last-known-good cached snapshot
 * (Doc 36 CFG-A2/CFG-D2 — never a synchronous control-plane call), validates schema compatibility
 * (Doc 29 §SCC) and residency (Doc 36 CFG-A14), resolves feature flags once deterministically (Doc
 * 36 §FFC), and records the applied version content-free for replay/audit (Doc 36 §CAU, Doc 29
 * §CVR). Fail-closed on any unavailability, incompatibility, or mismatch (Doc 36 CFG-INV).
 *
 * <p>Stateless and side-effect-free apart from the content-free audit/metric emissions, which never
 * affect the pin outcome (Doc 27 OT-A1).
 */
public final class ConfigPinService implements ConfigPinPort {

  private final SnapshotSourcePort<ConfigSnapshot> source;
  private final FeatureFlagResolver flagResolver;
  private final SchemaCompatibility schemaCompatibility;
  private final ConfigAuditPort audit;
  private final ConfigMetricsPort metrics;

  /**
   * Creates the pinning service against its injected ports (AD-002, Doc 11 R-017).
   *
   * @param source the last-known-good config snapshot source (Doc 36 §6, AD-022)
   * @param flagResolver the deterministic feature-flag resolver (Doc 36 §FFC)
   * @param schemaCompatibility the schema-range validator (Doc 29 §SCC)
   * @param audit the content-free applied-version audit seam (Doc 36 §CAU)
   * @param metrics the content-free metrics seam (Doc 36 §18)
   */
  public ConfigPinService(
      final SnapshotSourcePort<ConfigSnapshot> source,
      final FeatureFlagResolver flagResolver,
      final SchemaCompatibility schemaCompatibility,
      final ConfigAuditPort audit,
      final ConfigMetricsPort metrics) {
    this.source = Preconditions.requireNonNull(source, "source");
    this.flagResolver = Preconditions.requireNonNull(flagResolver, "flagResolver");
    this.schemaCompatibility =
        Preconditions.requireNonNull(schemaCompatibility, "schemaCompatibility");
    this.audit = Preconditions.requireNonNull(audit, "audit");
    this.metrics = Preconditions.requireNonNull(metrics, "metrics");
  }

  @Override
  public PinResult pin(
      final TenantScope tenantScope,
      final Region region,
      final ExecutionIdentity executionIdentity,
      final CodeVersion codeVersion) {
    // Post-C6 pinning (Doc 32 §SPT): tenant scope MUST be present. A null scope is a pin-before-C6
    // programming error and is rejected rather than silently proceeding.
    Preconditions.requireNonNull(tenantScope, "tenantScope");
    Preconditions.requireNonNull(region, "region");
    Preconditions.requireNonNull(executionIdentity, "executionIdentity");
    Preconditions.requireNonNull(codeVersion, "codeVersion");

    final Optional<ConfigSnapshot> maybeSnapshot = source.current();
    if (maybeSnapshot.isEmpty()) {
      safeMetric(metrics::requiredMissing);
      return new PinResult.FailClosed("config-snapshot-unavailable");
    }
    final ConfigSnapshot snapshot = maybeSnapshot.get();

    if (!schemaCompatibility.isCompatible(snapshot.schemaVersion())) {
      return new PinResult.FailClosed("config-schema-incompatible");
    }
    if (!snapshot.region().equals(region)) {
      // Residency confinement (Doc 36 CFG-A14, AD-014): never serve another region's config.
      return new PinResult.FailClosed("config-region-mismatch");
    }

    final ResolvedFlagSet resolvedFlags = flagResolver.resolve(snapshot.flags(), tenantScope);
    final PinnedConfig pinnedConfig =
        new PinnedConfig(
            snapshot.version(),
            snapshot.schemaVersion(),
            region,
            snapshot.entries(),
            resolvedFlags,
            snapshot.secureDefaults());

    // Content-free audit/metric emissions are best-effort — they NEVER affect the pin outcome and
    // never
    // throw out of pin() (Doc 27 OT-A1/OT-INV): a telemetry failure must not fail a valid config
    // pin.
    safeMetric(
        () ->
            audit.recordAppliedVersion(
                executionIdentity, codeVersion, snapshot.version(), resolvedFlags.id()));
    safeMetric(() -> metrics.snapshotPinned(snapshot.version()));
    return new PinResult.Pinned(pinnedConfig);
  }

  private static void safeMetric(final Runnable emit) {
    try {
      emit.run();
    } catch (final RuntimeException ignored) {
      // observability is side-effect-free — never affects the pin decision (Doc 27 OT-A1)
    }
  }
}

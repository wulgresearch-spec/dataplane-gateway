package io.reliabilityai.gateway.dataplane.config.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.reliabilityai.gateway.canonical.identity.AttemptId;
import io.reliabilityai.gateway.canonical.identity.CodeVersion;
import io.reliabilityai.gateway.canonical.identity.CorrelationId;
import io.reliabilityai.gateway.canonical.identity.ExecutionIdentity;
import io.reliabilityai.gateway.canonical.identity.IdempotencyKey;
import io.reliabilityai.gateway.canonical.identity.Region;
import io.reliabilityai.gateway.canonical.identity.SnapshotVersion;
import io.reliabilityai.gateway.canonical.identity.TenantScope;
import io.reliabilityai.gateway.canonical.snapshot.ConfigSnapshot;
import io.reliabilityai.gateway.canonical.snapshot.FeatureFlagDefinition;
import io.reliabilityai.gateway.dataplane.config.api.ConfigAuditPort;
import io.reliabilityai.gateway.dataplane.config.api.ConfigMetricsPort;
import io.reliabilityai.gateway.dataplane.config.api.ConfigPinPort.PinResult;
import io.reliabilityai.gateway.dataplane.config.domain.FeatureFlagResolver;
import io.reliabilityai.gateway.dataplane.config.domain.SchemaCompatibility;
import io.reliabilityai.gateway.ports.SnapshotSourcePort;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/** Behaviour tests for the config pinning use-case (Doc 36 §7, CFG-INV, §FFC, §SDS, §SCA). */
class ConfigPinServiceTest {

  private static final Region US_EAST = new Region("us-east-1");
  private static final TenantScope TENANT = TenantScope.of("org-1", "tenant-1");
  private static final CodeVersion CODE = new CodeVersion("1.0.0");
  private static final ExecutionIdentity EXEC =
      new ExecutionIdentity(
          new IdempotencyKey("idem-1"), new AttemptId("attempt-1"), new CorrelationId("corr-1"));

  private final FakeSource source = new FakeSource();
  private final RecordingAudit audit = new RecordingAudit();
  private final CountingMetrics metrics = new CountingMetrics();
  private final ConfigPinService service =
      new ConfigPinService(
          source, new FeatureFlagResolver(), new SchemaCompatibility(1, 1), audit, metrics);

  private static ConfigSnapshot snapshot(final Region region, final int schemaVersion) {
    return new ConfigSnapshot(
        new SnapshotVersion("config", "v7"),
        schemaVersion,
        region,
        Map.of("finalization.timeout.ms", "2000"),
        List.of(new FeatureFlagDefinition("f-on", 10_000)),
        Map.of("deny.default", "true"));
  }

  @Test
  void failsClosedWhenNoSnapshotAvailable() {
    source.snapshot = null;
    final PinResult result = service.pin(TENANT, US_EAST, EXEC, CODE);
    assertThat(result).isInstanceOf(PinResult.FailClosed.class);
    assertThat(((PinResult.FailClosed) result).reason()).isEqualTo("config-snapshot-unavailable");
    assertThat(metrics.requiredMissing).isEqualTo(1);
    assertThat(audit.calls).isZero();
  }

  @Test
  void failsClosedOnSchemaIncompatibility() {
    source.snapshot = snapshot(US_EAST, 2); // supported range is [1, 1]
    final PinResult result = service.pin(TENANT, US_EAST, EXEC, CODE);
    assertThat(result).isInstanceOf(PinResult.FailClosed.class);
    assertThat(((PinResult.FailClosed) result).reason()).isEqualTo("config-schema-incompatible");
    assertThat(audit.calls).isZero();
  }

  @Test
  void failsClosedOnRegionMismatch() {
    source.snapshot = snapshot(new Region("eu-west-1"), 1);
    final PinResult result = service.pin(TENANT, US_EAST, EXEC, CODE);
    assertThat(result).isInstanceOf(PinResult.FailClosed.class);
    assertThat(((PinResult.FailClosed) result).reason()).isEqualTo("config-region-mismatch");
  }

  @Test
  void throwingTelemetryNeverFailsAValidPin() {
    // Observability must never affect the pin outcome (Doc 27 OT-A1): a throwing audit/metrics port
    // must not turn a valid config pin into an exception/failure.
    source.snapshot = snapshot(US_EAST, 1);
    final ConfigAuditPort boomAudit =
        (executionIdentity, codeVersion, version, flagsId) -> {
          throw new RuntimeException("boom");
        };
    final ConfigMetricsPort boomMetrics =
        new ConfigMetricsPort() {
          @Override
          public void snapshotPinned(
              final io.reliabilityai.gateway.canonical.identity.SnapshotVersion v) {
            throw new RuntimeException("boom");
          }

          @Override
          public void lastKnownGoodActive() {
            throw new RuntimeException("boom");
          }

          @Override
          public void requiredMissing() {
            throw new RuntimeException("boom");
          }

          @Override
          public void driftActive() {
            throw new RuntimeException("boom");
          }
        };
    final ConfigPinService svc =
        new ConfigPinService(
            source,
            new FeatureFlagResolver(),
            new SchemaCompatibility(1, 1),
            boomAudit,
            boomMetrics);
    assertThat(svc.pin(TENANT, US_EAST, EXEC, CODE)).isInstanceOf(PinResult.Pinned.class);
  }

  @Test
  void pinsSuccessfullyAndRecordsAppliedVersionContentFree() {
    source.snapshot = snapshot(US_EAST, 1);
    final PinResult result = service.pin(TENANT, US_EAST, EXEC, CODE);
    assertThat(result).isInstanceOf(PinResult.Pinned.class);
    final var pinned = ((PinResult.Pinned) result).config();
    assertThat(pinned.configVersion().version()).isEqualTo("v7");
    assertThat(pinned.flag("f-on")).isTrue();
    assertThat(pinned.requiredLong("finalization.timeout.ms")).isEqualTo(2000L);
    assertThat(metrics.snapshotPinned).isEqualTo(1);
    assertThat(audit.calls).isEqualTo(1);
    assertThat(audit.lastVersion.version()).isEqualTo("v7");
    assertThat(audit.lastCodeVersion).isEqualTo(CODE); // CC-1: replay identity records codeVersion
    assertThat(audit.lastFlagSetId).isEqualTo(pinned.resolvedFlags().id());
  }

  @Test
  void rejectsNullTenantScopeBeforeC6() {
    source.snapshot = snapshot(US_EAST, 1);
    assertThatThrownBy(() -> service.pin(null, US_EAST, EXEC, CODE))
        .isInstanceOf(NullPointerException.class)
        .hasMessageContaining("tenantScope");
  }

  @Test
  void pinIsImmutableAndDeterministicAcrossStages() {
    source.snapshot = snapshot(US_EAST, 1);
    final var first = ((PinResult.Pinned) service.pin(TENANT, US_EAST, EXEC, CODE)).config();
    final var second = ((PinResult.Pinned) service.pin(TENANT, US_EAST, EXEC, CODE)).config();
    assertThat(first.resolvedFlags().id()).isEqualTo(second.resolvedFlags().id());
    assertThatThrownBy(() -> first.entries().put("x", "y"))
        .isInstanceOf(UnsupportedOperationException.class);
  }

  @Test
  void requiredEntryFallsBackToSecureDefaultThenFailsClosed() {
    source.snapshot = snapshot(US_EAST, 1);
    final var pinned = ((PinResult.Pinned) service.pin(TENANT, US_EAST, EXEC, CODE)).config();
    assertThat(pinned.requiredEntry("deny.default")).isEqualTo("true"); // from secure defaults
    assertThatThrownBy(() -> pinned.requiredEntry("absent.everywhere"))
        .isInstanceOf(IllegalStateException.class);
  }

  private static final class FakeSource implements SnapshotSourcePort<ConfigSnapshot> {
    private ConfigSnapshot snapshot;

    @Override
    public Optional<ConfigSnapshot> current() {
      return Optional.ofNullable(snapshot);
    }

    @Override
    public Optional<ConfigSnapshot> pinned(final SnapshotVersion version) {
      return current();
    }
  }

  private static final class RecordingAudit implements ConfigAuditPort {
    private int calls;
    private SnapshotVersion lastVersion;
    private CodeVersion lastCodeVersion;
    private String lastFlagSetId;

    @Override
    public void recordAppliedVersion(
        final ExecutionIdentity executionIdentity,
        final CodeVersion codeVersion,
        final SnapshotVersion configVersion,
        final String resolvedFlagSetId) {
      calls++;
      lastVersion = configVersion;
      lastCodeVersion = codeVersion;
      lastFlagSetId = resolvedFlagSetId;
    }
  }

  private static final class CountingMetrics implements ConfigMetricsPort {
    private int snapshotPinned;
    private int lastKnownGood;
    private int requiredMissing;
    private int drift;

    @Override
    public void snapshotPinned(final SnapshotVersion version) {
      snapshotPinned++;
    }

    @Override
    public void lastKnownGoodActive() {
      lastKnownGood++;
    }

    @Override
    public void requiredMissing() {
      requiredMissing++;
    }

    @Override
    public void driftActive() {
      drift++;
    }
  }
}

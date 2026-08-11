package io.reliabilityai.gateway.dataplane.secrets;

import static org.assertj.core.api.Assertions.assertThat;

import io.reliabilityai.gateway.canonical.decision.RouteTarget;
import io.reliabilityai.gateway.canonical.identity.CanonicalModelId;
import io.reliabilityai.gateway.canonical.identity.CorrelationId;
import io.reliabilityai.gateway.canonical.identity.SnapshotVersion;
import io.reliabilityai.gateway.canonical.identity.TenantScope;
import io.reliabilityai.gateway.canonical.snapshot.CredentialSnapshotRef;
import io.reliabilityai.gateway.dataplane.secrets.adapter.LastKnownGoodCredentialSnapshotCache;
import io.reliabilityai.gateway.dataplane.secrets.adapter.LastKnownGoodCredentialSnapshotCache.Key;
import io.reliabilityai.gateway.dataplane.secrets.adapter.SnapshotCredentialMaterialSource;
import io.reliabilityai.gateway.dataplane.secrets.api.SecretsMetricsPort;
import io.reliabilityai.gateway.dataplane.secrets.application.MaterializationService;
import io.reliabilityai.gateway.ports.ClockPort;
import io.reliabilityai.gateway.ports.CredentialRequest;
import io.reliabilityai.gateway.ports.SecretsProviderPort.MaterializationResult;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** End-to-end wiring: LKG cache → materialization service → lease → rotation (Doc 26 full flow). */
class SecretsProviderEndToEndTest {

  private static final TenantScope TENANT = TenantScope.of("org-1", "tenant-1");
  private static final RouteTarget ROUTE = new RouteTarget(new CanonicalModelId("m1"), "route-ref");
  private static final Instant NOW = Instant.parse("2026-07-23T00:00:00Z");
  private static final CredentialRequest REQUEST =
      new CredentialRequest(TENANT, ROUTE, new CorrelationId("corr-1"));

  private final LastKnownGoodCredentialSnapshotCache cache =
      new LastKnownGoodCredentialSnapshotCache();
  private final ClockPort clock = () -> NOW;
  private final MaterializationService service =
      new MaterializationService(
          cache, clock, record -> {}, new NoopMetrics(), Duration.ofSeconds(30), 4096);

  private void publish(final String version, final String secret) {
    final var ref =
        new CredentialSnapshotRef(
            new SnapshotVersion("credential", version), TENANT, NOW.plusSeconds(3600));
    cache.applyPublished(
        new SnapshotVersion("credential", version),
        Map.of(
            Key.of(TENANT, ROUTE),
            new SnapshotCredentialMaterialSource(ref, secret.toCharArray())));
  }

  private static char[] read(final MaterializationResult result) {
    final var lease = ((MaterializationResult.Leased) result).lease();
    final char[][] captured = new char[1][];
    lease.use(m -> captured[0] = m.clone());
    lease.close();
    return captured[0];
  }

  @Test
  void materializesFromCacheThenRotates() {
    publish("v1", "sk-live-1");
    final MaterializationResult first = service.materialize(REQUEST);
    assertThat(first).isInstanceOf(MaterializationResult.Leased.class);
    assertThat(read(first)).containsExactly("sk-live-1".toCharArray());

    // Rotation: publish a new version; old master is wiped, new one materializes.
    publish("v2", "sk-live-2");
    final MaterializationResult second = service.materialize(REQUEST);
    assertThat(read(second)).containsExactly("sk-live-2".toCharArray());
    assertThat(cache.pinnedVersion().orElseThrow().version()).isEqualTo("v2");
  }

  @Test
  void failsClosedAfterInvalidation() {
    publish("v1", "sk-live-1");
    cache.invalidate();
    final MaterializationResult result = service.materialize(REQUEST);
    assertThat(result).isInstanceOf(MaterializationResult.CredentialUnavailable.class);
    assertThat(((MaterializationResult.CredentialUnavailable) result).reason())
        .isEqualTo("snapshot-missing");
  }

  private static final class NoopMetrics implements SecretsMetricsPort {
    @Override
    public void materialized() {}

    @Override
    public void credentialUnavailable(final String reason) {}

    @Override
    public void sanitization(final boolean complete) {}

    @Override
    public void leaseLeakDetected() {}
  }
}

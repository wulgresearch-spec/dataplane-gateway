package io.reliabilityai.gateway.dataplane.config.adapter;

import static org.assertj.core.api.Assertions.assertThat;

import io.reliabilityai.gateway.canonical.identity.Region;
import io.reliabilityai.gateway.canonical.identity.SnapshotVersion;
import io.reliabilityai.gateway.canonical.snapshot.ConfigSnapshot;
import io.reliabilityai.gateway.dataplane.config.api.ConfigMetricsPort;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** Tests for the last-known-good config cache (Doc 36 §6, AD-017/AD-022, §DRC, §RIF). */
class LastKnownGoodConfigCacheTest {

  private final CountingMetrics metrics = new CountingMetrics();
  private final LastKnownGoodConfigCache cache = new LastKnownGoodConfigCache(metrics);

  private static ConfigSnapshot snapshot(final String version) {
    return new ConfigSnapshot(
        new SnapshotVersion("config", version),
        1,
        new Region("us-east-1"),
        Map.of(),
        List.of(),
        Map.of());
  }

  @Test
  void coldStartHasNoSnapshot() {
    assertThat(cache.current()).isEmpty();
  }

  @Test
  void appliesAndServesLastKnownGood() {
    cache.applyPublished(snapshot("v1"));
    assertThat(cache.current()).isPresent();
    assertThat(cache.current().orElseThrow().version().version()).isEqualTo("v1");
  }

  @Test
  void atomicSwapReplacesForNewRequestsOnly() {
    cache.applyPublished(snapshot("v1"));
    final ConfigSnapshot pinnedByInFlight = cache.current().orElseThrow();
    cache.applyPublished(snapshot("v2"));
    // The already-pinned reference the in-flight request holds is unchanged (Doc 36 §RIF).
    assertThat(pinnedByInFlight.version().version()).isEqualTo("v1");
    assertThat(cache.current().orElseThrow().version().version()).isEqualTo("v2");
  }

  @Test
  void rollbackRepinsPriorValidatedVersion() {
    cache.applyPublished(snapshot("v1"));
    cache.applyPublished(snapshot("v2"));
    cache.rollbackTo(snapshot("v1"));
    assertThat(cache.current().orElseThrow().version().version()).isEqualTo("v1");
  }

  @Test
  void pinnedReturnsHeldOnlyForMatchingVersion() {
    cache.applyPublished(snapshot("v1"));
    assertThat(cache.pinned(new SnapshotVersion("config", "v1"))).isPresent();
    assertThat(cache.pinned(new SnapshotVersion("config", "v9"))).isEmpty();
  }

  @Test
  void refreshFailureKeepsServingLastKnownGoodAndSignals() {
    cache.applyPublished(snapshot("v1"));
    cache.refreshFailed();
    assertThat(cache.current()).isPresent(); // no outage (Doc 36 CFG-A6)
    assertThat(metrics.lastKnownGood).isEqualTo(1);
    assertThat(metrics.requiredMissing).isZero();
  }

  @Test
  void refreshFailureOnColdStartSignalsRequiredMissing() {
    cache.refreshFailed();
    assertThat(metrics.requiredMissing).isEqualTo(1);
  }

  @Test
  void driftKeepsLastKnownGoodAndRaisesAlarm() {
    cache.applyPublished(snapshot("v1"));
    cache.markDriftDetected();
    assertThat(cache.current()).isPresent(); // continue on LKG (Doc 36 DRC-2)
    assertThat(metrics.drift).isEqualTo(1);
  }

  private static final class CountingMetrics implements ConfigMetricsPort {
    private int lastKnownGood;
    private int requiredMissing;
    private int drift;

    @Override
    public void snapshotPinned(final SnapshotVersion version) {
      // not exercised here
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

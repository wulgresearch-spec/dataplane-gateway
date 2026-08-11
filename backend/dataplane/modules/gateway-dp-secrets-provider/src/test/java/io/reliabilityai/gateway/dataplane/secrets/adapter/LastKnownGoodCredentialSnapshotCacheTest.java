package io.reliabilityai.gateway.dataplane.secrets.adapter;

import static org.assertj.core.api.Assertions.assertThat;

import io.reliabilityai.gateway.canonical.decision.RouteTarget;
import io.reliabilityai.gateway.canonical.identity.CanonicalModelId;
import io.reliabilityai.gateway.canonical.identity.SnapshotVersion;
import io.reliabilityai.gateway.canonical.identity.TenantScope;
import io.reliabilityai.gateway.canonical.snapshot.CredentialSnapshotRef;
import io.reliabilityai.gateway.dataplane.secrets.adapter.LastKnownGoodCredentialSnapshotCache.Key;
import io.reliabilityai.gateway.dataplane.secrets.api.CredentialMaterialSource;
import java.time.Instant;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * LKG / atomic-swap / rotation / invalidation tests for the credential cache (Doc 26 §12/§13,
 * AD-017/022).
 */
class LastKnownGoodCredentialSnapshotCacheTest {

  private static final TenantScope TENANT = TenantScope.of("org-1", "tenant-1");
  private static final RouteTarget ROUTE = new RouteTarget(new CanonicalModelId("m1"), "route-ref");
  private static final Instant NOT_AFTER = Instant.parse("2999-01-01T00:00:00Z");

  private final LastKnownGoodCredentialSnapshotCache cache =
      new LastKnownGoodCredentialSnapshotCache();

  private static SnapshotCredentialMaterialSource source(
      final String version, final String secret) {
    return sourceExpiring(version, secret, NOT_AFTER);
  }

  private static SnapshotCredentialMaterialSource sourceExpiring(
      final String version, final String secret, final Instant notAfter) {
    final var ref =
        new CredentialSnapshotRef(new SnapshotVersion("credential", version), TENANT, notAfter);
    return new SnapshotCredentialMaterialSource(ref, secret.toCharArray());
  }

  @Test
  void coldStartResolvesEmpty() {
    assertThat(cache.resolve(TENANT, ROUTE)).isEmpty();
    assertThat(cache.pinnedVersion()).isEmpty();
  }

  @Test
  void appliesAndResolvesPinnedSnapshot() {
    cache.applyPublished(
        new SnapshotVersion("credential", "v1"), Map.of(Key.of(TENANT, ROUTE), source("v1", "sk")));
    assertThat(cache.resolve(TENANT, ROUTE)).isPresent();
    assertThat(cache.pinnedVersion().orElseThrow().version()).isEqualTo("v1");
  }

  @Test
  void atomicSwapReplacesWithoutFailingInFlight() {
    // M-1: routine rotation swaps atomically but does NOT wipe the old master, so a source resolved
    // just before the swap (in-flight) remains usable — the new credential is published ahead of
    // the
    // old's expiry. Wiping is reserved for invalidate()/evictExpired().
    final SnapshotCredentialMaterialSource v1 = source("v1", "old-secret");
    cache.applyPublished(
        new SnapshotVersion("credential", "v1"), Map.of(Key.of(TENANT, ROUTE), v1));
    final CredentialMaterialSource inFlight = cache.resolve(TENANT, ROUTE).orElseThrow();

    final SnapshotCredentialMaterialSource v2 = source("v2", "new-secret");
    cache.applyPublished(
        new SnapshotVersion("credential", "v2"), Map.of(Key.of(TENANT, ROUTE), v2));

    assertThat(v1.isEvicted()).isFalse(); // routine rotation does not wipe (M-1)
    final char[] dest = new char[inFlight.length()];
    inFlight.copyInto(dest); // in-flight resolve still usable — no spurious failure
    assertThat(dest).containsExactly("old-secret".toCharArray());

    assertThat(cache.pinnedVersion().orElseThrow().version()).isEqualTo("v2");
    assertThat(cache.resolve(TENANT, ROUTE).orElseThrow()).isSameAs(v2);
  }

  @Test
  void invalidateWipesAndEmpties() {
    final SnapshotCredentialMaterialSource v1 = source("v1", "secret");
    cache.applyPublished(
        new SnapshotVersion("credential", "v1"), Map.of(Key.of(TENANT, ROUTE), v1));
    cache.invalidate();
    assertThat(v1.isEvicted()).isTrue();
    assertThat(cache.resolve(TENANT, ROUTE)).isEmpty();
    assertThat(cache.pinnedVersion()).isEmpty();
  }

  @Test
  void resolveOfUnknownRouteIsEmpty() {
    cache.applyPublished(
        new SnapshotVersion("credential", "v1"), Map.of(Key.of(TENANT, ROUTE), source("v1", "sk")));
    final RouteTarget other = new RouteTarget(new CanonicalModelId("m1"), "other-ref");
    assertThat(cache.resolve(TENANT, other)).isEmpty();
  }

  @Test
  void evictExpiredWipesAndDropsExpiredEntriesOnly() {
    // C-1: expired master material is proactively wiped/evicted so nothing lingers past its TTL.
    final Instant now = Instant.parse("2026-07-23T00:00:00Z");
    final SnapshotCredentialMaterialSource live =
        sourceExpiring("v1", "live", now.plusSeconds(3600));
    final SnapshotCredentialMaterialSource stale =
        sourceExpiring("v1", "stale", now.minusSeconds(1));
    final RouteTarget liveRoute = new RouteTarget(new CanonicalModelId("m1"), "live-ref");
    final RouteTarget staleRoute = new RouteTarget(new CanonicalModelId("m1"), "stale-ref");
    cache.applyPublished(
        new SnapshotVersion("credential", "v1"),
        Map.of(Key.of(TENANT, liveRoute), live, Key.of(TENANT, staleRoute), stale));

    cache.evictExpired(now);

    assertThat(stale.isEvicted()).isTrue();
    assertThat(live.isEvicted()).isFalse();
    assertThat(cache.resolve(TENANT, staleRoute)).isEmpty();
    assertThat(cache.resolve(TENANT, liveRoute)).isPresent();
  }

  @Test
  void evictExpiredIsNoOpWhenNothingExpired() {
    final Instant now = Instant.parse("2026-07-23T00:00:00Z");
    cache.applyPublished(
        new SnapshotVersion("credential", "v1"), Map.of(Key.of(TENANT, ROUTE), source("v1", "sk")));
    cache.evictExpired(now);
    assertThat(cache.resolve(TENANT, ROUTE)).isPresent();
    assertThat(cache.pinnedVersion()).isPresent();
  }

  @Test
  void keyIsDerivedFromTenantAndRoute() {
    final Key k = Key.of(TENANT, ROUTE);
    assertThat(k.tenantScope()).isEqualTo(TENANT);
    assertThat(k.providerRouteRef()).isEqualTo("route-ref");
  }
}

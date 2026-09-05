package io.reliabilityai.gateway.dataplane.app.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import io.reliabilityai.gateway.canonical.context.RequestContext;
import io.reliabilityai.gateway.canonical.identity.CausationId;
import io.reliabilityai.gateway.canonical.identity.CorrelationId;
import io.reliabilityai.gateway.canonical.identity.IdempotencyKey;
import io.reliabilityai.gateway.canonical.identity.PrincipalId;
import io.reliabilityai.gateway.canonical.identity.Region;
import io.reliabilityai.gateway.canonical.identity.SnapshotVersion;
import io.reliabilityai.gateway.canonical.identity.TenantScope;
import io.reliabilityai.gateway.canonical.io.ForwardedTransportIdentity;
import io.reliabilityai.gateway.canonical.snapshot.TenantScopeSnapshot;
import io.reliabilityai.gateway.canonical.snapshot.VerificationKeySnapshot;
import io.reliabilityai.gateway.dataplane.authn.api.IdentityVerifierPort;
import io.reliabilityai.gateway.dataplane.authn.api.VerificationOutcome;
import io.reliabilityai.gateway.dataplane.governance.api.AuditSinkPort;
import io.reliabilityai.gateway.ports.AuthenticationPort;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Republishing a tenant-scope snapshot must change what the running node authorizes.
 *
 * <p>These are revocation-propagation contracts, not coverage. Before the snapshot sources were
 * retained on {@link GatewayRuntime}, every one of the tenant cases below kept resolving the
 * startup mapping for the lifetime of the process, so a membership revocation or a tenant move
 * could not take effect without rebuilding the runtime.
 *
 * <p>Authentication itself is stubbed to a fixed verified principal on purpose: the contract under
 * test is tenant <em>resolution</em> from the published snapshot, and the tenant must stay
 * server-derived from {@code principalId → TenantScopeSnapshot} rather than from anything the token
 * carries.
 */
class SnapshotRepublicationTest {

  private static final String PRINCIPAL = "principal-a";
  private static final Region REGION = new Region("us-east-1");

  @TempDir Path walDir;
  @TempDir Path dlqDir;

  private GatewayRuntime runtime;

  @AfterEach
  void tearDown() {
    if (runtime != null && runtime.state() == GatewayRuntime.State.READY) {
      runtime.stop();
    }
  }

  /** A verifier that always establishes the same principal, so only tenant resolution varies. */
  private static final class FixedPrincipalVerifier implements IdentityVerifierPort {
    @Override
    public VerificationOutcome verify(
        final ForwardedTransportIdentity transportIdentity,
        final VerificationKeySnapshot keySnapshot) {
      return new VerificationOutcome.Verified(new PrincipalId(PRINCIPAL), Map.of(), "bearer");
    }
  }

  private GatewayRuntime start() {
    final ExternalAdapters all = RuntimeFixture.allExternalAdapters();
    runtime =
        new GatewayRuntime(
            RuntimeFixture.config(
                RuntimeFixture.masterKey(),
                walDir,
                dlqDir.resolve("dlq.log"),
                record -> {},
                new RuntimeFixture.TestClock(),
                new ExternalAdapters(
                    all.ingress(),
                    Optional.of(new FixedPrincipalVerifier()),
                    all.governance(),
                    all.providerTransport(),
                    all.providerTranslator(),
                    all.credentialPort(),
                    all.capabilitySnapshot(),
                    all.schemaValidator(),
                    all.providerGeneration()),
                Optional.of(
                    RuntimeFixture.governanceConfig(
                        RuntimeFixture.permittingPolicy(), AuditSinkPort.NO_OP)),
                Optional.of(
                    RuntimeFixture.providerConfig(java.net.URI.create("http://127.0.0.1:1"))),
                Optional.empty(),
                Optional.empty()));
    runtime.start();
    return runtime;
  }

  private static RequestContext requestContext(final Region region) {
    return new RequestContext(
        new CorrelationId("corr-1"),
        new IdempotencyKey("idem-1"),
        new CausationId("cause-1"),
        "traceparent",
        region);
  }

  private AuthenticationPort.AuthenticationResult authenticate() {
    return runtime
        .authentication()
        .orElseThrow()
        .authenticate(
            new ForwardedTransportIdentity("Bearer", "Bearer token", Map.of()),
            requestContext(REGION));
  }

  private static TenantScopeSnapshot tenantSnapshot(
      final String version, final Region region, final Map<String, TenantScope> mapping) {
    return new TenantScopeSnapshot(new SnapshotVersion("tenant-scopes", version), region, mapping);
  }

  private void publishTenants(final TenantScopeSnapshot snapshot) {
    runtime.tenantSnapshotSource().orElseThrow().applyPublished(snapshot.version(), snapshot);
  }

  @Test
  void aPrincipalRemovedByARepublishedSnapshotIsRefusedOnTheNextRequest() {
    start();

    // The startup snapshot maps principal-a → org-a/tenant-a.
    assertThat(authenticate())
        .isInstanceOf(AuthenticationPort.AuthenticationResult.Authenticated.class);

    publishTenants(tenantSnapshot("v2", REGION, Map.of()));

    assertThat(authenticate())
        .isInstanceOfSatisfying(
            AuthenticationPort.AuthenticationResult.Unauthenticated.class,
            denied -> assertThat(denied.reason()).isEqualTo("tenant-unresolved"));
  }

  @Test
  void aPrincipalMovedToAnotherTenantResolvesToTheNewTenant() {
    start();

    final AuthenticationPort.AuthenticationResult before = authenticate();
    assertThat(before)
        .isInstanceOfSatisfying(
            AuthenticationPort.AuthenticationResult.Authenticated.class,
            ok -> assertThat(ok.tenant().tenantScope().tenant()).isEqualTo("tenant-a"));

    publishTenants(
        tenantSnapshot("v2", REGION, Map.of(PRINCIPAL, TenantScope.of("org-b", "tenant-b"))));

    assertThat(authenticate())
        .isInstanceOfSatisfying(
            AuthenticationPort.AuthenticationResult.Authenticated.class,
            ok -> {
              assertThat(ok.tenant().tenantScope().tenant()).isEqualTo("tenant-b");
              assertThat(ok.tenant().tenantScope().org()).isEqualTo("org-b");
            });
  }

  @Test
  void aRepublishedSnapshotThatDropsTheTenantEntirelyFailsClosed() {
    start();
    assertThat(authenticate())
        .isInstanceOf(AuthenticationPort.AuthenticationResult.Authenticated.class);

    // Tenant deleted upstream: the principal survives, the mapping does not.
    publishTenants(
        tenantSnapshot("v2", REGION, Map.of("someone-else", TenantScope.of("org-c", "tenant-c"))));

    assertThat(authenticate())
        .isInstanceOfSatisfying(
            AuthenticationPort.AuthenticationResult.Unauthenticated.class,
            denied -> assertThat(denied.reason()).isEqualTo("tenant-unresolved"));
  }

  @Test
  void aRepublishedSnapshotForAnotherRegionStillFailsClosed() {
    start();

    publishTenants(
        tenantSnapshot(
            "v2", new Region("eu-west-1"), Map.of(PRINCIPAL, TenantScope.of("org-a", "tenant-a"))));

    // Republication must not become a way around the residency gate.
    assertThat(authenticate())
        .isInstanceOfSatisfying(
            AuthenticationPort.AuthenticationResult.Unauthenticated.class,
            denied -> assertThat(denied.reason()).isEqualTo("tenant-snapshot-unavailable"));
  }

  @Test
  void theAccessorExposesTheSameLiveSourceAuthenticationReads() {
    start();

    // Proof the accessor is not handing back a detached copy: a publication through it is what the
    // authentication path observes on the next request.
    assertThat(runtime.tenantSnapshotSource()).isPresent();
    publishTenants(
        tenantSnapshot("v2", REGION, Map.of(PRINCIPAL, TenantScope.of("org-z", "tenant-z"))));

    assertThat(runtime.tenantSnapshotSource().orElseThrow().current().orElseThrow().version())
        .isEqualTo(new SnapshotVersion("tenant-scopes", "v2"));
    assertThat(authenticate())
        .isInstanceOfSatisfying(
            AuthenticationPort.AuthenticationResult.Authenticated.class,
            ok -> assertThat(ok.tenant().tenantScope().tenant()).isEqualTo("tenant-z"));
  }

  @Test
  void theKeySnapshotSourceIsRepublishableThroughTheRuntime() {
    start();

    assertThat(runtime.keySnapshotSource()).isPresent();
    final VerificationKeySnapshot revoking =
        new VerificationKeySnapshot(
            new SnapshotVersion("verification-keys", "v2"), REGION, List.of(), List.of("kid-1"));
    runtime.keySnapshotSource().orElseThrow().applyPublished(revoking.version(), revoking);

    final VerificationKeySnapshot current =
        runtime.keySnapshotSource().orElseThrow().current().orElseThrow();
    assertThat(current.version()).isEqualTo(new SnapshotVersion("verification-keys", "v2"));
    assertThat(current.revokedKeyIds()).containsExactly("kid-1");
  }

  @Test
  void concurrentReadersNeverObserveAMixedGeneration() throws InterruptedException {
    start();

    final TenantScopeSnapshot generationOne =
        tenantSnapshot("g1", REGION, Map.of(PRINCIPAL, TenantScope.of("org-1", "tenant-1")));
    final TenantScopeSnapshot generationTwo =
        tenantSnapshot("g2", new Region("eu-west-1"), Map.of("other", TenantScope.of("o2", "t2")));

    final AtomicBoolean torn = new AtomicBoolean(false);
    final AtomicReference<String> detail = new AtomicReference<>("");
    final AtomicBoolean running = new AtomicBoolean(true);
    final CountDownLatch done = new CountDownLatch(1);

    final Thread reader =
        new Thread(
            () -> {
              while (running.get()) {
                runtime
                    .tenantSnapshotSource()
                    .orElseThrow()
                    .current()
                    .ifPresent(
                        seen -> {
                          // version, region and mapping must always come from one publication.
                          final boolean consistent =
                              "g1".equals(seen.version().version())
                                  ? seen.region().equals(REGION)
                                      && seen.principalToTenant().containsKey(PRINCIPAL)
                                  : !"g2".equals(seen.version().version())
                                      || (seen.region().equals(new Region("eu-west-1"))
                                          && seen.principalToTenant().containsKey("other"));
                          if (!consistent) {
                            torn.set(true);
                            detail.set(seen.version().version() + "/" + seen.region().value());
                          }
                        });
              }
              done.countDown();
            });
    reader.start();

    for (int i = 0; i < 2_000; i++) {
      publishTenants(i % 2 == 0 ? generationOne : generationTwo);
    }
    running.set(false);
    assertThat(done.await(10, TimeUnit.SECONDS)).isTrue();

    assertThat(torn).as("observed a mixed generation: %s", detail.get()).isFalse();
  }
}

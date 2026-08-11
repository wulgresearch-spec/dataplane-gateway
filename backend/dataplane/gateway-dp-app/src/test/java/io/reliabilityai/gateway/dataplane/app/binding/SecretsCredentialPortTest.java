package io.reliabilityai.gateway.dataplane.app.binding;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.reliabilityai.gateway.canonical.decision.RouteTarget;
import io.reliabilityai.gateway.canonical.identity.CanonicalModelId;
import io.reliabilityai.gateway.canonical.identity.CorrelationId;
import io.reliabilityai.gateway.canonical.identity.TenantScope;
import io.reliabilityai.gateway.canonical.secret.CredentialLease;
import io.reliabilityai.gateway.ports.CredentialRequest;
import io.reliabilityai.gateway.ports.SecretsProviderPort;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/**
 * The credential bridge: tenant propagation, lease lifecycle, zeroization, and isolation between
 * concurrent requests.
 */
class SecretsCredentialPortTest {

  private static final RouteTarget ROUTE =
      new RouteTarget(new CanonicalModelId("model.test.v1"), "route-a");
  private static final TenantScope TENANT_A = TenantScope.of("org-a", "tenant-a");
  private static final TenantScope TENANT_B = TenantScope.of("org-b", "tenant-b");

  private final List<CredentialRequest> observed = new CopyOnWriteArrayList<>();
  private final RequestCredentialScope scope = new RequestCredentialScope();

  /** A lease that records closure — closure is what zeroizes the material. */
  private static final class RecordingLease implements CredentialLease {
    private final AtomicInteger closes = new AtomicInteger();
    private final TenantScope tenant;

    RecordingLease(final TenantScope tenant) {
      this.tenant = tenant;
    }

    @Override
    public String leaseId() {
      return "lease-" + tenant.tenant();
    }

    @Override
    public TenantScope tenantScope() {
      return tenant;
    }

    @Override
    public Instant notAfter() {
      return Instant.parse("2030-01-01T00:00:00Z");
    }

    @Override
    public boolean active() {
      return closes.get() == 0;
    }

    @Override
    public void use(final SecretConsumer consumer) {
      consumer.accept(new char[] {'s', 'k'});
    }

    @Override
    public void close() {
      closes.incrementAndGet();
    }
  }

  private SecretsProviderPort secrets(final boolean available) {
    return request -> {
      observed.add(request);
      return available
          ? new SecretsProviderPort.MaterializationResult.Leased(
              new RecordingLease(request.tenantScope()))
          : new SecretsProviderPort.MaterializationResult.CredentialUnavailable("no-credential");
    };
  }

  @Test
  void refusesToAcquireWithoutARequestScope() {
    final SecretsCredentialPort port = new SecretsCredentialPort(secrets(true), scope);

    // No bound tenant means no safe answer. Guessing here would be a cross-tenant credential leak.
    assertThat(port.acquire(ROUTE)).isEmpty();
    assertThat(observed).isEmpty();
  }

  @Test
  void propagatesTheBoundTenantAndCorrelationUnchanged() {
    final SecretsCredentialPort port = new SecretsCredentialPort(secrets(true), scope);

    try (var binding = scope.bind(TENANT_A, new CorrelationId("corr-1"))) {
      final Optional<CredentialLease> lease = port.acquire(ROUTE);
      assertThat(lease).isPresent();
    }

    assertThat(observed).hasSize(1);
    assertThat(observed.get(0).tenantScope()).isEqualTo(TENANT_A);
    assertThat(observed.get(0).correlationId()).isEqualTo(new CorrelationId("corr-1"));
    assertThat(observed.get(0).routeTarget()).isEqualTo(ROUTE);
  }

  @Test
  void returnsEmptyWhenTheCredentialIsUnavailable() {
    final SecretsCredentialPort port = new SecretsCredentialPort(secrets(false), scope);

    try (var binding = scope.bind(TENANT_A, new CorrelationId("corr-1"))) {
      assertThat(port.acquire(ROUTE)).isEmpty();
    }
  }

  @Test
  void mintsAFreshLeasePerAcquisitionRatherThanCachingOne() {
    final SecretsCredentialPort port = new SecretsCredentialPort(secrets(true), scope);

    try (var binding = scope.bind(TENANT_A, new CorrelationId("corr-1"))) {
      final CredentialLease first = port.acquire(ROUTE).orElseThrow();
      final CredentialLease second = port.acquire(ROUTE).orElseThrow();
      assertThat(first).isNotSameAs(second);
    }
    assertThat(observed).hasSize(2); // every acquisition goes back to the secrets module
  }

  @Test
  void closingTheLeaseZeroizesIt() {
    final SecretsCredentialPort port = new SecretsCredentialPort(secrets(true), scope);

    try (var binding = scope.bind(TENANT_A, new CorrelationId("corr-1"))) {
      final CredentialLease lease = port.acquire(ROUTE).orElseThrow();
      assertThat(lease.active()).isTrue();
      lease.close();
      assertThat(lease.active()).isFalse();
    }
  }

  @Test
  void scopeIsReleasedWhenTheBindingCloses() {
    final SecretsCredentialPort port = new SecretsCredentialPort(secrets(true), scope);

    try (var binding = scope.bind(TENANT_A, new CorrelationId("corr-1"))) {
      assertThat(port.acquire(ROUTE)).isPresent();
    }
    // Outside the window there is no tenant, so no credential can be minted.
    assertThat(port.acquire(ROUTE)).isEmpty();
  }

  @Test
  void nestedBindingIsRejectedRatherThanSilentlyStacked() {
    try (var outer = scope.bind(TENANT_A, new CorrelationId("corr-1"))) {
      assertThatThrownBy(() -> scope.bind(TENANT_B, new CorrelationId("corr-2")))
          .isInstanceOf(IllegalStateException.class)
          .hasMessageContaining("already bound");
    }
  }

  @Test
  void concurrentRequestsNeverObserveEachOthersTenant() throws Exception {
    final SecretsCredentialPort port = new SecretsCredentialPort(secrets(true), scope);
    final int threads = 32;
    final CountDownLatch ready = new CountDownLatch(threads);
    final CountDownLatch go = new CountDownLatch(1);
    final CountDownLatch done = new CountDownLatch(threads);
    final List<String> mismatches = new CopyOnWriteArrayList<>();

    for (int i = 0; i < threads; i++) {
      final TenantScope tenant = TenantScope.of("org-" + i, "tenant-" + i);
      // Virtual threads: the scope is a ThreadLocal, so each carrier sees only its own binding.
      Thread.ofVirtual()
          .start(
              () -> {
                try {
                  ready.countDown();
                  go.await();
                  try (var binding = scope.bind(tenant, new CorrelationId("corr"))) {
                    final CredentialLease lease = port.acquire(ROUTE).orElseThrow();
                    if (!lease.tenantScope().equals(tenant)) {
                      mismatches.add(tenant.tenant());
                    }
                    lease.close();
                  }
                } catch (final InterruptedException e) {
                  Thread.currentThread().interrupt();
                } finally {
                  done.countDown();
                }
              });
    }

    assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
    go.countDown();
    assertThat(done.await(10, TimeUnit.SECONDS)).isTrue();
    assertThat(mismatches).isEmpty();
    assertThat(observed).hasSize(threads);
  }

  @Test
  void scopedSecretsProviderBindsTheScopeForTheLeaseLifetime() {
    final ScopedSecretsProvider scoped = new ScopedSecretsProvider(secrets(true), scope);
    final SecretsCredentialPort port = new SecretsCredentialPort(secrets(true), scope);

    final SecretsProviderPort.MaterializationResult result =
        scoped.materialize(new CredentialRequest(TENANT_A, ROUTE, new CorrelationId("corr-1")));
    final CredentialLease pipelineLease =
        ((SecretsProviderPort.MaterializationResult.Leased) result).lease();

    // This is the pipeline's window: SECRETS has run, the provider call happens now.
    assertThat(port.acquire(ROUTE)).isPresent();

    pipelineLease.close();

    // ...and once the pipeline releases its lease, the identity is gone.
    assertThat(port.acquire(ROUTE)).isEmpty();
  }

  @Test
  void scopedSecretsProviderPublishesNothingOnRefusal() {
    final ScopedSecretsProvider scoped = new ScopedSecretsProvider(secrets(false), scope);
    final SecretsCredentialPort port = new SecretsCredentialPort(secrets(true), scope);

    scoped.materialize(new CredentialRequest(TENANT_A, ROUTE, new CorrelationId("corr-1")));

    assertThat(scope.current()).isEmpty();
    assertThat(port.acquire(ROUTE)).isEmpty();
  }
}

package io.reliabilityai.gateway.dataplane.provider.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.reliabilityai.gateway.canonical.decision.RouteTarget;
import io.reliabilityai.gateway.canonical.identity.CanonicalModelId;
import io.reliabilityai.gateway.canonical.identity.SnapshotVersion;
import io.reliabilityai.gateway.canonical.identity.TenantScope;
import io.reliabilityai.gateway.canonical.io.CanonicalError;
import io.reliabilityai.gateway.canonical.io.CanonicalRequest;
import io.reliabilityai.gateway.canonical.io.CanonicalResponse;
import io.reliabilityai.gateway.canonical.io.CanonicalUsage;
import io.reliabilityai.gateway.canonical.io.ErrorCategory;
import io.reliabilityai.gateway.canonical.io.FinishReason;
import io.reliabilityai.gateway.canonical.io.ProviderMeta;
import io.reliabilityai.gateway.canonical.io.UsageClass;
import io.reliabilityai.gateway.canonical.secret.CredentialLease;
import io.reliabilityai.gateway.dataplane.provider.api.AdapterTelemetryPort;
import io.reliabilityai.gateway.dataplane.provider.api.CapabilityMapping;
import io.reliabilityai.gateway.dataplane.provider.api.CapabilitySnapshotPort;
import io.reliabilityai.gateway.dataplane.provider.api.CredentialPort;
import io.reliabilityai.gateway.dataplane.provider.api.PinnedVersion;
import io.reliabilityai.gateway.dataplane.provider.api.ProviderTranslator;
import io.reliabilityai.gateway.dataplane.provider.api.ProviderTransportPort;
import io.reliabilityai.gateway.dataplane.provider.api.TransportException;
import io.reliabilityai.gateway.dataplane.provider.api.TransportRequest;
import io.reliabilityai.gateway.dataplane.provider.api.TransportResponse;
import io.reliabilityai.gateway.dataplane.provider.domain.TransportFailureKind;
import io.reliabilityai.gateway.ports.AttemptBudget;
import io.reliabilityai.gateway.ports.ProviderAdapterPort.ProviderInvocationResult;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.Flow;
import org.junit.jupiter.api.Test;

/** Fail-closed pipeline tests for the provider-neutral Anti-Corruption coordinator (Doc 25 §8). */
class ProviderAdapterServiceTest {

  private static final CanonicalModelId MODEL = new CanonicalModelId("m");
  private static final RouteTarget ROUTE = new RouteTarget(MODEL, "route/x");
  private static final AttemptBudget BUDGET = new AttemptBudget(Duration.ofSeconds(2));
  private static final CanonicalRequest REQUEST =
      new CanonicalRequest(MODEL, List.of(), List.of(), Map.of());
  private static final PinnedVersion VERSION = new PinnedVersion("2026-01-01");
  private static final CapabilityMapping CAPS =
      new CapabilityMapping(
          MODEL, new SnapshotVersion("capability", "v9"), VERSION, Set.of("chat"));

  private final FakeTranslator translator = new FakeTranslator();
  private final FakeTransport transport = new FakeTransport();
  private final FakeCredentialPort credentials = new FakeCredentialPort();
  private final FakeCapabilityPort capabilities = new FakeCapabilityPort();
  private final CountingTelemetry telemetry = new CountingTelemetry();

  private ProviderAdapterService service() {
    return new ProviderAdapterService(translator, transport, credentials, capabilities, telemetry);
  }

  private static TransportRequest transportRequest() {
    return new TransportRequest("https://x", Map.of(), new byte[] {1}, VERSION);
  }

  private static ProviderInvocationResult unary() {
    return new ProviderInvocationResult.Unary(
        new CanonicalResponse(
            "ok",
            List.of(),
            FinishReason.STOP,
            new CanonicalUsage(1, 1, 0, 0, 0, UsageClass.AUTHORITATIVE),
            ProviderMeta.empty()));
  }

  @Test
  void succeedsUnaryStampsTelemetryAndClosesLease() {
    capabilities.mapping = CAPS;
    translator.out = transportRequest();
    transport.response = new TransportResponse.Buffered(200, Map.of(), new byte[] {9});
    translator.in = unary();
    final ProviderInvocationResult result = service().invoke(REQUEST, ROUTE, BUDGET);
    assertThat(result).isInstanceOf(ProviderInvocationResult.Unary.class);
    assertThat(telemetry.invoked).isEqualTo(1);
    assertThat(telemetry.succeeded).isEqualTo(1);
    assertThat(credentials.lease.closed).isTrue(); // bounded lifetime (Doc 25 §33.1 CR-4)
  }

  @Test
  void streamingResultPassesThrough() {
    capabilities.mapping = CAPS;
    translator.out = transportRequest();
    transport.response = new TransportResponse.Buffered(200, Map.of(), new byte[] {9});
    final Flow.Publisher<io.reliabilityai.gateway.canonical.stream.StreamChunk> pub =
        subscriber -> {};
    translator.in = new ProviderInvocationResult.Streaming(pub);
    assertThat(service().invoke(REQUEST, ROUTE, BUDGET))
        .isInstanceOf(ProviderInvocationResult.Streaming.class);
    assertThat(telemetry.succeeded).isEqualTo(1);
  }

  @Test
  void capabilitySnapshotMissFailsClosedUnknown() {
    capabilities.mapping = null; // stale/absent snapshot (Doc 25 §14.1 CAP-5)
    final ProviderInvocationResult result = service().invoke(REQUEST, ROUTE, BUDGET);
    assertThat(category(result)).isEqualTo(ErrorCategory.UNKNOWN);
    assertThat(credentials.acquired).isFalse(); // never reached credential acquisition
    assertThat(transport.calls).isZero();
  }

  @Test
  void credentialMissFailsClosedAuthFailed() {
    capabilities.mapping = CAPS;
    credentials.present = false; // cache miss (Doc 25 §33.1 CR-6)
    final ProviderInvocationResult result = service().invoke(REQUEST, ROUTE, BUDGET);
    assertThat(category(result)).isEqualTo(ErrorCategory.AUTH_FAILED);
    assertThat(transport.calls).isZero(); // fail closed, no bypass
  }

  @Test
  void translateOutFailureFailsClosedMalformed() {
    capabilities.mapping = CAPS;
    translator.throwOnOut = true;
    final ProviderInvocationResult result = service().invoke(REQUEST, ROUTE, BUDGET);
    assertThat(category(result)).isEqualTo(ErrorCategory.MALFORMED_RESPONSE);
    assertThat(transport.calls).isZero();
    assertThat(credentials.lease.closed).isTrue();
  }

  @Test
  void transportExceptionSurfacesClassifiedErrorAndClosesLease() {
    capabilities.mapping = CAPS;
    translator.out = transportRequest();
    transport.failure = new TransportException(TransportFailureKind.TIMEOUT, "t");
    final ProviderInvocationResult result = service().invoke(REQUEST, ROUTE, BUDGET);
    assertThat(category(result)).isEqualTo(ErrorCategory.TIMEOUT);
    assertThat(credentials.lease.closed).isTrue(); // lease closed even on transport failure
    assertThat(transport.lastBudget)
        .isSameAs(BUDGET); // handed budget passed through (Doc 25 §17.1)
  }

  @Test
  void translateInFailureFailsClosedMalformed() {
    capabilities.mapping = CAPS;
    translator.out = transportRequest();
    transport.response = new TransportResponse.Buffered(200, Map.of(), new byte[] {9});
    translator.throwOnIn = true;
    assertThat(category(service().invoke(REQUEST, ROUTE, BUDGET)))
        .isEqualTo(ErrorCategory.MALFORMED_RESPONSE);
  }

  @Test
  void translatorFailedResultPassesThrough() {
    capabilities.mapping = CAPS;
    translator.out = transportRequest();
    transport.response = new TransportResponse.Buffered(429, Map.of(), new byte[] {9});
    translator.in =
        new ProviderInvocationResult.Failed(
            new CanonicalError(ErrorCategory.RATE_LIMITED, Boolean.TRUE, "429", true));
    final ProviderInvocationResult result = service().invoke(REQUEST, ROUTE, BUDGET);
    assertThat(category(result)).isEqualTo(ErrorCategory.RATE_LIMITED);
    assertThat(telemetry.failedCategory).isEqualTo(ErrorCategory.RATE_LIMITED);
    assertThat(telemetry.succeeded).isZero();
  }

  @Test
  void telemetryFailureNeverBreaksOutcome() {
    capabilities.mapping = CAPS;
    translator.out = transportRequest();
    transport.response = new TransportResponse.Buffered(200, Map.of(), new byte[] {9});
    translator.in = unary();
    telemetry.explode = true;
    assertThat(service().invoke(REQUEST, ROUTE, BUDGET))
        .isInstanceOf(ProviderInvocationResult.Unary.class);
  }

  @Test
  void unexpectedInternalErrorFailsClosedUnknown() {
    capabilities.mapping = CAPS;
    translator.out = transportRequest();
    transport.response = new TransportResponse.Buffered(200, Map.of(), new byte[] {9});
    translator.returnNullIn = true; // contract violation → NPE → fail-closed backstop
    assertThat(category(service().invoke(REQUEST, ROUTE, BUDGET))).isEqualTo(ErrorCategory.UNKNOWN);
  }

  @Test
  void rejectsNullArguments() {
    final ProviderAdapterService s = service();
    assertThatThrownBy(() -> s.invoke(null, ROUTE, BUDGET))
        .isInstanceOf(NullPointerException.class);
    assertThatThrownBy(() -> s.invoke(REQUEST, null, BUDGET))
        .isInstanceOf(NullPointerException.class);
    assertThatThrownBy(() -> s.invoke(REQUEST, ROUTE, null))
        .isInstanceOf(NullPointerException.class);
  }

  private static ErrorCategory category(final ProviderInvocationResult result) {
    return ((ProviderInvocationResult.Failed) result).error().category();
  }

  private static final class FakeTranslator implements ProviderTranslator {
    private TransportRequest out;
    private ProviderInvocationResult in;
    private boolean throwOnOut;
    private boolean throwOnIn;
    private boolean returnNullIn;

    @Override
    public TransportRequest translateOut(
        final CanonicalRequest request,
        final CapabilityMapping capabilities,
        final PinnedVersion pinnedVersion) {
      if (throwOnOut) {
        throw new IllegalStateException("unmappable");
      }
      return out;
    }

    @Override
    public ProviderInvocationResult translateIn(final TransportResponse response) {
      if (throwOnIn) {
        throw new IllegalStateException("unmappable");
      }
      return returnNullIn ? null : in;
    }

    @Override
    public CanonicalError classifyTransportFailure(final TransportException failure) {
      return new CanonicalError(ErrorCategory.TIMEOUT, Boolean.TRUE, failure.codeOpaque(), true);
    }
  }

  private static final class FakeTransport implements ProviderTransportPort {
    private TransportResponse response;
    private TransportException failure;
    private int calls;
    private AttemptBudget lastBudget;

    @Override
    public TransportResponse exchange(
        final TransportRequest request,
        final CredentialLease credential,
        final AttemptBudget budget)
        throws TransportException {
      calls++;
      lastBudget = budget;
      if (failure != null) {
        throw failure;
      }
      return response;
    }
  }

  private static final class FakeCredentialPort implements CredentialPort {
    private boolean present = true;
    private boolean acquired;
    private final FakeLease lease = new FakeLease();

    @Override
    public Optional<CredentialLease> acquire(final RouteTarget routeTarget) {
      acquired = true;
      return present ? Optional.of(lease) : Optional.empty();
    }
  }

  private static final class FakeCapabilityPort implements CapabilitySnapshotPort {
    private CapabilityMapping mapping;

    @Override
    public Optional<CapabilityMapping> mappingFor(final RouteTarget routeTarget) {
      return Optional.ofNullable(mapping);
    }
  }

  private static final class FakeLease implements CredentialLease {
    private boolean closed;

    @Override
    public String leaseId() {
      return "lease-1";
    }

    @Override
    public TenantScope tenantScope() {
      return TenantScope.of("org", "tenant");
    }

    @Override
    public Instant notAfter() {
      return Instant.parse("2099-01-01T00:00:00Z");
    }

    @Override
    public boolean active() {
      return !closed;
    }

    @Override
    public void use(final SecretConsumer consumer) {
      consumer.accept(new char[] {'k'});
    }

    @Override
    public void close() {
      closed = true;
    }
  }

  private static final class CountingTelemetry implements AdapterTelemetryPort {
    private int invoked;
    private int succeeded;
    private ErrorCategory failedCategory;
    private boolean explode;

    @Override
    public void invoked(
        final CanonicalModelId model,
        final PinnedVersion providerApiVersion,
        final SnapshotVersion snapshotVersion) {
      if (explode) {
        throw new IllegalStateException("telemetry down");
      }
      invoked++;
    }

    @Override
    public void succeeded(final CanonicalModelId model) {
      if (explode) {
        throw new IllegalStateException("telemetry down");
      }
      succeeded++;
    }

    @Override
    public void failed(final CanonicalModelId model, final ErrorCategory category) {
      failedCategory = category;
    }
  }
}

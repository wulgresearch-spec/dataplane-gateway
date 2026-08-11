package io.reliabilityai.gateway.dataplane.app.runtime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sun.net.httpserver.HttpServer;
import io.reliabilityai.gateway.canonical.context.RequestContext;
import io.reliabilityai.gateway.canonical.identity.CausationId;
import io.reliabilityai.gateway.canonical.identity.CorrelationId;
import io.reliabilityai.gateway.canonical.identity.IdempotencyKey;
import io.reliabilityai.gateway.canonical.identity.Region;
import io.reliabilityai.gateway.canonical.identity.RequestId;
import io.reliabilityai.gateway.canonical.identity.SnapshotVersion;
import io.reliabilityai.gateway.canonical.io.CanonicalRequest;
import io.reliabilityai.gateway.canonical.io.ForwardedTransportIdentity;
import io.reliabilityai.gateway.canonical.snapshot.CredentialSnapshotRef;
import io.reliabilityai.gateway.dataplane.app.MandatoryStage;
import io.reliabilityai.gateway.dataplane.app.StartupValidationException;
import io.reliabilityai.gateway.dataplane.app.pipeline.PipelineOutcome;
import io.reliabilityai.gateway.dataplane.app.pipeline.RequestExecution;
import io.reliabilityai.gateway.dataplane.governance.api.AuditSinkPort;
import io.reliabilityai.gateway.dataplane.schemalock.api.Mode;
import io.reliabilityai.gateway.dataplane.secrets.adapter.LastKnownGoodCredentialSnapshotCache;
import io.reliabilityai.gateway.dataplane.secrets.adapter.SnapshotCredentialMaterialSource;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The first complete execution path: ingress → authn → governance → router → credential resolution
 * → reliability → OpenAI provider → canonical response, exercised against a loopback provider.
 */
class OpenAiRuntimeWiringTest {

  @TempDir Path walDir;
  @TempDir Path dlqDir;

  private final List<String> providerPaths = new CopyOnWriteArrayList<>();
  private final List<String> providerAuth = new CopyOnWriteArrayList<>();
  private HttpServer provider;
  private GatewayRuntime runtime;

  @AfterEach
  void tearDown() {
    if (runtime != null && runtime.state() == GatewayRuntime.State.READY) {
      runtime.stop();
    }
    if (provider != null) {
      provider.stop(0);
    }
  }

  private URI startProvider(final int status, final String body) throws IOException {
    provider = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    provider.createContext(
        "/",
        exchange -> {
          providerPaths.add(exchange.getRequestURI().getPath());
          providerAuth.add(String.valueOf(exchange.getRequestHeaders().getFirst("Authorization")));
          exchange.getRequestBody().readAllBytes();
          final byte[] payload = body.getBytes(StandardCharsets.UTF_8);
          exchange.getResponseHeaders().add("Content-Type", "application/json");
          exchange.sendResponseHeaders(status, payload.length);
          try (OutputStream out = exchange.getResponseBody()) {
            out.write(payload);
          }
        });
    provider.start();
    return URI.create("http://127.0.0.1:" + provider.getAddress().getPort());
  }

  private GatewayRuntime runtime(
      final Optional<GatewayRuntimeConfig.ProviderConfig> providerConfig) {
    return new GatewayRuntime(
        RuntimeFixture.config(
            RuntimeFixture.masterKey(),
            walDir,
            dlqDir.resolve("dlq.log"),
            record -> {},
            new RuntimeFixture.TestClock(),
            RuntimeFixture.authenticatingExternalAdapters(),
            Optional.of(
                RuntimeFixture.governanceConfig(
                    RuntimeFixture.permittingPolicy(), AuditSinkPort.NO_OP)),
            providerConfig));
  }

  /** Publishes credential material for the fixture tenant + route so SECRETS can materialize. */
  private void publishCredential(final GatewayRuntime gateway) {
    final LastKnownGoodCredentialSnapshotCache cache = gateway.credentialCache();
    cache.applyPublished(
        new SnapshotVersion("credentials", "v1"),
        Map.of(
            new LastKnownGoodCredentialSnapshotCache.Key(RuntimeFixture.TENANT, "route-a"),
            new SnapshotCredentialMaterialSource(
                new CredentialSnapshotRef(
                    new SnapshotVersion("credentials", "v1"),
                    RuntimeFixture.TENANT,
                    Instant.parse("2030-01-01T00:00:00Z")),
                "sk-live-test".toCharArray())));
  }

  private static RequestExecution.Inbound inbound() {
    return new RequestExecution.Inbound(
        new RequestId("req-1"),
        new RequestContext(
            new CorrelationId("corr-1"),
            new IdempotencyKey("idem-1"),
            new CausationId("cause-1"),
            "traceparent",
            new Region("us-east-1")),
        new ForwardedTransportIdentity("Bearer", "token", Map.of()),
        new CanonicalRequest(RuntimeFixture.MODEL, List.of(), List.of(), Map.of()),
        Set.of("chat"),
        1024,
        Set.of(),
        1_000_000L,
        Set.of(),
        Instant.parse("2026-01-01T00:00:30Z"),
        true,
        Mode.BATCH,
        Optional.empty(),
        Optional.empty());
  }

  // ---- construction and binding -------------------------------------------------------------

  @Test
  void runtimeConstructsAndBindsTheProvider() throws Exception {
    runtime = runtime(Optional.of(RuntimeFixture.providerConfig(startProvider(200, "{}"))));
    runtime.start();

    assertThat(runtime.providerAdapter()).isPresent();
    assertThat(runtime.reliabilityEngine()).isPresent();
    assertThat(runtime.capabilitySource()).isPresent();
    assertThat(runtime.boundStages()).contains(MandatoryStage.ADAPTER, MandatoryStage.RELIABILITY);
  }

  @Test
  void startupSucceedsAndRegistersProviderHealthWithoutProbing() throws Exception {
    runtime = runtime(Optional.of(RuntimeFixture.providerConfig(startProvider(200, "{}"))));
    runtime.start();

    assertThat(runtime.state()).isEqualTo(GatewayRuntime.State.READY);
    assertThat(runtime.providerHealth()).isPresent();
    assertThat(runtime.providerHealth().orElseThrow().detail()).isEqualTo("not-probed");
  }

  @Test
  void aFailingHealthProbeNeverPreventsStartup() throws Exception {
    final URI endpoint = startProvider(500, "{}");
    final GatewayRuntimeConfig.ProviderConfig withProbe =
        withHealthProbe(RuntimeFixture.providerConfig(endpoint));
    runtime = runtime(Optional.of(withProbe));

    runtime.start();

    // A provider outage must not become a gateway outage.
    assertThat(runtime.state()).isEqualTo(GatewayRuntime.State.READY);
    assertThat(runtime.providerHealth().orElseThrow().healthy()).isFalse();
  }

  @Test
  void startupFailsClosedWhenTheCapabilitySnapshotIsMissing() throws Exception {
    final GatewayRuntimeConfig.ProviderConfig base =
        RuntimeFixture.providerConfig(startProvider(200, "{}"));
    runtime =
        runtime(
            Optional.of(
                new GatewayRuntimeConfig.ProviderConfig(
                    base.transport(), Optional.empty(), base.credentials(), Optional.empty())));

    assertThatThrownBy(runtime::start)
        .isInstanceOf(StartupValidationException.class)
        .hasMessageContaining("ADAPTER");
  }

  @Test
  void startupFailsClosedWhenTheCredentialProviderIsMissing() throws Exception {
    final GatewayRuntimeConfig.ProviderConfig base =
        RuntimeFixture.providerConfig(startProvider(200, "{}"));
    runtime =
        runtime(
            Optional.of(
                new GatewayRuntimeConfig.ProviderConfig(
                    base.transport(),
                    base.capabilitySnapshot(),
                    Optional.empty(),
                    Optional.empty())));

    assertThatThrownBy(runtime::start)
        .isInstanceOf(StartupValidationException.class)
        .hasMessageContaining("ADAPTER");
  }

  @Test
  void startupFailsClosedWhenNoProviderIsConfiguredAtAll() {
    runtime = runtime(Optional.empty());

    assertThatThrownBy(runtime::start)
        .isInstanceOf(StartupValidationException.class)
        .hasMessageContaining("ADAPTER");
  }

  @Test
  void plaintextEndpointIsRefusedWhenTlsIsRequired() {
    assertThatThrownBy(
            () ->
                new GatewayRuntimeConfig.ProviderTransportConfig(
                    URI.create("http://api.example.com"),
                    new io.reliabilityai.gateway.dataplane.provider.api.PinnedVersion("v1"),
                    null,
                    null,
                    java.time.Duration.ofSeconds(1),
                    java.time.Duration.ofSeconds(1),
                    java.net.http.HttpClient.Version.HTTP_2,
                    8,
                    true,
                    true))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("https");
  }

  @Test
  void gracefulShutdownClosesTheProviderTransport() throws Exception {
    runtime = runtime(Optional.of(RuntimeFixture.providerConfig(startProvider(200, "{}"))));
    runtime.start();

    runtime.stop();

    assertThat(runtime.state()).isEqualTo(GatewayRuntime.State.STOPPED);
    assertThat(runtime.shutdownPhases())
        .containsSequence(
            GatewayRuntime.ShutdownPhase.CLOSE_ADAPTERS,
            GatewayRuntime.ShutdownPhase.ZEROIZE_SECRETS,
            GatewayRuntime.ShutdownPhase.EXIT);
  }

  // ---- end-to-end execution -----------------------------------------------------------------

  @Test
  void executesTheFullPathAndReturnsACanonicalResponse() throws Exception {
    final URI endpoint =
        startProvider(
            200,
            "{\"choices\":[{\"message\":{\"role\":\"assistant\",\"content\":\"pong\"},"
                + "\"finish_reason\":\"stop\"}],"
                + "\"usage\":{\"prompt_tokens\":5,\"completion_tokens\":1}}");
    runtime = runtime(Optional.of(RuntimeFixture.providerConfig(endpoint)));
    runtime.start();
    publishCredential(runtime);

    final PipelineOutcome outcome = runtime.pipeline().execute(inbound());

    assertThat(outcome).isInstanceOf(PipelineOutcome.Completed.class);
    final PipelineOutcome.Completed completed = (PipelineOutcome.Completed) outcome;
    assertThat(completed.response().content()).isEqualTo("pong");
    assertThat(completed.response().usage().prompt()).isEqualTo(5L);

    // Every mandatory stage ran, in frozen order — no bypass on the happy path.
    assertThat(outcome.trace().stages()).containsExactly(MandatoryStage.values());

    // The credential reached the provider, resolved per request from the secrets module.
    assertThat(providerPaths).containsExactly(OpenAiConfigurationPaths.CHAT);
    assertThat(providerAuth).containsExactly("Bearer sk-live-test");
  }

  @Test
  void routerOutputSelectsTheProviderRouteRatherThanAHardcodedProvider() throws Exception {
    // No usage block — the shape OpenAI legally returns and which used to crash the EMITTER stage.
    final URI endpoint =
        startProvider(
            200, "{\"choices\":[{\"message\":{\"content\":\"ok\"},\"finish_reason\":\"stop\"}]}");
    runtime = runtime(Optional.of(RuntimeFixture.providerConfig(endpoint)));
    runtime.start();
    publishCredential(runtime);

    final PipelineOutcome outcome = runtime.pipeline().execute(inbound());

    // End-to-end proof of the EMITTER fix: a real provider response without usage now completes.
    assertThat(outcome).isInstanceOf(PipelineOutcome.Completed.class);
    assertThat(outcome.trace().stages()).containsExactly(MandatoryStage.values());

    // The capability snapshot is keyed by the router's chosen route; a second provider would slot
    // in
    // by publishing another entry, with no pipeline change.
    assertThat(runtime.capabilitySource().orElseThrow().entryFor(RuntimeFixture.ROUTE)).isPresent();
  }

  @Test
  void providerUnavailableSurfacesAsACanonicalTransportFailure() throws Exception {
    // Point at a closed port: nothing is listening, so the transport cannot connect.
    runtime = runtime(Optional.of(RuntimeFixture.providerConfig(URI.create("http://127.0.0.1:1"))));
    runtime.start();
    publishCredential(runtime);

    final PipelineOutcome outcome = runtime.pipeline().execute(inbound());

    assertThat(outcome).isInstanceOf(PipelineOutcome.Refused.class);
    final PipelineOutcome.Refused refused = (PipelineOutcome.Refused) outcome;
    assertThat(refused.refusal().stage()).isEqualTo(MandatoryStage.ADAPTER);
    // Canonical, opaque — no provider text, no stack detail.
    assertThat(refused.refusal().error().providerCodeOpaque()).doesNotContain("Connection");
  }

  @Test
  void providerRejectionSurfacesAsACanonicalErrorWithoutProviderText() throws Exception {
    final URI endpoint =
        startProvider(429, "{\"error\":{\"message\":\"Rate limit for org-SECRET\"}}");
    runtime = runtime(Optional.of(RuntimeFixture.providerConfig(endpoint)));
    runtime.start();
    publishCredential(runtime);

    final PipelineOutcome outcome = runtime.pipeline().execute(inbound());

    final PipelineOutcome.Refused refused = (PipelineOutcome.Refused) outcome;
    assertThat(refused.refusal().stage()).isEqualTo(MandatoryStage.ADAPTER);
    assertThat(refused.refusal().error().providerCodeOpaque()).doesNotContain("SECRET");
  }

  @Test
  void withoutPublishedCredentialsTheRequestFailsClosedAtSecrets() throws Exception {
    runtime = runtime(Optional.of(RuntimeFixture.providerConfig(startProvider(200, "{}"))));
    runtime.start();

    final PipelineOutcome outcome = runtime.pipeline().execute(inbound());

    assertThat(((PipelineOutcome.Refused) outcome).refusal().stage())
        .isEqualTo(MandatoryStage.SECRETS);
    assertThat(providerPaths).isEmpty(); // no credential, no provider call
  }

  private static GatewayRuntimeConfig.ProviderConfig withHealthProbe(
      final GatewayRuntimeConfig.ProviderConfig base) {
    return new GatewayRuntimeConfig.ProviderConfig(
        base.transport(),
        base.capabilitySnapshot(),
        base.credentials(),
        Optional.of(
            new GatewayRuntimeConfig.HealthProbe(RuntimeFixture.TENANT, RuntimeFixture.ROUTE)),
        // Carried through deliberately: dropping the modules would leave the node with nothing to
        // dispatch to, and this test is about the probe, not about an unwired provider.
        base.modules());
  }

  /** The provider paths asserted above, kept out of the assertion for readability. */
  private static final class OpenAiConfigurationPaths {
    static final String CHAT = "/v1/chat/completions";

    private OpenAiConfigurationPaths() {}
  }
}

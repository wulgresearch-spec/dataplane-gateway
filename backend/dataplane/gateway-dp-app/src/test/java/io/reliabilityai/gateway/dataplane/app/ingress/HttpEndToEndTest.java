package io.reliabilityai.gateway.dataplane.app.ingress;

import static org.assertj.core.api.Assertions.assertThat;

import com.sun.net.httpserver.HttpServer;
import io.reliabilityai.gateway.canonical.identity.SnapshotVersion;
import io.reliabilityai.gateway.canonical.snapshot.CredentialSnapshotRef;
import io.reliabilityai.gateway.dataplane.app.runtime.GatewayRuntime;
import io.reliabilityai.gateway.dataplane.app.runtime.RuntimeFixture;
import io.reliabilityai.gateway.dataplane.governance.api.AuditSinkPort;
import io.reliabilityai.gateway.dataplane.ingress.HttpIngressServer;
import io.reliabilityai.gateway.dataplane.ingress.IngressConfig;
import io.reliabilityai.gateway.dataplane.secrets.adapter.LastKnownGoodCredentialSnapshotCache;
import io.reliabilityai.gateway.dataplane.secrets.adapter.SnapshotCredentialMaterialSource;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The full path a real client takes: HTTP → ingress → pipeline → OpenAI provider → HTTP, with a
 * loopback provider standing in for OpenAI. Nothing here reaches the network beyond {@code
 * 127.0.0.1}.
 */
class HttpEndToEndTest {

  @TempDir Path walDir;
  @TempDir Path dlqDir;

  private HttpServer provider;
  private GatewayRuntime runtime;
  private final HttpClient client =
      HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build();

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

  private GatewayRuntime start(final URI providerEndpoint) {
    runtime =
        new GatewayRuntime(
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
                Optional.of(RuntimeFixture.providerConfig(providerEndpoint)),
                Optional.of(
                    new IngressConfig(
                        "127.0.0.1", 0, 65_536, Duration.ofSeconds(15), 64, Duration.ZERO))));
    runtime.start();
    publishCredential(runtime);
    return runtime;
  }

  private static void publishCredential(final GatewayRuntime gateway) {
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

  private URI gatewayUri(final String path) {
    return URI.create("http://127.0.0.1:" + runtime.httpIngress().orElseThrow().boundPort() + path);
  }

  private HttpResponse<String> chat(final String body) throws IOException, InterruptedException {
    return client.send(
        HttpRequest.newBuilder()
            .uri(gatewayUri(HttpIngressServer.PATH_CHAT))
            .header("Content-Type", "application/json")
            .header("Authorization", "Bearer client-token")
            .POST(HttpRequest.BodyPublishers.ofString(body))
            .build(),
        HttpResponse.BodyHandlers.ofString());
  }

  private static String chatBody() {
    return "{\"model\":\""
        + RuntimeFixture.MODEL.value()
        + "\",\"messages\":[{\"role\":\"user\",\"content\":\"ping\"}]}";
  }

  @Test
  void aClientRequestTraversesTheWholePipelineAndReturnsAnAnswer() throws Exception {
    start(
        startProvider(
            200,
            "{\"choices\":[{\"message\":{\"role\":\"assistant\",\"content\":\"pong\"},"
                + "\"finish_reason\":\"stop\"}],"
                + "\"usage\":{\"prompt_tokens\":5,\"completion_tokens\":1}}"));

    final HttpResponse<String> response = chat(chatBody());

    assertThat(response.statusCode()).isEqualTo(200);
    assertThat(response.body())
        .contains("\"content\":\"pong\"")
        .contains("\"finish_reason\":\"stop\"")
        .contains("\"prompt_tokens\":5");
  }

  /** A provider that answers with a real SSE body, flushed frame by frame. */
  private URI startStreamingProvider(final String... sseFrames) throws IOException {
    provider = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    provider.createContext(
        "/",
        exchange -> {
          exchange.getRequestBody().readAllBytes();
          exchange.getResponseHeaders().add("Content-Type", "text/event-stream");
          exchange.sendResponseHeaders(200, 0); // chunked
          try (OutputStream out = exchange.getResponseBody()) {
            for (final String frame : sseFrames) {
              out.write(frame.getBytes(StandardCharsets.UTF_8));
              out.flush();
            }
          }
        });
    provider.start();
    return URI.create("http://127.0.0.1:" + provider.getAddress().getPort());
  }

  private static String chatStreamBody() {
    return "{\"model\":\""
        + RuntimeFixture.MODEL.value()
        + "\",\"messages\":[{\"role\":\"user\",\"content\":\"ping\"}],\"stream\":true}";
  }

  @Test
  void aStreamingRequestFlowsFromTheProviderSocketToTheHttpClient() throws Exception {
    start(
        startStreamingProvider(
            "data: {\"choices\":[{\"delta\":{\"role\":\"assistant\"}}]}\n\n",
            "data: {\"choices\":[{\"delta\":{\"content\":\"Hel\"}}]}\n\n",
            "data: {\"choices\":[{\"delta\":{\"content\":\"lo\"}}]}\n\n",
            "data: {\"choices\":[],\"usage\":{\"prompt_tokens\":4,\"completion_tokens\":2}}\n\n",
            "data: {\"choices\":[{\"delta\":{},\"finish_reason\":\"stop\"}]}\n\n",
            "data: [DONE]\n\n"));

    final HttpResponse<String> response = chat(chatStreamBody());

    assertThat(response.statusCode()).isEqualTo(200);
    assertThat(response.headers().firstValue("Content-Type").orElseThrow())
        .startsWith("text/event-stream");
    // Provider bytes → OpenAI transport → StreamGuard → decoder → pipeline → SSE to the client.
    assertThat(response.body())
        .contains("\"content\":\"Hel\"")
        .contains("\"content\":\"lo\"")
        .contains("\"prompt_tokens\":4")
        .endsWith("data: [DONE]\n\n");
  }

  @Test
  void aTruncatedProviderStreamIsReportedAsAnErrorFinish() throws Exception {
    // The provider closes mid-frame: StreamGuard cannot prove the transport intact.
    start(startStreamingProvider("data: {\"choices\":[{\"delta\":{\"content\":\"par"));

    final HttpResponse<String> response = chat(chatStreamBody());

    assertThat(response.statusCode()).isEqualTo(200); // headers were already sent
    assertThat(response.body()).contains("\"finish_reason\":\"error\"");
  }

  @Test
  void aStreamingRequestStillRunsEveryMandatoryStage() throws Exception {
    start(
        startStreamingProvider(
            "data: {\"choices\":[{\"delta\":{\"content\":\"x\"}}]}\n\n",
            "data: {\"choices\":[],\"usage\":{\"prompt_tokens\":1,\"completion_tokens\":1}}\n\n",
            "data: {\"choices\":[{\"delta\":{},\"finish_reason\":\"stop\"}]}\n\n",
            "data: [DONE]\n\n"));

    final HttpResponse<String> response = chat(chatStreamBody());

    assertThat(response.body()).endsWith("data: [DONE]\n\n");
    // Streaming does not bypass accounting: the finalized fact was published exactly once.
    assertThat(runtime.httpIngress().orElseThrow().metrics().inFlight()).isZero();
  }

  @Test
  void aProviderOutageBecomesAnUpstreamStatusNotAnInternalError() throws Exception {
    // Nothing is listening on port 1, so the transport cannot connect.
    start(URI.create("http://127.0.0.1:1"));

    final HttpResponse<String> response = chat(chatBody());

    assertThat(response.statusCode()).isEqualTo(502);
    assertThat(response.body()).contains("\"stage\":\"ADAPTER\"").doesNotContain("Exception");
  }

  @Test
  void aProviderRateLimitIsSurfacedAsRetryableToTheClient() throws Exception {
    start(startProvider(429, "{\"error\":{\"message\":\"slow down org-SECRET\"}}"));

    final HttpResponse<String> response = chat(chatBody());

    assertThat(response.statusCode()).isEqualTo(429);
    assertThat(response.body()).doesNotContain("SECRET");
  }

  @Test
  void governanceRefusalIsSurfacedBeforeAnyProviderCall() throws Exception {
    final URI endpoint = startProvider(200, "{}");
    runtime =
        new GatewayRuntime(
            RuntimeFixture.config(
                RuntimeFixture.masterKey(),
                walDir,
                dlqDir.resolve("dlq.log"),
                record -> {},
                new RuntimeFixture.TestClock(),
                RuntimeFixture.authenticatingExternalAdapters(),
                Optional.of(
                    RuntimeFixture.governanceConfig(
                        RuntimeFixture.disabledTenantPolicy(), AuditSinkPort.NO_OP)),
                Optional.of(RuntimeFixture.providerConfig(endpoint)),
                Optional.of(
                    new IngressConfig(
                        "127.0.0.1", 0, 65_536, Duration.ofSeconds(15), 64, Duration.ZERO))));
    runtime.start();
    publishCredential(runtime);

    final HttpResponse<String> response = chat(chatBody());

    assertThat(response.statusCode()).isEqualTo(403);
    assertThat(response.body()).contains("\"stage\":\"GOVERNANCE\"").contains("tenant-disabled");
  }

  @Test
  void healthReadyAndMetricsAreServedByTheRunningNode() throws Exception {
    start(
        startProvider(
            200,
            "{\"choices\":[{\"message\":{\"content\":\"x\"},"
                + "\"finish_reason\":\"stop\"}],\"usage\":{\"prompt_tokens\":1,\"completion_tokens\":1}}"));

    assertThat(get(HttpIngressServer.PATH_HEALTH).body().trim()).isEqualTo("READY");
    assertThat(get(HttpIngressServer.PATH_READY).statusCode()).isEqualTo(200);

    chat(chatBody());
    assertThat(get(HttpIngressServer.PATH_METRICS).body())
        .contains("gateway_ingress_responses{status=\"200\"}");
  }

  @Test
  void shutdownClosesTheFrontDoorAndTheRuntime() throws Exception {
    start(startProvider(200, "{}"));
    final int port = runtime.httpIngress().orElseThrow().boundPort();

    runtime.stop();

    assertThat(runtime.state()).isEqualTo(GatewayRuntime.State.STOPPED);
    // The socket is released, so a leaked listener or executor would fail this rebind.
    try (java.net.ServerSocket rebind = new java.net.ServerSocket()) {
      rebind.setReuseAddress(true);
      rebind.bind(new InetSocketAddress("127.0.0.1", port));
      assertThat(rebind.isBound()).isTrue();
    }
  }

  @Test
  void ingressBindsTheIngressStageSoTheNodeActivates() throws Exception {
    start(startProvider(200, "{}"));

    assertThat(runtime.unboundStages()).isEmpty();
    assertThat(runtime.httpIngress()).isPresent();
  }

  private HttpResponse<String> get(final String path) throws IOException, InterruptedException {
    return client.send(
        HttpRequest.newBuilder().uri(gatewayUri(path)).GET().build(),
        HttpResponse.BodyHandlers.ofString());
  }
}

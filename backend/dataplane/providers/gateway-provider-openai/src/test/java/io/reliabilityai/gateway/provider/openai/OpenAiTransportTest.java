package io.reliabilityai.gateway.provider.openai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.reliabilityai.gateway.dataplane.provider.api.TransportException;
import io.reliabilityai.gateway.dataplane.provider.api.TransportRequest;
import io.reliabilityai.gateway.dataplane.provider.api.TransportResponse;
import io.reliabilityai.gateway.dataplane.provider.domain.TransportFailureKind;
import io.reliabilityai.gateway.ports.AttemptBudget;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Transport behaviour against a loopback server: auth, timeouts, compression, and no-retry. */
class OpenAiTransportTest {

  private FakeOpenAiServer server;
  private OpenAiConfiguration configuration;
  private OpenAiProviderTransport transport;

  @BeforeEach
  void setUp() {
    server = new FakeOpenAiServer();
    configuration = configurationFor(server.baseUri(), Duration.ofSeconds(5));
    transport =
        new OpenAiProviderTransport(
            OpenAiProviderTransport.defaultClient(configuration),
            configuration,
            new OpenAiAuthentication());
  }

  @AfterEach
  void tearDown() {
    server.close();
  }

  private static OpenAiConfiguration configurationFor(final URI base, final Duration connect) {
    return new OpenAiConfiguration(
        base, OpenAiFixture.API_VERSION, connect, Duration.ofSeconds(30), null, true);
  }

  private static TransportRequest chatRequest() {
    return new TransportRequest(
        OpenAiConfiguration.CHAT_COMPLETIONS_PATH,
        Map.of("Content-Type", "application/json"),
        "{\"model\":\"gpt-4o-mini\"}".getBytes(StandardCharsets.UTF_8),
        OpenAiFixture.API_VERSION);
  }

  private static AttemptBudget budget(final Duration timeout) {
    return new AttemptBudget(timeout);
  }

  @Test
  void sendsBearerCredentialFromTheLease() throws Exception {
    server.respond(200, "{\"choices\":[]}");
    final OpenAiFixture.TestLease lease = new OpenAiFixture.TestLease("sk-test-123");

    transport.exchange(chatRequest(), lease, budget(Duration.ofSeconds(5)));

    assertThat(server.lastRequest().headers()).containsEntry("authorization", "Bearer sk-test-123");
    assertThat(lease.uses()).isEqualTo(1); // read once, at send time
  }

  @Test
  void refusesToSendWithAnInactiveLease() {
    final OpenAiFixture.TestLease lease = new OpenAiFixture.TestLease("sk-test-123");
    lease.close();

    assertThatThrownBy(
            () -> transport.exchange(chatRequest(), lease, budget(Duration.ofSeconds(5))))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("not active");
    assertThat(server.captured()).isEmpty(); // nothing left the process
  }

  @Test
  void postsWhenABodyIsPresentAndGetsWhenItIsNot() throws Exception {
    server.respond(200, "{\"data\":[]}");
    final OpenAiFixture.TestLease lease = new OpenAiFixture.TestLease("sk");

    transport.exchange(chatRequest(), lease, budget(Duration.ofSeconds(5)));
    assertThat(server.lastRequest().method()).isEqualTo("POST");

    transport.exchange(
        new TransportRequest(
            OpenAiConfiguration.MODELS_PATH, Map.of(), new byte[0], OpenAiFixture.API_VERSION),
        lease,
        budget(Duration.ofSeconds(5)));
    assertThat(server.lastRequest().method()).isEqualTo("GET");
  }

  @Test
  void inflatesGzippedResponses() throws Exception {
    server.respond(200, "{\"choices\":[{\"message\":{\"content\":\"compressed\"}}]}").gzipped();

    final TransportResponse response =
        transport.exchange(
            chatRequest(), new OpenAiFixture.TestLease("sk"), budget(Duration.ofSeconds(5)));

    final String body =
        new String(((TransportResponse.Buffered) response).body(), StandardCharsets.UTF_8);
    assertThat(body).contains("compressed");
  }

  @Test
  void surfacesNonSuccessStatusWithoutThrowing() throws Exception {
    server.respond(429, "{\"error\":{\"message\":\"slow down\"}}");

    final TransportResponse response =
        transport.exchange(
            chatRequest(), new OpenAiFixture.TestLease("sk"), budget(Duration.ofSeconds(5)));

    // Status interpretation belongs to the translator, not the transport.
    assertThat(response.status()).isEqualTo(429);
  }

  @Test
  void mapsAnExceededBudgetToATimeoutFailure() {
    server.respond(200, "{}").delay(1_500);

    assertThatThrownBy(
            () ->
                transport.exchange(
                    chatRequest(),
                    new OpenAiFixture.TestLease("sk"),
                    budget(Duration.ofMillis(150))))
        .isInstanceOf(TransportException.class)
        .satisfies(
            thrown ->
                assertThat(((TransportException) thrown).kind())
                    .isEqualTo(TransportFailureKind.TIMEOUT));
  }

  @Test
  void mapsAnUnreachableHostToAConnectFailure() {
    final OpenAiConfiguration unreachable =
        configurationFor(URI.create("http://127.0.0.1:1"), Duration.ofMillis(400));
    final OpenAiProviderTransport isolated =
        new OpenAiProviderTransport(
            OpenAiProviderTransport.defaultClient(unreachable),
            unreachable,
            new OpenAiAuthentication());

    assertThatThrownBy(
            () ->
                isolated.exchange(
                    chatRequest(),
                    new OpenAiFixture.TestLease("sk"),
                    budget(Duration.ofSeconds(2))))
        .isInstanceOf(TransportException.class)
        .satisfies(
            thrown ->
                assertThat(((TransportException) thrown).kind())
                    .isIn(TransportFailureKind.CONNECT, TransportFailureKind.IO));
  }

  @Test
  void neverRetriesOnItsOwn() throws Exception {
    server.respond(503, "{}");

    transport.exchange(
        chatRequest(), new OpenAiFixture.TestLease("sk"), budget(Duration.ofSeconds(5)));

    // Retry is the Reliability Engine's decision and its budget. Exactly one attempt left this
    // class.
    assertThat(server.captured()).hasSize(1);
  }

  @Test
  void neverRetriesAfterATransportFailure() {
    final OpenAiConfiguration unreachable =
        configurationFor(URI.create("http://127.0.0.1:1"), Duration.ofMillis(300));
    final OpenAiProviderTransport isolated =
        new OpenAiProviderTransport(
            OpenAiProviderTransport.defaultClient(unreachable),
            unreachable,
            new OpenAiAuthentication());

    assertThatThrownBy(
            () ->
                isolated.exchange(
                    chatRequest(),
                    new OpenAiFixture.TestLease("sk"),
                    budget(Duration.ofSeconds(2))))
        .isInstanceOf(TransportException.class);
    assertThat(server.captured()).isEmpty();
  }

  @Test
  void healthCheckReportsHealthyAndListsModels() {
    server.respond(
        200, "{\"data\":[{\"id\":\"gpt-4o-mini\"},{\"id\":\"gpt-4o\"},{\"id\":\"o1\"}]}");
    final OpenAiHealthCheck health = new OpenAiHealthCheck(transport, configuration);

    final OpenAiHealthCheck.Health verdict =
        health.probe(new OpenAiFixture.TestLease("sk"), budget(Duration.ofSeconds(5)));

    assertThat(verdict.healthy()).isTrue();
    assertThat(verdict.models()).containsExactly("gpt-4o", "gpt-4o-mini", "o1"); // sorted
    assertThat(server.lastRequest().path()).isEqualTo(OpenAiConfiguration.MODELS_PATH);
    assertThat(server.lastRequest().headers()).containsKey("authorization");
  }

  @Test
  void healthCheckReportsUnhealthyOnRejectedCredentialWithoutThrowing() {
    server.respond(401, "{\"error\":{\"message\":\"invalid api key sk-live-abc\"}}");
    final OpenAiHealthCheck health = new OpenAiHealthCheck(transport, configuration);

    final OpenAiHealthCheck.Health verdict =
        health.probe(new OpenAiFixture.TestLease("sk"), budget(Duration.ofSeconds(5)));

    assertThat(verdict.healthy()).isFalse();
    assertThat(verdict.status()).isEqualTo(401);
    assertThat(verdict.detail()).isEqualTo("status:401").doesNotContain("sk-live-abc");
  }

  @Test
  void healthCheckReportsUnhealthyWhenTheProviderIsUnreachable() {
    final OpenAiConfiguration unreachable =
        configurationFor(URI.create("http://127.0.0.1:1"), Duration.ofMillis(300));
    final OpenAiHealthCheck health =
        new OpenAiHealthCheck(
            new OpenAiProviderTransport(
                OpenAiProviderTransport.defaultClient(unreachable),
                unreachable,
                new OpenAiAuthentication()),
            unreachable);

    final OpenAiHealthCheck.Health verdict =
        health.probe(new OpenAiFixture.TestLease("sk"), budget(Duration.ofSeconds(2)));

    // A probe that throws is a worse operational signal than one that reports unhealthy.
    assertThat(verdict.healthy()).isFalse();
    assertThat(verdict.detail()).startsWith("transport:");
  }
}

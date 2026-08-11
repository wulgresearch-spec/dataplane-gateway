package io.reliabilityai.gateway.provider.openai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.reliabilityai.gateway.dataplane.embedding.api.EmbeddingFailure;
import io.reliabilityai.gateway.dataplane.embedding.api.EmbeddingTransportException;
import io.reliabilityai.gateway.dataplane.embedding.api.EmbeddingTransportPort;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import java.util.zip.GZIPOutputStream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * The production embedding transport, exercised over a real loopback socket.
 *
 * <p><b>This is the first embedding test in the repository that sends actual bytes over TCP.</b>
 * Every other one runs against an in-process double, which is why B57 says the adapter is
 * unverified — a double cannot be wrong about chunked encoding, gzip, header restrictions or what a
 * socket does when it closes mid-body. It still is not the live vendor API, and that distinction is
 * kept: what is verified here is the transport's own behaviour, against a server this file
 * controls.
 */
@DisplayName("http embedding transport")
final class HttpEmbeddingTransportTest {

  private HttpServer server;
  private String base;
  private final List<Headers> received = new ArrayList<>();
  private final AtomicReference<Responder> responder = new AtomicReference<>();

  /** What a request looked like when the server saw it. */
  private record Headers(String method, Map<String, List<String>> headers, byte[] body) {}

  /** How the server should answer. */
  @FunctionalInterface
  private interface Responder {
    void respond(HttpExchange exchange) throws IOException;
  }

  @BeforeEach
  void startServer() throws IOException {
    server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext(
        "/",
        exchange -> {
          final byte[] body = exchange.getRequestBody().readAllBytes();
          received.add(
              new Headers(
                  exchange.getRequestMethod(), Map.copyOf(exchange.getRequestHeaders()), body));
          responder.get().respond(exchange);
        });
    server.start();
    base = "http://127.0.0.1:" + server.getAddress().getPort();
  }

  @AfterEach
  void stopServer() {
    server.stop(0);
  }

  private static void ok(final HttpExchange exchange, final String body) throws IOException {
    final byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
    exchange.sendResponseHeaders(200, bytes.length);
    exchange.getResponseBody().write(bytes);
    exchange.close();
  }

  private static EmbeddingTransportPort.Call call(final String url, final String body) {
    return new EmbeddingTransportPort.Call(
        "POST",
        url,
        Map.of("Authorization", "Bearer sk-test", "Content-Type", "application/json"),
        body.getBytes(StandardCharsets.UTF_8),
        5_000L);
  }

  /**
   * Header lookup that does not care about case.
   *
   * <p>{@code com.sun.net.httpserver.Headers} normalises every key it stores — "Accept-Encoding"
   * comes back as "Accept-encoding" — so an exact-name lookup silently returns null and the
   * assertion fails for a reason that has nothing to do with the transport.
   *
   * @param headers what the server saw
   * @param name the header to find
   * @return the values, or an empty list
   */
  private static List<String> header(final Map<String, List<String>> headers, final String name) {
    return headers.entrySet().stream()
        .filter(e -> e.getKey().equalsIgnoreCase(name))
        .findFirst()
        .map(Map.Entry::getValue)
        .orElse(List.of());
  }

  private static HttpEmbeddingTransport transport() {
    return new HttpEmbeddingTransport(Duration.ofSeconds(5));
  }

  @Test
  @Timeout(30)
  @DisplayName("a request reaches the server with its method, headers and body intact")
  void aRequestReachesTheServerWithItsMethodHeadersAndBodyIntact() {
    responder.set(exchange -> ok(exchange, "{\"data\":[]}"));

    final EmbeddingTransportPort.Exchange result =
        transport().send(call(base + "/v1/embeddings", "{\"input\":[\"hello\"]}"));

    assertThat(result.status()).isEqualTo(200);
    assertThat(received).hasSize(1);
    assertThat(received.get(0).method()).isEqualTo("POST");
    assertThat(new String(received.get(0).body(), StandardCharsets.UTF_8))
        .isEqualTo("{\"input\":[\"hello\"]}");
    assertThat(header(received.get(0).headers(), "Authorization"))
        .containsExactly("Bearer sk-test");
  }

  @Test
  @Timeout(30)
  @DisplayName("a non-200 status is returned, not thrown")
  void aNon200StatusIsReturnedNotThrown() {
    responder.set(
        exchange -> {
          final byte[] bytes = "{\"error\":\"slow down\"}".getBytes(StandardCharsets.UTF_8);
          exchange.sendResponseHeaders(429, bytes.length);
          exchange.getResponseBody().write(bytes);
          exchange.close();
        });

    // The transport's job is the exchange, not the verdict. Classifying 429 is the adapter's, and
    // throwing here would take that decision away from the one place that maps status to a neutral
    // failure kind.
    final EmbeddingTransportPort.Exchange result = transport().send(call(base + "/x", "{}"));

    assertThat(result.status()).isEqualTo(429);
    assertThat(new String(result.body(), StandardCharsets.UTF_8)).contains("slow down");
  }

  @Test
  @Timeout(30)
  @DisplayName("a gzip response body is decompressed")
  void aGzipResponseBodyIsDecompressed() {
    responder.set(
        exchange -> {
          final ByteArrayOutputStream compressed = new ByteArrayOutputStream();
          try (GZIPOutputStream gzip = new GZIPOutputStream(compressed)) {
            gzip.write("{\"data\":[{\"embedding\":[0.5]}]}".getBytes(StandardCharsets.UTF_8));
          }
          final byte[] bytes = compressed.toByteArray();
          exchange.getResponseHeaders().add("Content-Encoding", "gzip");
          exchange.sendResponseHeaders(200, bytes.length);
          exchange.getResponseBody().write(bytes);
          exchange.close();
        });

    final EmbeddingTransportPort.Exchange result = transport().send(call(base + "/x", "{}"));

    assertThat(new String(result.body(), StandardCharsets.UTF_8)).contains("\"embedding\"");
  }

  @Test
  @Timeout(30)
  @DisplayName("gzip is requested, so a large float payload is not sent as plain text")
  void gzipIsRequested() {
    responder.set(exchange -> ok(exchange, "{}"));

    transport().send(call(base + "/x", "{}"));

    assertThat(header(received.get(0).headers(), "Accept-Encoding")).containsExactly("gzip");
  }

  @Test
  @Timeout(30)
  @DisplayName("a slow endpoint becomes a retryable TIMEOUT rather than hanging the caller")
  void aSlowEndpointBecomesARetryableTimeout() {
    responder.set(
        exchange -> {
          try {
            Thread.sleep(3_000L);
          } catch (final InterruptedException interrupted) {
            Thread.currentThread().interrupt();
          }
          ok(exchange, "{}");
        });

    final EmbeddingTransportPort.Call slow =
        new EmbeddingTransportPort.Call(
            "POST", base + "/x", Map.of(), "{}".getBytes(StandardCharsets.UTF_8), 150L);

    assertThatThrownBy(() -> transport().send(slow))
        .isInstanceOf(EmbeddingTransportException.class)
        .satisfies(
            thrown -> {
              final EmbeddingFailure reason = ((EmbeddingTransportException) thrown).reason();
              assertThat(reason).isEqualTo(EmbeddingFailure.TIMEOUT);
              assertThat(reason.retryable()).isTrue();
            });
  }

  @Test
  @Timeout(30)
  @DisplayName("an unreachable endpoint becomes a retryable NETWORK failure")
  void anUnreachableEndpointBecomesARetryableNetworkFailure() {
    server.stop(0);

    assertThatThrownBy(() -> transport().send(call(base + "/x", "{}")))
        .isInstanceOf(EmbeddingTransportException.class)
        .satisfies(
            thrown ->
                assertThat(((EmbeddingTransportException) thrown).reason().retryable()).isTrue());
  }

  @Test
  @Timeout(30)
  @DisplayName("a connection dropped mid-body is a failure, never a truncated success")
  void aConnectionDroppedMidBodyIsAFailureNeverATruncatedSuccess() {
    responder.set(
        exchange -> {
          // Promise 4096 bytes, send 10, hang up. A transport that returned the 10 would hand the
          // adapter a half-parsed vector, which is the one outcome worse than an error.
          exchange.sendResponseHeaders(200, 4096);
          exchange.getResponseBody().write(new byte[10]);
          exchange.close();
        });

    assertThatThrownBy(() -> transport().send(call(base + "/x", "{}")))
        .isInstanceOf(EmbeddingTransportException.class);
  }

  @Test
  @Timeout(30)
  @DisplayName("a non-http scheme is refused without a call, and is not retryable")
  void aNonHttpSchemeIsRefusedWithoutACall() {
    // An unchecked scheme would let a misconfigured endpoint read the local filesystem.
    for (final String hostile :
        List.of("file:///etc/passwd", "jar:file:///tmp/x.jar!/y", "ftp://h/x")) {
      assertThatThrownBy(() -> transport().send(call(hostile, "{}")))
          .isInstanceOf(EmbeddingTransportException.class)
          .satisfies(
              thrown -> {
                final EmbeddingFailure reason = ((EmbeddingTransportException) thrown).reason();
                assertThat(reason).isEqualTo(EmbeddingFailure.REJECTED);
                assertThat(reason.retryable()).isFalse();
              });
    }
    assertThat(received).isEmpty();
  }

  @Test
  @Timeout(30)
  @DisplayName("a redirect is not followed, so the credential is never forwarded")
  void aRedirectIsNotFollowedSoTheCredentialIsNeverForwarded() {
    responder.set(
        exchange -> {
          exchange.getResponseHeaders().add("Location", "http://attacker.invalid/collect");
          exchange.sendResponseHeaders(302, -1);
          exchange.close();
        });

    // Following a redirect on a credentialed POST resends the Authorization header to whatever host
    // the far end names — a credential-forwarding primitive controlled by the server.
    final EmbeddingTransportPort.Exchange result = transport().send(call(base + "/x", "{}"));

    assertThat(result.status()).isEqualTo(302);
    assertThat(received).hasSize(1);
  }

  @Test
  @Timeout(30)
  @DisplayName("a caller cannot override the headers the transport owns")
  void aCallerCannotOverrideTheHeadersTheTransportOwns() {
    responder.set(exchange -> ok(exchange, "{}"));

    // Content-Length and Host are JDK-restricted and would throw; the rest would let a caller
    // reframe
    // the body underneath the encoder that produced it.
    transport()
        .send(
            new EmbeddingTransportPort.Call(
                "POST",
                base + "/x",
                Map.of(
                    "Content-Length",
                    "999",
                    "Host",
                    "evil.invalid",
                    "Transfer-Encoding",
                    "chunked"),
                "{}".getBytes(StandardCharsets.UTF_8),
                5_000L));

    assertThat(received).hasSize(1);
    assertThat(header(received.get(0).headers(), "Host")).doesNotContain("evil.invalid");
    assertThat(new String(received.get(0).body(), StandardCharsets.UTF_8)).isEqualTo("{}");
  }

  @Test
  @Timeout(30)
  @DisplayName("a null or absent header value is skipped rather than throwing")
  void aNullOrAbsentHeaderValueIsSkipped() {
    responder.set(exchange -> ok(exchange, "{}"));
    final Map<String, String> withNull = new java.util.HashMap<>();
    withNull.put("X-Present", "yes");
    withNull.put("X-Absent", null);

    final EmbeddingTransportPort.Exchange result =
        transport()
            .send(
                new EmbeddingTransportPort.Call(
                    "POST", base + "/x", withNull, "{}".getBytes(StandardCharsets.UTF_8), 5_000L));

    assertThat(result.status()).isEqualTo(200);
    assertThat(header(received.get(0).headers(), "X-Present")).containsExactly("yes");
  }

  @Test
  @Timeout(30)
  @DisplayName("the transport never retries — one call in, one request out")
  void theTransportNeverRetries() {
    responder.set(
        exchange -> {
          final byte[] bytes = "{}".getBytes(StandardCharsets.UTF_8);
          exchange.sendResponseHeaders(503, bytes.length);
          exchange.getResponseBody().write(bytes);
          exchange.close();
        });

    transport().send(call(base + "/x", "{}"));

    // Retry belongs to EmbeddingPipeline, which owns the budget and the health state. A transport
    // retrying underneath it would double the real attempt count and corrupt both.
    assertThat(received).hasSize(1);
  }

  @Test
  @Timeout(30)
  @DisplayName("the adapter drives the real transport end to end")
  void theAdapterDrivesTheRealTransportEndToEnd() {
    final StringBuilder vector = new StringBuilder();
    for (int i = 0; i < 1536; i++) {
      vector.append(i > 0 ? "," : "").append("0.01");
    }
    responder.set(
        exchange ->
            ok(
                exchange,
                "{\"data\":[{\"object\":\"embedding\",\"index\":0,\"embedding\":["
                    + vector
                    + "]}],\"usage\":{\"prompt_tokens\":7}}"));

    final OpenAiEmbeddingProvider provider =
        new OpenAiEmbeddingProvider(
            transport(),
            () -> "sk-test",
            () -> java.time.Instant.parse("2026-08-08T00:00:00Z"),
            5_000L,
            base + "/v1/embeddings");

    final var response =
        provider.embed(
            io.reliabilityai.gateway.dataplane.embedding.api.EmbeddingRequest.of(
                io.reliabilityai.gateway.canonical.identity.TenantScope.of("acme", "core"),
                "text-default-1536",
                "hello",
                io.reliabilityai.gateway.dataplane.embedding.api.EmbeddingRequest.EmbeddingPurpose
                    .WRITE));

    assertThat(response.complete()).isTrue();
    assertThat(response.at(0).orElseThrow().dimension()).isEqualTo(1536);
    assertThat(response.usage().inputTokens()).isEqualTo(7L);
    assertThat(new String(received.get(0).body(), StandardCharsets.UTF_8))
        .contains("text-embedding-3-small");
  }

  @Test
  @Timeout(30)
  @DisplayName("an oversized response body is refused rather than buffered")
  void anOversizedResponseBodyIsRefusedRatherThanBuffered() {
    responder.set(
        exchange -> {
          final byte[] bytes = new byte[64 * 1024];
          exchange.sendResponseHeaders(200, bytes.length);
          exchange.getResponseBody().write(bytes);
          exchange.close();
        });
    final HttpEmbeddingTransport bounded =
        new HttpEmbeddingTransport(
            java.net.http.HttpClient.newBuilder()
                .followRedirects(java.net.http.HttpClient.Redirect.NEVER)
                .connectTimeout(Duration.ofSeconds(5))
                .build(),
            1024L);

    assertThatThrownBy(() -> bounded.send(call(base + "/x", "{}")))
        .isInstanceOf(EmbeddingTransportException.class)
        .satisfies(
            thrown ->
                assertThat(((EmbeddingTransportException) thrown).reason())
                    .isEqualTo(EmbeddingFailure.REJECTED));
  }

  @Test
  @Timeout(30)
  @DisplayName("the bound is on the DECOMPRESSED size, so a gzip bomb cannot slip under it")
  void theBoundIsOnTheDecompressedSize() {
    responder.set(
        exchange -> {
          // ~200 bytes on the wire, 256 KiB once inflated. A bound applied to the compressed size
          // would wave this through and let the heap absorb the difference.
          final ByteArrayOutputStream compressed = new ByteArrayOutputStream();
          try (GZIPOutputStream gzip = new GZIPOutputStream(compressed)) {
            gzip.write(new byte[256 * 1024]);
          }
          final byte[] bytes = compressed.toByteArray();
          assertThat(bytes.length).isLessThan(2048);
          exchange.getResponseHeaders().add("Content-Encoding", "gzip");
          exchange.sendResponseHeaders(200, bytes.length);
          exchange.getResponseBody().write(bytes);
          exchange.close();
        });
    final HttpEmbeddingTransport bounded =
        new HttpEmbeddingTransport(
            java.net.http.HttpClient.newBuilder()
                .followRedirects(java.net.http.HttpClient.Redirect.NEVER)
                .connectTimeout(Duration.ofSeconds(5))
                .build(),
            8192L);

    assertThatThrownBy(() -> bounded.send(call(base + "/x", "{}")))
        .isInstanceOf(EmbeddingTransportException.class);
  }

  @Test
  @DisplayName("a non-positive ceiling is refused at construction")
  void aNonPositiveCeilingIsRefusedAtConstruction() {
    assertThatThrownBy(
            () -> new HttpEmbeddingTransport(java.net.http.HttpClient.newHttpClient(), 0L))
        .isInstanceOf(IllegalArgumentException.class);
  }
}

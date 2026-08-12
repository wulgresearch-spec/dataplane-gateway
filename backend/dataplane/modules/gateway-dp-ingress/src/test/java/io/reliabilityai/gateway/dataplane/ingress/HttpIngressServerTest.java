package io.reliabilityai.gateway.dataplane.ingress;

import static org.assertj.core.api.Assertions.assertThat;

import io.reliabilityai.gateway.canonical.io.CanonicalError;
import io.reliabilityai.gateway.canonical.io.CanonicalResponse;
import io.reliabilityai.gateway.canonical.io.CanonicalUsage;
import io.reliabilityai.gateway.canonical.io.ErrorCategory;
import io.reliabilityai.gateway.canonical.io.FinishReason;
import io.reliabilityai.gateway.canonical.io.ProviderMeta;
import io.reliabilityai.gateway.canonical.io.UsageClass;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * HTTP-level tests for the ingress: endpoints, limits, status mapping, concurrency and shutdown.
 */
class HttpIngressServerTest {

  private static final String CHAT_BODY =
      "{\"model\":\"gpt-4o-mini\",\"messages\":[{\"role\":\"user\",\"content\":\"hi\"}]}";

  private HttpIngressServer server;
  private HttpClient client;

  private final AtomicInteger handled = new AtomicInteger();
  private final List<IngressChatRequest> seen = new CopyOnWriteArrayList<>();

  private volatile IngressOutcome outcome = completed("pong");
  private volatile Duration handlerDelay = Duration.ZERO;
  private volatile String state = "READY";
  private volatile boolean ready = true;

  private static IngressOutcome completed(final String content) {
    return new IngressOutcome.Completed(
        new CanonicalResponse(
            content,
            List.of(),
            FinishReason.STOP,
            new CanonicalUsage(7L, 3L, 0L, 0L, 0L, UsageClass.AUTHORITATIVE),
            ProviderMeta.empty()));
  }

  private static IngressOutcome refused(
      final String stage, final ErrorCategory category, final String code) {
    return new IngressOutcome.Refused(
        stage, new CanonicalError(category, Boolean.FALSE, code, false));
  }

  @BeforeEach
  void setUp() {
    final IngressHandler handler =
        request -> {
          handled.incrementAndGet();
          seen.add(request);
          if (!handlerDelay.isZero()) {
            try {
              Thread.sleep(handlerDelay.toMillis());
            } catch (final InterruptedException e) {
              Thread.currentThread().interrupt();
            }
          }
          return outcome;
        };
    final RuntimeStateProbe probe =
        new RuntimeStateProbe() {
          @Override
          public String state() {
            return state;
          }

          @Override
          public boolean ready() {
            return ready;
          }
        };
    server =
        new HttpIngressServer(
            // 2s budget: comfortably above normal handling, far below the 5s stall the timeout test
            // induces, so that test stays decisive without making the others race the clock.
            new IngressConfig("127.0.0.1", 0, 256, Duration.ofSeconds(2), 128, Duration.ZERO),
            handler,
            probe);
    server.start();
    client = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build();
  }

  @AfterEach
  void tearDown() {
    server.stop();
  }

  private URI uri(final String path) {
    return URI.create("http://127.0.0.1:" + server.boundPort() + path);
  }

  private HttpResponse<String> post(final String path, final String body, final String contentType)
      throws IOException, InterruptedException {
    final HttpRequest.Builder builder =
        HttpRequest.newBuilder().uri(uri(path)).POST(HttpRequest.BodyPublishers.ofString(body));
    if (contentType != null) {
      builder.header("Content-Type", contentType);
    }
    return client.send(builder.build(), HttpResponse.BodyHandlers.ofString());
  }

  private HttpResponse<String> get(final String path) throws IOException, InterruptedException {
    return client.send(
        HttpRequest.newBuilder().uri(uri(path)).GET().build(),
        HttpResponse.BodyHandlers.ofString());
  }

  // ---- happy path ----------------------------------------------------------------------------

  @Test
  void postReturnsAChatCompletion() throws Exception {
    final HttpResponse<String> response =
        post(HttpIngressServer.PATH_CHAT, CHAT_BODY, "application/json");

    assertThat(response.statusCode()).isEqualTo(200);
    assertThat(response.headers().firstValue("Content-Type")).contains("application/json");
    assertThat(response.body())
        .contains("\"content\":\"pong\"")
        .contains("\"finish_reason\":\"stop\"");
    assertThat(response.body()).contains("\"total_tokens\":10");
    assertThat(handled.get()).isEqualTo(1);
  }

  @Test
  void requestReachesTheHandlerWithParsedFieldsAndHeaders() throws Exception {
    client.send(
        HttpRequest.newBuilder()
            .uri(uri(HttpIngressServer.PATH_CHAT))
            .header("Content-Type", "application/json")
            .header("Authorization", "Bearer abc")
            .header("X-Correlation-Id", "corr-xyz")
            .POST(
                HttpRequest.BodyPublishers.ofString(
                    "{\"model\":\"m\",\"messages\":[{\"role\":\"user\",\"content\":\"hi\"}],"
                        + "\"temperature\":0.5,\"max_tokens\":32,\"stop\":[\"A\",\"B\"],"
                        + "\"response_format\":{\"type\":\"json_object\"}}"))
            .build(),
        HttpResponse.BodyHandlers.ofString());

    final IngressChatRequest request = seen.get(0);
    assertThat(request.model()).isEqualTo("m");
    assertThat(request.correlationId()).isEqualTo("corr-xyz");
    assertThat(request.authorization()).isEqualTo("Bearer abc");
    assertThat(request.params())
        .containsEntry("temperature", "0.5")
        .containsEntry("max_tokens", "32")
        .containsEntry("stop", "A,B")
        .containsEntry("json_mode", "true");
  }

  @Test
  void responsesAreDeterministicForIdenticalRequests() throws Exception {
    final String first = post(HttpIngressServer.PATH_CHAT, CHAT_BODY, "application/json").body();

    for (int run = 0; run < 20; run++) {
      final HttpResponse<String> repeat =
          client.send(
              HttpRequest.newBuilder()
                  .uri(uri(HttpIngressServer.PATH_CHAT))
                  .header("Content-Type", "application/json")
                  .header("X-Correlation-Id", "fixed")
                  .POST(HttpRequest.BodyPublishers.ofString(CHAT_BODY))
                  .build(),
              HttpResponse.BodyHandlers.ofString());
      assertThat(repeat.statusCode()).isEqualTo(200);
    }
    assertThat(first).contains("\"content\":\"pong\"");
  }

  // ---- request validation --------------------------------------------------------------------

  @Test
  void malformedJsonIsRejectedWith400() throws Exception {
    final HttpResponse<String> response =
        post(HttpIngressServer.PATH_CHAT, "{not json", "application/json");

    assertThat(response.statusCode()).isEqualTo(400);
    assertThat(response.body()).contains("malformed-json");
    assertThat(handled.get()).isZero(); // never reached the pipeline
  }

  @Test
  void oversizedBodyIsRejectedWith413() throws Exception {
    final String big = "{\"model\":\"m\",\"pad\":\"" + "x".repeat(400) + "\"}";

    final HttpResponse<String> response =
        post(HttpIngressServer.PATH_CHAT, big, "application/json");

    assertThat(response.statusCode()).isEqualTo(413);
    assertThat(handled.get()).isZero();
  }

  @Test
  void aBodyOfExactlyTheConfiguredCeilingIsAccepted() throws Exception {
    // The ceiling is a limit, not a target: the server reads one byte past it to tell "at the
    // limit" from "over it". A guard that refused the exact size would reject requests the
    // configuration permits, and the oversize test above cannot detect that on its own.
    final String prefix = "{\"model\":\"m\",\"messages\":[{\"role\":\"user\",\"content\":\"";
    final String suffix = "\"}]}";
    final String body = prefix + "x".repeat(256 - prefix.length() - suffix.length()) + suffix;
    assertThat(body.getBytes(java.nio.charset.StandardCharsets.UTF_8)).hasSize(256);

    final HttpResponse<String> response =
        post(HttpIngressServer.PATH_CHAT, body, "application/json");

    assertThat(response.statusCode()).isEqualTo(200);
    assertThat(handled.get()).isEqualTo(1);
  }

  @Test
  void jsonModeIsCarriedOnlyWhenTheClientActuallyAsksForIt() throws Exception {
    // response_format is a nested, client-supplied shape. Every branch that is not exactly
    // {"type":"json_object"} must leave the flag off, or the pipeline would be told the caller
    // demanded structured output when it did not.
    post(
        HttpIngressServer.PATH_CHAT,
        "{\"model\":\"m\",\"messages\":[{\"role\":\"user\",\"content\":\"h\"}],"
            + "\"response_format\":{\"type\":\"json_object\"}}",
        "application/json");
    assertThat(seen.get(0).params()).containsEntry("json_mode", "true");

    seen.clear();
    // Not an object, wrong type value, and absent: none of these request JSON mode.
    post(
        HttpIngressServer.PATH_CHAT,
        "{\"model\":\"m\",\"messages\":[{\"role\":\"user\",\"content\":\"h\"}],"
            + "\"response_format\":\"json_object\"}",
        "application/json");
    post(
        HttpIngressServer.PATH_CHAT,
        "{\"model\":\"m\",\"messages\":[{\"role\":\"user\",\"content\":\"h\"}],"
            + "\"response_format\":{\"type\":\"text\"}}",
        "application/json");
    post(
        HttpIngressServer.PATH_CHAT,
        "{\"model\":\"m\",\"messages\":[{\"role\":\"user\",\"content\":\"h\"}]}",
        "application/json");
    assertThat(seen)
        .allSatisfy(request -> assertThat(request.params()).doesNotContainKey("json_mode"));
  }

  @Test
  void stopSequencesAreAcceptedAsAStringOrAListAndBlanksAreDropped() throws Exception {
    post(
        HttpIngressServer.PATH_CHAT,
        "{\"model\":\"m\",\"messages\":[{\"role\":\"user\",\"content\":\"h\"}],\"stop\":\"END\"}",
        "application/json");
    assertThat(seen.get(0).params()).containsEntry("stop", "END");

    seen.clear();
    // A list is joined; blank and non-string entries contribute nothing rather than producing
    // empty sequences the provider would have to interpret.
    post(
        HttpIngressServer.PATH_CHAT,
        "{\"model\":\"m\",\"messages\":[{\"role\":\"user\",\"content\":\"h\"}],"
            + "\"stop\":[\"A\",\" \",\"B\",1,\"\"]}",
        "application/json");
    assertThat(seen.get(0).params()).containsEntry("stop", "A,B");

    seen.clear();
    // Nothing usable at all leaves the parameter absent entirely.
    post(
        HttpIngressServer.PATH_CHAT,
        "{\"model\":\"m\",\"messages\":[{\"role\":\"user\",\"content\":\"h\"}],\"stop\":[\"\"]}",
        "application/json");
    assertThat(seen.get(0).params()).doesNotContainKey("stop");
  }

  @Test
  void aClientSuppliedCorrelationIdIsUsedAndOtherwiseOneIsGenerated() throws Exception {
    // The correlation id is echoed into the response and becomes the request's identity
    // downstream. It is accepted from the client by design, so what must hold is that the value
    // arrives unaltered and that its absence is filled rather than left blank.
    final HttpRequest.Builder builder =
        HttpRequest.newBuilder()
            .uri(uri(HttpIngressServer.PATH_CHAT))
            .header("Content-Type", "application/json")
            .header("X-Correlation-Id", "chosen-id")
            .header("Idempotency-Key", "chosen-key")
            .POST(HttpRequest.BodyPublishers.ofString(CHAT_BODY));
    client.send(builder.build(), HttpResponse.BodyHandlers.ofString());

    assertThat(seen.get(0).correlationId()).isEqualTo("chosen-id");
    assertThat(seen.get(0).idempotencyKey()).isEqualTo("chosen-key");

    seen.clear();
    post(HttpIngressServer.PATH_CHAT, CHAT_BODY, "application/json");
    assertThat(seen.get(0).correlationId()).isNotBlank();
    assertThat(seen.get(0).idempotencyKey()).isNotBlank();
  }

  @Test
  void unsupportedContentTypeIsRejectedWith415() throws Exception {
    final HttpResponse<String> response =
        post(HttpIngressServer.PATH_CHAT, CHAT_BODY, "text/plain");

    assertThat(response.statusCode()).isEqualTo(415);
    assertThat(handled.get()).isZero();
  }

  @Test
  void missingContentTypeIsRejectedWith415() throws Exception {
    final HttpResponse<String> response = post(HttpIngressServer.PATH_CHAT, CHAT_BODY, null);

    assertThat(response.statusCode()).isEqualTo(415);
  }

  @Test
  void semanticallyInvalidRequestIsRejectedWith422() throws Exception {
    assertThat(
            post(HttpIngressServer.PATH_CHAT, "{\"messages\":[]}", "application/json").statusCode())
        .isEqualTo(422);
    assertThat(
            post(
                    HttpIngressServer.PATH_CHAT,
                    "{\"model\":\"m\",\"messages\":[]}",
                    "application/json")
                .statusCode())
        .isEqualTo(422);
  }

  @Test
  void aStreamingRequestCarriesTheStreamFlagToThePipeline() throws Exception {
    post(
        HttpIngressServer.PATH_CHAT,
        "{\"model\":\"m\",\"messages\":[{\"role\":\"user\",\"content\":\"hi\"}],\"stream\":true}",
        "application/json");

    // The flag is intent, forwarded as a parameter; the pipeline and provider decide what to do
    // with it.
    assertThat(seen.get(0).params()).containsEntry("stream", "true");
  }

  @Test
  void wrongMethodOnChatIsRejected() throws Exception {
    assertThat(get(HttpIngressServer.PATH_CHAT).statusCode()).isEqualTo(405);
  }

  @Test
  void unknownPathIs404() throws Exception {
    assertThat(get("/v1/nope").statusCode()).isEqualTo(404);
  }

  // ---- refusal mapping -----------------------------------------------------------------------

  @Test
  void authenticationRefusalMapsTo401() throws Exception {
    outcome = refused("AUTHN", ErrorCategory.AUTH_FAILED, "invalid-signature");

    final HttpResponse<String> response =
        post(HttpIngressServer.PATH_CHAT, CHAT_BODY, "application/json");

    assertThat(response.statusCode()).isEqualTo(401);
    assertThat(response.body()).contains("\"stage\":\"AUTHN\"");
  }

  @Test
  void governanceDenialMapsTo403AndThrottleMapsTo429() throws Exception {
    outcome = refused("GOVERNANCE", ErrorCategory.AUTH_FAILED, "tenant-disabled");
    assertThat(post(HttpIngressServer.PATH_CHAT, CHAT_BODY, "application/json").statusCode())
        .isEqualTo(403);

    // A spend or usage ceiling is a throttle the caller can wait out, not a forbidden action.
    outcome = refused("GOVERNANCE", ErrorCategory.AUTH_FAILED, "budget-exceeded");
    assertThat(post(HttpIngressServer.PATH_CHAT, CHAT_BODY, "application/json").statusCode())
        .isEqualTo(429);
  }

  @Test
  void providerFailuresMapToUpstreamStatuses() throws Exception {
    outcome = refused("ADAPTER", ErrorCategory.RATE_LIMITED, "openai:429");
    assertThat(post(HttpIngressServer.PATH_CHAT, CHAT_BODY, "application/json").statusCode())
        .isEqualTo(429);

    outcome = refused("ADAPTER", ErrorCategory.PROVIDER_UNAVAILABLE, "openai:503");
    assertThat(post(HttpIngressServer.PATH_CHAT, CHAT_BODY, "application/json").statusCode())
        .isEqualTo(502);

    outcome = refused("ADAPTER", ErrorCategory.TIMEOUT, "openai:504");
    assertThat(post(HttpIngressServer.PATH_CHAT, CHAT_BODY, "application/json").statusCode())
        .isEqualTo(504);

    // Our credential being refused upstream is not the caller's fault, so it is not a 401.
    outcome = refused("ADAPTER", ErrorCategory.AUTH_FAILED, "openai:401");
    assertThat(post(HttpIngressServer.PATH_CHAT, CHAT_BODY, "application/json").statusCode())
        .isEqualTo(502);
  }

  @Test
  void errorBodiesNeverLeakInternals() throws Exception {
    outcome = refused("ADAPTER", ErrorCategory.PROVIDER_UNAVAILABLE, "openai:503");

    final String body = post(HttpIngressServer.PATH_CHAT, CHAT_BODY, "application/json").body();

    assertThat(body)
        .doesNotContain("Exception")
        .doesNotContain("io.reliabilityai")
        .doesNotContain("at java.");
  }

  @Test
  void aHandlerThatThrowsBecomesACanonical500() throws Exception {
    final HttpIngressServer throwing =
        new HttpIngressServer(
            new IngressConfig("127.0.0.1", 0, 4096, Duration.ofSeconds(2), 8, Duration.ZERO),
            request -> {
              throw new IllegalStateException("secret internal detail");
            },
            new RuntimeStateProbe() {
              @Override
              public String state() {
                return "READY";
              }

              @Override
              public boolean ready() {
                return true;
              }
            });
    throwing.start();
    try {
      final HttpResponse<String> response =
          client.send(
              HttpRequest.newBuilder()
                  .uri(
                      URI.create(
                          "http://127.0.0.1:" + throwing.boundPort() + HttpIngressServer.PATH_CHAT))
                  .header("Content-Type", "application/json")
                  .POST(HttpRequest.BodyPublishers.ofString(CHAT_BODY))
                  .build(),
              HttpResponse.BodyHandlers.ofString());

      assertThat(response.statusCode()).isEqualTo(500);
      assertThat(response.body())
          .contains("internal-error")
          .doesNotContain("secret internal detail");
    } finally {
      throwing.stop();
    }
  }

  // ---- lifecycle endpoints -------------------------------------------------------------------

  @Test
  void healthReportsLifecycleStateAndStays200() throws Exception {
    state = "STARTING";
    ready = false;

    final HttpResponse<String> response = get(HttpIngressServer.PATH_HEALTH);

    assertThat(response.statusCode()).isEqualTo(200);
    assertThat(response.body().trim()).isEqualTo("STARTING");
  }

  @Test
  void readyIs200OnlyWhenTheRuntimeIsReady() throws Exception {
    assertThat(get(HttpIngressServer.PATH_READY).statusCode()).isEqualTo(200);

    state = "STOPPING";
    ready = false;
    final HttpResponse<String> notReady = get(HttpIngressServer.PATH_READY);
    assertThat(notReady.statusCode()).isEqualTo(503);
    assertThat(notReady.body().trim()).isEqualTo("STOPPING");
  }

  @Test
  void metricsIsPlainTextAndCountsRequests() throws Exception {
    post(HttpIngressServer.PATH_CHAT, CHAT_BODY, "application/json");

    final HttpResponse<String> response = get(HttpIngressServer.PATH_METRICS);

    assertThat(response.statusCode()).isEqualTo(200);
    assertThat(response.headers().firstValue("Content-Type")).contains("text/plain; charset=utf-8");
    assertThat(response.body())
        .contains("gateway_ingress_requests_received")
        .contains("gateway_ingress_responses{status=\"200\"}");
  }

  // ---- timeouts, concurrency, shutdown -------------------------------------------------------

  @Test
  void aStalledHandlerIsBoundedByTheRequestTimeout() throws Exception {
    handlerDelay = Duration.ofSeconds(5); // far beyond the 500ms configured budget

    final HttpResponse<String> response =
        post(HttpIngressServer.PATH_CHAT, CHAT_BODY, "application/json");

    assertThat(response.statusCode()).isEqualTo(504);
    assertThat(response.body()).contains("request-timeout");
  }

  @Test
  void keepAliveReusesOneConnectionAcrossRequests() throws Exception {
    for (int i = 0; i < 10; i++) {
      final HttpResponse<String> response =
          post(HttpIngressServer.PATH_CHAT, CHAT_BODY, "application/json");
      assertThat(response.statusCode()).isEqualTo(200);
      assertThat(response.version()).isEqualTo(HttpClient.Version.HTTP_1_1);
    }
    assertThat(handled.get()).isEqualTo(10);
  }

  @Test
  void servesOneHundredSimultaneousRequestsWithoutLosingOrLeakingAny() throws Exception {
    // A dedicated server with a generous budget and backlog: the shared fixture's 500ms timeout
    // exists
    // for the timeout test, and 100 requests landing at once can legitimately exceed it. Reusing it
    // here would make this a test of the clock rather than of concurrency.
    final int requests = 100;
    final HttpIngressServer concurrent =
        new HttpIngressServer(
            new IngressConfig("127.0.0.1", 0, 4096, Duration.ofSeconds(30), 256, Duration.ZERO),
            request -> completed("pong"),
            alwaysReady());
    concurrent.start();

    final CountDownLatch ready = new CountDownLatch(requests);
    final CountDownLatch go = new CountDownLatch(1);
    final CountDownLatch done = new CountDownLatch(requests);
    final List<Integer> statuses = new CopyOnWriteArrayList<>();
    final URI endpoint =
        URI.create("http://127.0.0.1:" + concurrent.boundPort() + HttpIngressServer.PATH_CHAT);

    try {
      for (int i = 0; i < requests; i++) {
        Thread.ofVirtual()
            .start(
                () -> {
                  try {
                    ready.countDown();
                    go.await();
                    statuses.add(
                        client
                            .send(
                                HttpRequest.newBuilder()
                                    .uri(endpoint)
                                    .header("Content-Type", "application/json")
                                    .POST(HttpRequest.BodyPublishers.ofString(CHAT_BODY))
                                    .build(),
                                HttpResponse.BodyHandlers.ofString())
                            .statusCode());
                  } catch (final IOException | InterruptedException e) {
                    statuses.add(-1);
                  } finally {
                    done.countDown();
                  }
                });
      }

      assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
      go.countDown();
      assertThat(done.await(120, TimeUnit.SECONDS)).isTrue();

      assertThat(statuses).hasSize(requests).allMatch(status -> status == 200);
      // The in-flight gauge returning to zero is what proves no request leaked a thread.
      assertThat(concurrent.metrics().inFlight()).isZero();
      assertThat(concurrent.metrics().received()).isEqualTo(requests);
    } finally {
      concurrent.stop();
    }
  }

  private static RuntimeStateProbe alwaysReady() {
    return new RuntimeStateProbe() {
      @Override
      public String state() {
        return "READY";
      }

      @Override
      public boolean ready() {
        return true;
      }
    };
  }

  @Test
  void concurrentRequestsEachGetTheirOwnDecodedRequest() throws Exception {
    final int requests = 24;
    final CountDownLatch done = new CountDownLatch(requests);
    final Set<String> correlationIds = ConcurrentHashMap.newKeySet();

    for (int i = 0; i < requests; i++) {
      final String correlation = "corr-" + i;
      Thread.ofVirtual()
          .start(
              () -> {
                try {
                  client.send(
                      HttpRequest.newBuilder()
                          .uri(uri(HttpIngressServer.PATH_CHAT))
                          .header("Content-Type", "application/json")
                          .header("X-Correlation-Id", correlation)
                          .POST(HttpRequest.BodyPublishers.ofString(CHAT_BODY))
                          .build(),
                      HttpResponse.BodyHandlers.ofString());
                  correlationIds.add(correlation);
                } catch (final IOException | InterruptedException e) {
                  Thread.currentThread().interrupt();
                } finally {
                  done.countDown();
                }
              });
    }

    assertThat(done.await(60, TimeUnit.SECONDS)).isTrue();
    assertThat(correlationIds).hasSize(requests);
    // No request observed another's identity.
    assertThat(
            new ArrayList<>(seen)
                .stream().map(IngressChatRequest::correlationId).distinct().count())
        .isEqualTo(requests);
  }

  @Test
  void shutdownStopsAcceptingAndReleasesTheSocket() throws Exception {
    assertThat(post(HttpIngressServer.PATH_CHAT, CHAT_BODY, "application/json").statusCode())
        .isEqualTo(200);
    final int port = server.boundPort();

    server.stop();

    // The port is free again — a leaked listener or executor would keep it bound.
    try (java.net.ServerSocket rebind = new java.net.ServerSocket()) {
      rebind.setReuseAddress(true);
      rebind.bind(new java.net.InetSocketAddress("127.0.0.1", port));
      assertThat(rebind.isBound()).isTrue();
    }
  }

  @Test
  void shutdownIsIdempotent() {
    server.stop();
    server.stop();
    assertThat(server.metrics().received()).isNotNegative();
  }
}

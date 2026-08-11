package io.reliabilityai.gateway.dataplane.ingress;

import static org.assertj.core.api.Assertions.assertThat;

import io.reliabilityai.gateway.canonical.io.CanonicalToolCall;
import io.reliabilityai.gateway.canonical.io.CanonicalUsage;
import io.reliabilityai.gateway.canonical.io.FinishReason;
import io.reliabilityai.gateway.canonical.io.UsageClass;
import io.reliabilityai.gateway.canonical.stream.StreamChunk;
import io.reliabilityai.gateway.canonical.stream.StreamState;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/** Server-Sent Events over chunked transfer: incremental delivery, flushing, and disconnect. */
class SseStreamingTest {

  private static final String STREAM_BODY =
      "{\"model\":\"m\",\"messages\":[{\"role\":\"user\",\"content\":\"hi\"}],\"stream\":true}";

  private HttpIngressServer server;
  private final HttpClient client =
      HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build();

  @AfterEach
  void tearDown() {
    if (server != null) {
      server.stop();
    }
  }

  /** A stream driven from a queue, so a test controls exactly when each chunk becomes available. */
  private static final class ScriptedStream implements IngressStream {
    private final LinkedBlockingQueue<StreamChunk> chunks = new LinkedBlockingQueue<>();
    private final AtomicBoolean finished = new AtomicBoolean();
    private final AtomicBoolean cancelled = new AtomicBoolean();
    private final AtomicInteger pulls = new AtomicInteger();

    ScriptedStream(final StreamChunk... scripted) {
      chunks.addAll(List.of(scripted));
    }

    void push(final StreamChunk chunk) {
      chunks.add(chunk);
    }

    @Override
    public StreamChunk next() {
      pulls.incrementAndGet();
      try {
        final StreamChunk chunk = chunks.poll(5, TimeUnit.SECONDS);
        final StreamChunk resolved =
            chunk == null
                ? new StreamChunk.Terminal(StreamState.TERMINAL_FAILED, FinishReason.ERROR)
                : chunk;
        if (resolved instanceof StreamChunk.Terminal) {
          finished.set(true);
        }
        return resolved;
      } catch (final InterruptedException e) {
        Thread.currentThread().interrupt();
        finished.set(true);
        return new StreamChunk.Terminal(StreamState.TERMINAL_FAILED, FinishReason.ERROR);
      }
    }

    @Override
    public boolean finished() {
      return finished.get();
    }

    @Override
    public void cancel(final String reason) {
      cancelled.set(true);
      finished.set(true);
    }

    boolean cancelled() {
      return cancelled.get();
    }

    int pulls() {
      return pulls.get();
    }
  }

  private void startWith(final IngressStream stream) {
    server =
        new HttpIngressServer(
            new IngressConfig("127.0.0.1", 0, 4096, Duration.ofSeconds(5), 64, Duration.ZERO),
            request -> new IngressOutcome.Streamed(stream),
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
    server.start();
  }

  private HttpRequest streamRequest() {
    return HttpRequest.newBuilder()
        .uri(URI.create("http://127.0.0.1:" + server.boundPort() + HttpIngressServer.PATH_CHAT))
        .header("Content-Type", "application/json")
        .header("X-Correlation-Id", "corr-1")
        .POST(HttpRequest.BodyPublishers.ofString(STREAM_BODY))
        .build();
  }

  private static StreamChunk delta(final String text) {
    return new StreamChunk.Delta(text);
  }

  private static StreamChunk terminal() {
    return new StreamChunk.Terminal(StreamState.TERMINAL_COMPLETE, FinishReason.STOP);
  }

  // ---- delivery
  // ----------------------------------------------------------------------------------

  @Test
  void streamsEventsAsChunkedServerSentEvents() throws Exception {
    startWith(new ScriptedStream(delta("Hello"), delta(" world"), terminal()));

    final HttpResponse<String> response =
        client.send(streamRequest(), HttpResponse.BodyHandlers.ofString());

    assertThat(response.statusCode()).isEqualTo(200);
    assertThat(response.headers().firstValue("Content-Type"))
        .contains("text/event-stream; charset=utf-8");
    // Chunked transfer: the length is not known up front.
    assertThat(response.headers().firstValue("Content-Length")).isEmpty();
    assertThat(response.body())
        .contains("\"content\":\"Hello\"")
        .contains("\"content\":\" world\"")
        .contains("\"finish_reason\":\"stop\"")
        .endsWith("data: [DONE]\n\n");
  }

  @Test
  void everyEventIsWellFormedSse() throws Exception {
    startWith(new ScriptedStream(delta("a"), delta("b"), terminal()));

    final String body = client.send(streamRequest(), HttpResponse.BodyHandlers.ofString()).body();

    for (final String event : body.split("\n\n")) {
      if (!event.isBlank()) {
        assertThat(event).startsWith("data: ");
      }
    }
  }

  @Test
  void theClientReceivesTokensIncrementallyBeforeTheStreamEnds() throws Exception {
    final ScriptedStream stream = new ScriptedStream(delta("first"));
    startWith(stream);

    final HttpResponse<java.io.InputStream> response =
        client.send(streamRequest(), HttpResponse.BodyHandlers.ofInputStream());
    final BufferedReader reader =
        new BufferedReader(new InputStreamReader(response.body(), StandardCharsets.UTF_8));

    // The first event arrives while the server still has no idea what comes next — proof that the
    // response is flushed per event rather than assembled and sent at the end.
    final String firstLine = reader.readLine();
    assertThat(firstLine).contains("\"content\":\"first\"");

    stream.push(delta("second"));
    reader.readLine(); // blank separator
    assertThat(reader.readLine()).contains("\"content\":\"second\"");

    stream.push(terminal());
    reader.close();
  }

  @Test
  void backpressureIsPullBasedSoTheServerNeverRunsAhead() throws Exception {
    final ScriptedStream stream = new ScriptedStream(delta("one"));
    startWith(stream);

    final HttpResponse<java.io.InputStream> response =
        client.send(streamRequest(), HttpResponse.BodyHandlers.ofInputStream());
    final BufferedReader reader =
        new BufferedReader(new InputStreamReader(response.body(), StandardCharsets.UTF_8));
    reader.readLine();

    // One chunk written, and the server is blocked pulling the next: it has not drained ahead.
    Thread.sleep(200);
    assertThat(stream.pulls()).isLessThanOrEqualTo(2);

    stream.push(terminal());
    reader.close();
  }

  @Test
  void toolCallsAndUsageAreStreamed() throws Exception {
    startWith(
        new ScriptedStream(
            new StreamChunk.ToolCallDelta(new CanonicalToolCall("lookup", "{}", "call-1")),
            new StreamChunk.Usage(new CanonicalUsage(7L, 2L, 0L, 0L, 0L, UsageClass.AUTHORITATIVE)),
            terminal()));

    final String body = client.send(streamRequest(), HttpResponse.BodyHandlers.ofString()).body();

    assertThat(body)
        .contains("\"tool_calls\"")
        .contains("\"name\":\"lookup\"")
        .contains("\"prompt_tokens\":7")
        .contains("\"total_tokens\":9");
  }

  // ---- failures and cancellation
  // -------------------------------------------------------------------

  @Test
  void aFailedStreamReportsAnErrorFinishReasonRatherThanStop() throws Exception {
    startWith(
        new ScriptedStream(
            delta("partial"),
            new StreamChunk.Terminal(StreamState.TERMINAL_FAILED, FinishReason.STOP)));

    final String body = client.send(streamRequest(), HttpResponse.BodyHandlers.ofString()).body();

    // Telling the client "stop" for a truncated answer would present it as complete.
    assertThat(body)
        .contains("\"finish_reason\":\"error\"")
        .doesNotContain("\"finish_reason\":\"stop\"");
  }

  @Test
  void aClientDisconnectCancelsTheStream() throws Exception {
    final ScriptedStream stream = new ScriptedStream(delta("one"));
    startWith(stream);

    final HttpResponse<java.io.InputStream> response =
        client.send(streamRequest(), HttpResponse.BodyHandlers.ofInputStream());
    response.body().read();
    response.body().close(); // the client walks away mid-stream

    // Keep producing so the server's next write hits the closed socket.
    for (int i = 0; i < 200 && !stream.cancelled(); i++) {
      stream.push(delta("more-" + i));
      Thread.sleep(20);
    }

    // Cancellation must reach the pipeline, which unwinds it to the provider connection.
    assertThat(stream.cancelled()).isTrue();
  }

  @Test
  void aStreamThatStallsTerminatesRatherThanHangingForever() throws Exception {
    // The scripted stream yields a failed terminal when nothing arrives within its own budget.
    startWith(new ScriptedStream());

    final HttpResponse<String> response =
        client.send(streamRequest(), HttpResponse.BodyHandlers.ofString());

    assertThat(response.statusCode()).isEqualTo(200);
    assertThat(response.body())
        .contains("\"finish_reason\":\"error\"")
        .endsWith("data: [DONE]\n\n");
  }

  @Test
  void streamingIsCountedInMetricsAndLeavesNoRequestInFlight() throws Exception {
    startWith(new ScriptedStream(delta("x"), terminal()));

    client.send(streamRequest(), HttpResponse.BodyHandlers.ofString());

    assertThat(server.metrics().received()).isEqualTo(1);
    assertThat(server.metrics().inFlight()).isZero();
    assertThat(server.metrics().render()).contains("gateway_ingress_responses{status=\"200\"}");
  }

  @Test
  void oneHundredConcurrentStreamsEachCompleteIndependently() throws Exception {
    final int streams = 100;
    server =
        new HttpIngressServer(
            new IngressConfig("127.0.0.1", 0, 4096, Duration.ofSeconds(30), 256, Duration.ZERO),
            request ->
                new IngressOutcome.Streamed(
                    new ScriptedStream(delta(request.correlationId()), terminal())),
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
    server.start();

    final CountDownLatch go = new CountDownLatch(1);
    final CountDownLatch done = new CountDownLatch(streams);
    final List<String> failures = new CopyOnWriteArrayList<>();
    final URI endpoint =
        URI.create("http://127.0.0.1:" + server.boundPort() + HttpIngressServer.PATH_CHAT);

    for (int i = 0; i < streams; i++) {
      final String correlation = "corr-" + i;
      Thread.ofVirtual()
          .start(
              () -> {
                try {
                  go.await();
                  final HttpResponse<String> response =
                      client.send(
                          HttpRequest.newBuilder()
                              .uri(endpoint)
                              .header("Content-Type", "application/json")
                              .header("X-Correlation-Id", correlation)
                              .POST(HttpRequest.BodyPublishers.ofString(STREAM_BODY))
                              .build(),
                          HttpResponse.BodyHandlers.ofString());
                  if (!response.body().contains("\"content\":\"" + correlation + "\"")) {
                    failures.add("crossed streams");
                  }
                  if (!response.body().endsWith("data: [DONE]\n\n")) {
                    failures.add("missing terminator");
                  }
                } catch (final IOException | InterruptedException e) {
                  failures.add(e.getClass().getSimpleName());
                } finally {
                  done.countDown();
                }
              });
    }

    go.countDown();
    assertThat(done.await(120, TimeUnit.SECONDS)).isTrue();
    assertThat(failures).isEmpty();
    assertThat(server.metrics().inFlight()).isZero(); // no leaked request threads
  }

  @Test
  void repeatedIdenticalStreamsProduceIdenticalBodies() throws Exception {
    final List<String> bodies = new ArrayList<>();
    for (int run = 0; run < 10; run++) {
      if (server != null) {
        server.stop();
      }
      startWith(new ScriptedStream(delta("a"), delta("b"), terminal()));
      bodies.add(client.send(streamRequest(), HttpResponse.BodyHandlers.ofString()).body());
    }

    assertThat(bodies).containsOnly(bodies.get(0));
  }
}

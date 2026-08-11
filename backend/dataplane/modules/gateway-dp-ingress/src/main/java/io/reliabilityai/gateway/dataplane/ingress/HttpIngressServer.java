package io.reliabilityai.gateway.dataplane.ingress;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.reliabilityai.gateway.canonical.io.CanonicalResponse;
import io.reliabilityai.gateway.canonical.io.CanonicalToolCall;
import io.reliabilityai.gateway.common.Preconditions;
import io.reliabilityai.gateway.dataplane.ingress.IngressChatRequest.IngressMessage;
import io.reliabilityai.gateway.dataplane.ingress.internal.IngressJson;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicLong;

/**
 * The gateway's HTTP front door, built on the JDK's {@link HttpServer} (Doc 30).
 *
 * <p><b>It decides nothing.</b> The server validates transport concerns — method, path, content
 * type, body size, JSON shape — and then hands every surviving request to {@link IngressHandler}.
 * There is no code path from here to a provider that skips the pipeline, which is what makes the
 * non-bypass guarantee hold at the edge as well as inside (AD-018).
 *
 * <p><b>Nothing internal escapes.</b> Every response body is a canonical, content-free error
 * object. Exceptions are caught at the handler boundary and collapsed into a status plus an opaque
 * code — a stack trace or provider message reaching a caller would leak internals and, worse, hand
 * an attacker a map of the system.
 *
 * <p><b>Threading.</b> One virtual thread per request, from an executor this class owns and shuts
 * down. Each request is additionally bounded by {@code requestTimeout}, so a stalled downstream
 * cannot pin a connection forever, and the in-flight gauge makes a leak visible rather than merely
 * suspected.
 */
public final class HttpIngressServer {

  /** The chat-completions endpoint. */
  public static final String PATH_CHAT = "/v1/chat/completions";

  /** The liveness endpoint. */
  public static final String PATH_HEALTH = "/health";

  /** The readiness endpoint. */
  public static final String PATH_READY = "/ready";

  /** The metrics endpoint. */
  public static final String PATH_METRICS = "/metrics";

  private static final String JSON = "application/json";
  private static final String TEXT = "text/plain; charset=utf-8";

  private final IngressConfig config;
  private final IngressHandler handler;
  private final RuntimeStateProbe probe;
  private final IngressMetrics metrics = new IngressMetrics();
  private final AtomicLong requestCounter = new AtomicLong();

  private HttpServer server;
  private ExecutorService executor;
  private volatile boolean accepting;

  /**
   * Creates the server. Nothing is bound until {@link #start()}.
   *
   * @param config the ingress wiring
   * @param handler the pipeline seam
   * @param probe the runtime lifecycle probe
   */
  public HttpIngressServer(
      final IngressConfig config, final IngressHandler handler, final RuntimeStateProbe probe) {
    this.config = Preconditions.requireNonNull(config, "config");
    this.handler = Preconditions.requireNonNull(handler, "handler");
    this.probe = Preconditions.requireNonNull(probe, "probe");
  }

  /**
   * Binds the socket and begins accepting.
   *
   * @throws IllegalStateException if the socket cannot be bound
   */
  public void start() {
    if (server != null) {
      throw new IllegalStateException("ingress already started");
    }
    try {
      server =
          HttpServer.create(new InetSocketAddress(config.host(), config.port()), config.backlog());
    } catch (final IOException bindFailure) {
      throw new IllegalStateException("ingress cannot bind " + config.host(), bindFailure);
    }
    executor = Executors.newVirtualThreadPerTaskExecutor();
    server.setExecutor(executor);
    server.createContext(PATH_CHAT, exchange -> dispatch(exchange, this::handleChat));
    server.createContext(PATH_HEALTH, exchange -> dispatch(exchange, this::handleHealth));
    server.createContext(PATH_READY, exchange -> dispatch(exchange, this::handleReady));
    server.createContext(PATH_METRICS, exchange -> dispatch(exchange, this::handleMetrics));
    server.createContext("/", exchange -> dispatch(exchange, this::handleUnknown));
    accepting = true;
    server.start();
  }

  /**
   * Stops accepting new requests, lets in-flight ones finish within the grace window, then closes
   * the socket and the executor.
   *
   * <p>The order matters: refusing new work first means the drain sees a bounded backlog rather
   * than a moving target, and shutting the executor down last is what guarantees no virtual thread
   * outlives the server.
   */
  public void stop() {
    accepting = false;
    if (server != null) {
      server.stop((int) Math.max(0, config.shutdownGrace().toSeconds()));
      server = null;
    }
    if (executor != null) {
      executor.shutdown();
      try {
        if (!executor.awaitTermination(config.shutdownGrace().toSeconds() + 1, TimeUnit.SECONDS)) {
          executor.shutdownNow();
        }
      } catch (final InterruptedException e) {
        executor.shutdownNow();
        Thread.currentThread().interrupt();
      }
      executor = null;
    }
  }

  /**
   * The bound port, resolved after {@link #start()} — the caller may have configured port {@code
   * 0}.
   *
   * @return the bound port
   * @throws IllegalStateException if the server is not started
   */
  public int boundPort() {
    if (server == null) {
      throw new IllegalStateException("ingress not started");
    }
    return server.getAddress().getPort();
  }

  /**
   * The request counters.
   *
   * @return the metrics
   */
  public IngressMetrics metrics() {
    return metrics;
  }

  // ---- dispatch ------------------------------------------------------------------------------

  /** One response: either a fully-encoded body, or a live stream to write as it arrives. */
  private sealed interface Reply permits Reply.Buffered, Reply.Streaming {

    /** The HTTP status, needed for metrics regardless of body shape. */
    int status();

    /** A complete, already-encoded body. */
    record Buffered(int status, String contentType, String body) implements Reply {}

    /** A live stream; the body is written incrementally by the dispatcher. */
    record Streaming(IngressStream stream, IngressChatRequest request) implements Reply {
      @Override
      public int status() {
        return 200;
      }
    }
  }

  /** An endpoint: reads the exchange, returns a reply. Never writes to the exchange itself. */
  @FunctionalInterface
  private interface Endpoint {
    Reply serve(HttpExchange exchange);
  }

  /**
   * The single funnel every request passes through: counts it, bounds it in time, guarantees the
   * exchange is closed, and converts anything unexpected into a canonical 500.
   */
  private void dispatch(final HttpExchange exchange, final Endpoint endpoint) throws IOException {
    metrics.onReceived();
    Reply reply;
    try {
      reply = accepting ? bounded(exchange, endpoint) : error(503, "shutting-down", null);
    } catch (final RuntimeException | Error unexpected) {
      // Nothing internal may reach the caller — not a message, not a type name.
      reply = error(500, "internal-error", null);
    }
    try {
      if (reply instanceof Reply.Streaming streaming) {
        writeEventStream(exchange, streaming);
      } else {
        write(exchange, (Reply.Buffered) reply);
      }
    } finally {
      metrics.onCompleted(reply.status());
      exchange.close();
    }
  }

  /**
   * Runs the endpoint on a bounded budget so a stalled downstream cannot pin the connection.
   *
   * <p>The exchange is passed explicitly rather than carried in a thread-local: the work runs on a
   * different virtual thread from the one that waits for it, so any ambient context would be
   * invisible to the endpoint.
   */
  private Reply bounded(final HttpExchange exchange, final Endpoint endpoint) {
    final ExecutorService current = executor;
    if (current == null) {
      return error(503, "shutting-down", null);
    }
    // The budget bounds producing a reply, not writing one: a legitimate stream may outlive it, and
    // its own guard policy is what bounds the stream itself.
    final Future<Reply> future = current.submit(() -> endpoint.serve(exchange));
    try {
      return future.get(config.requestTimeout().toMillis(), TimeUnit.MILLISECONDS);
    } catch (final TimeoutException timeout) {
      future.cancel(true); // never leave work running behind an already-returned response
      return error(504, "request-timeout", null);
    } catch (final InterruptedException interrupted) {
      future.cancel(true);
      Thread.currentThread().interrupt();
      return error(503, "interrupted", null);
    } catch (final ExecutionException failure) {
      return error(500, "internal-error", null);
    }
  }

  /**
   * Writes a live stream as Server-Sent Events over a chunked response.
   *
   * <p>A response length of {@code 0} puts the JDK server into chunked transfer, and each event is
   * flushed the moment it is written — so the client sees a token as soon as the gateway does. The
   * loop pulls one chunk, writes it, and only then pulls the next: the socket write is the
   * backpressure, so a slow client slows the provider instead of filling a buffer here.
   *
   * <p><b>Client disconnect is detected by the write failing.</b> That is the only reliable signal
   * the JDK server offers, and it is enough: the {@code IOException} triggers cancellation, which
   * unwinds through the pipeline to the provider connection. The {@code finally} guarantees the
   * stream is cancelled on every path, so no provider socket is left running for a client that has
   * gone.
   */
  private void writeEventStream(final HttpExchange exchange, final Reply.Streaming streaming)
      throws IOException {
    final IngressStream stream = streaming.stream();
    exchange.getResponseHeaders().add("Content-Type", "text/event-stream; charset=utf-8");
    exchange.getResponseHeaders().add("Cache-Control", "no-cache");
    exchange.getResponseHeaders().add("Connection", "keep-alive");
    // 0 => chunked transfer encoding, which is what allows an unknown-length live body.
    exchange.sendResponseHeaders(200, 0);

    boolean completed = false;
    try (OutputStream out = exchange.getResponseBody()) {
      while (!stream.finished()) {
        final io.reliabilityai.gateway.canonical.stream.StreamChunk chunk = stream.next();
        final String event = encodeEvent(streaming.request(), chunk);
        if (event != null) {
          out.write(event.getBytes(StandardCharsets.UTF_8));
          out.flush();
        }
      }
      out.write(DONE_EVENT.getBytes(StandardCharsets.UTF_8));
      out.flush();
      completed = true;
    } catch (final IOException clientGone) {
      stream.cancel("client-disconnected");
      throw clientGone;
    } finally {
      if (!completed) {
        stream.cancel("write-failed");
      }
    }
  }

  /** The SSE sentinel that closes a chat-completions stream. */
  private static final String DONE_EVENT = "data: [DONE]\n\n";

  /**
   * Renders one canonical chunk as an SSE event in the chat-completions delta shape.
   *
   * @return the event text, or {@code null} for chunks that carry nothing a client should see
   */
  private static String encodeEvent(
      final IngressChatRequest request,
      final io.reliabilityai.gateway.canonical.stream.StreamChunk chunk) {
    final StringBuilder json = new StringBuilder(160);
    json.append("{\"id\":").append(IngressJson.quote(request.correlationId()));
    json.append(",\"object\":\"chat.completion.chunk\"");
    json.append(",\"model\":").append(IngressJson.quote(request.model()));

    if (chunk instanceof io.reliabilityai.gateway.canonical.stream.StreamChunk.Delta delta) {
      json.append(",\"choices\":[{\"index\":0,\"delta\":{\"content\":")
          .append(IngressJson.quote(delta.text()))
          .append("},\"finish_reason\":null}]}");
    } else if (chunk
        instanceof io.reliabilityai.gateway.canonical.stream.StreamChunk.ToolCallDelta toolCall) {
      json.append(",\"choices\":[{\"index\":0,\"delta\":{\"tool_calls\":[{\"id\":")
          .append(IngressJson.quote(toolCall.toolCall().callId()))
          .append(",\"type\":\"function\",\"function\":{\"name\":")
          .append(IngressJson.quote(toolCall.toolCall().name()))
          .append(",\"arguments\":")
          .append(IngressJson.quote(toolCall.toolCall().argumentsRaw()))
          .append("}}]},\"finish_reason\":null}]}");
    } else if (chunk instanceof io.reliabilityai.gateway.canonical.stream.StreamChunk.Usage usage) {
      json.append(",\"choices\":[],\"usage\":{\"prompt_tokens\":")
          .append(usage.usage().prompt())
          .append(",\"completion_tokens\":")
          .append(usage.usage().completion())
          .append(",\"total_tokens\":")
          .append(usage.usage().prompt() + usage.usage().completion())
          .append("}}");
    } else if (chunk
        instanceof io.reliabilityai.gateway.canonical.stream.StreamChunk.Terminal terminal) {
      json.append(",\"choices\":[{\"index\":0,\"delta\":{},\"finish_reason\":")
          .append(IngressJson.quote(finishReason(terminal)))
          .append("}]}");
    } else {
      return null;
    }
    return "data: " + json + "\n\n";
  }

  private static String finishReason(
      final io.reliabilityai.gateway.canonical.stream.StreamChunk.Terminal terminal) {
    if (terminal.state()
        != io.reliabilityai.gateway.canonical.stream.StreamState.TERMINAL_COMPLETE) {
      // A stream that did not complete intact must say so; reporting "stop" would tell the client a
      // truncated answer was whole.
      return "error";
    }
    return switch (terminal.finishReason()) {
      case STOP -> "stop";
      case LENGTH -> "length";
      case TOOL_CALLS -> "tool_calls";
      case CONTENT_FILTER -> "content_filter";
      case ERROR -> "error";
    };
  }

  private void write(final HttpExchange exchange, final Reply.Buffered reply) throws IOException {
    final byte[] payload = reply.body().getBytes(StandardCharsets.UTF_8);
    exchange.getResponseHeaders().add("Content-Type", reply.contentType());
    // Keep-alive is the JDK server's default for HTTP/1.1; a definite length is what makes it work.
    exchange.sendResponseHeaders(reply.status(), payload.length);
    try (OutputStream out = exchange.getResponseBody()) {
      out.write(payload);
    }
  }

  // ---- endpoints -----------------------------------------------------------------------------

  private Reply handleHealth(final HttpExchange exchange) {
    return new Reply.Buffered(200, TEXT, probe.state() + "\n");
  }

  private Reply handleReady(final HttpExchange exchange) {
    return probe.ready()
        ? new Reply.Buffered(200, TEXT, "READY\n")
        : new Reply.Buffered(503, TEXT, probe.state() + "\n");
  }

  private Reply handleMetrics(final HttpExchange exchange) {
    return new Reply.Buffered(200, TEXT, metrics.render());
  }

  private Reply handleUnknown(final HttpExchange exchange) {
    return error(404, "not-found", null);
  }

  private Reply handleChat(final HttpExchange exchange) {
    if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
      return error(405, "method-not-allowed", null);
    }
    final String contentType = header(exchange, "Content-Type");
    if (contentType == null || !contentType.toLowerCase(Locale.ROOT).startsWith(JSON)) {
      return error(415, "unsupported-media-type", null);
    }

    final String body;
    try {
      body = readBounded(exchange);
    } catch (final BodyTooLargeException tooLarge) {
      return error(413, "payload-too-large", null);
    } catch (final IOException io) {
      return error(400, "unreadable-body", null);
    }

    final IngressChatRequest request;
    try {
      request = decode(exchange, body);
    } catch (final IngressJson.JsonException malformed) {
      return error(400, "malformed-json", null);
    } catch (final UnprocessableException unprocessable) {
      return error(422, unprocessable.code(), null);
    }

    final IngressOutcome outcome = handler.handle(request);
    if (outcome instanceof IngressOutcome.Streamed streamed) {
      return new Reply.Streaming(streamed.stream(), request);
    }
    if (outcome instanceof IngressOutcome.Completed completed) {
      return new Reply.Buffered(200, JSON, encode(request, completed.response()));
    }
    final IngressOutcome.Refused refused = (IngressOutcome.Refused) outcome;
    return error(statusFor(refused), refused.error().providerCodeOpaque(), refused.stage());
  }

  // ---- request decoding ----------------------------------------------------------------------

  /** Raised when the body exceeds the configured ceiling. */
  private static final class BodyTooLargeException extends IOException {
    private static final long serialVersionUID = 1L;

    BodyTooLargeException() {
      super("body too large");
    }
  }

  /** Raised when the body parses but is not a usable request. */
  private static final class UnprocessableException extends RuntimeException {
    private static final long serialVersionUID = 1L;
    private final String code;

    UnprocessableException(final String code) {
      super(code);
      this.code = code;
    }

    String code() {
      return code;
    }
  }

  /**
   * Reads at most {@code maxBodyBytes + 1} bytes. Reading one byte past the ceiling is what lets
   * the server distinguish "exactly at the limit" from "over it" without buffering an unbounded
   * body — a declared Content-Length is not trusted, because a hostile client can lie about it.
   */
  private String readBounded(final HttpExchange exchange) throws IOException {
    final int ceiling = config.maxBodyBytes();
    try (InputStream in = exchange.getRequestBody()) {
      final byte[] buffer = in.readNBytes(ceiling + 1);
      if (buffer.length > ceiling) {
        throw new BodyTooLargeException();
      }
      return new String(buffer, StandardCharsets.UTF_8);
    }
  }

  private IngressChatRequest decode(final HttpExchange exchange, final String body) {
    final Map<String, Object> root = IngressJson.parseObject(body);

    final String model = IngressJson.stringAt(root, "model");
    if (model == null || model.isBlank()) {
      throw new UnprocessableException("model-required");
    }

    final List<Object> rawMessages = IngressJson.arrayAt(root, "messages");
    if (rawMessages.isEmpty()) {
      throw new UnprocessableException("messages-required");
    }
    final List<IngressMessage> messages = new ArrayList<>(rawMessages.size());
    for (final Object entry : rawMessages) {
      if (!(entry instanceof Map)) {
        throw new UnprocessableException("malformed-message");
      }
      @SuppressWarnings("unchecked")
      final Map<String, Object> message = (Map<String, Object>) entry;
      final String role = IngressJson.stringAt(message, "role");
      final String content = IngressJson.stringAt(message, "content");
      if (role == null || role.isBlank() || content == null) {
        throw new UnprocessableException("malformed-message");
      }
      messages.add(new IngressMessage(role, content));
    }

    final Map<String, String> params = new LinkedHashMap<>();
    copyNumber(root, "temperature", params);
    copyNumber(root, "top_p", params);
    copyNumber(root, "max_tokens", params);
    copyStop(root, params);
    if (jsonModeRequested(root)) {
      params.put("json_mode", "true");
    }
    if (Boolean.TRUE.equals(root.get("stream"))) {
      params.put("stream", "true");
    }

    final String correlation = orDefault(header(exchange, "X-Correlation-Id"), nextId("corr"));
    final String idempotency = orDefault(header(exchange, "Idempotency-Key"), nextId("idem"));
    return new IngressChatRequest(
        model,
        List.copyOf(messages),
        Map.copyOf(params),
        header(exchange, "Authorization"),
        correlation,
        idempotency);
  }

  private static boolean jsonModeRequested(final Map<String, Object> root) {
    final Object format = root.get("response_format");
    if (!(format instanceof Map)) {
      return false;
    }
    @SuppressWarnings("unchecked")
    final Map<String, Object> responseFormat = (Map<String, Object>) format;
    return "json_object".equals(IngressJson.stringAt(responseFormat, "type"));
  }

  private static void copyNumber(
      final Map<String, Object> root, final String name, final Map<String, String> params) {
    final Object value = root.get(name);
    if (value instanceof Double number) {
      params.put(
          name,
          number == Math.rint(number)
              ? String.valueOf(number.longValue())
              : String.valueOf(number));
    }
  }

  private static void copyStop(final Map<String, Object> root, final Map<String, String> params) {
    final Object value = root.get("stop");
    if (value instanceof String single && !single.isBlank()) {
      params.put("stop", single);
      return;
    }
    final List<Object> sequences = IngressJson.arrayAt(root, "stop");
    if (sequences.isEmpty()) {
      return;
    }
    final StringBuilder joined = new StringBuilder();
    for (final Object sequence : sequences) {
      if (sequence instanceof String text && !text.isBlank()) {
        if (joined.length() > 0) {
          joined.append(',');
        }
        joined.append(text);
      }
    }
    if (joined.length() > 0) {
      params.put("stop", joined.toString());
    }
  }

  // ---- response encoding ---------------------------------------------------------------------

  private static String encode(final IngressChatRequest request, final CanonicalResponse response) {
    final StringBuilder out = new StringBuilder(256);
    out.append("{\"id\":").append(IngressJson.quote(request.correlationId()));
    out.append(",\"object\":\"chat.completion\"");
    out.append(",\"model\":").append(IngressJson.quote(request.model()));
    out.append(",\"choices\":[{\"index\":0,\"message\":{\"role\":\"assistant\",\"content\":")
        .append(IngressJson.quote(response.content()));
    if (!response.toolCalls().isEmpty()) {
      out.append(",\"tool_calls\":[");
      for (int i = 0; i < response.toolCalls().size(); i++) {
        if (i > 0) {
          out.append(',');
        }
        final CanonicalToolCall call = response.toolCalls().get(i);
        out.append("{\"id\":")
            .append(IngressJson.quote(call.callId()))
            .append(",\"type\":\"function\",\"function\":{\"name\":")
            .append(IngressJson.quote(call.name()))
            .append(",\"arguments\":")
            .append(IngressJson.quote(call.argumentsRaw()))
            .append("}}");
      }
      out.append(']');
    }
    out.append("},\"finish_reason\":")
        .append(IngressJson.quote(finishReason(response)))
        .append("}]");
    out.append(",\"usage\":{\"prompt_tokens\":")
        .append(response.usage().prompt())
        .append(",\"completion_tokens\":")
        .append(response.usage().completion())
        .append(",\"total_tokens\":")
        .append(response.usage().prompt() + response.usage().completion())
        .append('}');
    return out.append('}').toString();
  }

  private static String finishReason(final CanonicalResponse response) {
    return switch (response.finishReason()) {
      case STOP -> "stop";
      case LENGTH -> "length";
      case TOOL_CALLS -> "tool_calls";
      case CONTENT_FILTER -> "content_filter";
      case ERROR -> "error";
    };
  }

  /**
   * Maps a pipeline refusal onto an HTTP status.
   *
   * <p>The distinction that matters is whose fault it is. A denied policy or a bad credential is
   * the caller's problem (4xx) and retrying unchanged will not help; a provider outage or a broken
   * provider response is ours (5xx) and is worth retrying. Getting this backwards teaches clients
   * to hammer a gateway that is deliberately refusing them.
   */
  private static int statusFor(final IngressOutcome.Refused refused) {
    final String code = refused.error().providerCodeOpaque();
    return switch (refused.stage()) {
      case "INGRESS" -> 400;
      case "AUTHN", "SECRETS" -> 401;
      case "GOVERNANCE" -> governanceStatus(code);
      case "ROUTER" -> 404;
      case "RELIABILITY", "ADAPTER" -> providerStatus(refused);
      case "STREAM_GUARD", "SCHEMA_LOCK" -> 502;
      default -> 500;
    };
  }

  private static int governanceStatus(final String code) {
    if (code == null) {
      return 403;
    }
    // A spend or usage ceiling is a throttle the caller can wait out; a policy denial is not.
    return switch (code) {
      case "rate-limited", "quota-exceeded", "budget-exceeded" -> 429;
      default -> 403;
    };
  }

  private static int providerStatus(final IngressOutcome.Refused refused) {
    return switch (refused.error().category()) {
      case RATE_LIMITED -> 429;
      case TIMEOUT -> 504;
      case PROVIDER_UNAVAILABLE, TRANSPORT, MALFORMED_RESPONSE -> 502;
      case PROVIDER_REJECTED -> 400;
      case CONTENT_FILTERED -> 422;
      // Our credential was refused by the provider — the caller cannot fix that, so it is not a
      // 401.
      case AUTH_FAILED -> 502;
      default -> 500;
    };
  }

  private static Reply error(final int status, final String code, final String stage) {
    final StringBuilder out = new StringBuilder(96);
    out.append("{\"error\":{\"code\":").append(IngressJson.quote(code == null ? "error" : code));
    if (stage != null) {
      out.append(",\"stage\":").append(IngressJson.quote(stage));
    }
    out.append(",\"status\":").append(status).append("}}");
    return new Reply.Buffered(status, JSON, out.toString());
  }

  // ---- helpers -------------------------------------------------------------------------------

  private static String header(final HttpExchange exchange, final String name) {
    final List<String> values = exchange.getRequestHeaders().get(name);
    return values == null || values.isEmpty() ? null : values.get(0);
  }

  private static String orDefault(final String value, final String fallback) {
    return value == null || value.isBlank() ? fallback : value;
  }

  private String nextId(final String prefix) {
    return prefix + '-' + requestCounter.incrementAndGet();
  }
}

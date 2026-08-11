package io.reliabilityai.gateway.provider.openai;

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.zip.GZIPOutputStream;

/**
 * A loopback HTTP server standing in for OpenAI. No test in this module ever reaches the real API.
 *
 * <p>Records what the adapter actually sent, so request-mapping and authentication assertions are
 * made against bytes on the wire rather than against the adapter's own view of them.
 */
final class FakeOpenAiServer implements AutoCloseable {

  /** One captured request. */
  record Captured(String method, String path, Map<String, String> headers, String body) {}

  private final HttpServer server;
  private final List<Captured> captured = new CopyOnWriteArrayList<>();

  private volatile int status = 200;
  private volatile String responseBody = "{}";
  private volatile String contentType = "application/json";
  private volatile boolean gzip;
  private volatile long delayMillis;

  FakeOpenAiServer() {
    try {
      server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    } catch (final IOException e) {
      throw new IllegalStateException("cannot start loopback server", e);
    }
    server.createContext(
        "/",
        exchange -> {
          final Map<String, String> headers = new java.util.LinkedHashMap<>();
          exchange
              .getRequestHeaders()
              .forEach(
                  (name, values) ->
                      headers.put(
                          name.toLowerCase(java.util.Locale.ROOT),
                          values.isEmpty() ? "" : values.get(0)));
          final String body =
              new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
          captured.add(
              new Captured(
                  exchange.getRequestMethod(), exchange.getRequestURI().getPath(), headers, body));

          if (delayMillis > 0) {
            try {
              Thread.sleep(delayMillis);
            } catch (final InterruptedException e) {
              Thread.currentThread().interrupt();
            }
          }

          final byte[] payload = payload();
          exchange.getResponseHeaders().add("Content-Type", contentType);
          if (gzip) {
            exchange.getResponseHeaders().add("Content-Encoding", "gzip");
          }
          exchange.sendResponseHeaders(status, payload.length);
          try (OutputStream out = exchange.getResponseBody()) {
            out.write(payload);
          }
        });
    server.start();
  }

  private byte[] payload() {
    final byte[] raw = responseBody.getBytes(StandardCharsets.UTF_8);
    if (!gzip) {
      return raw;
    }
    try (var bytes = new java.io.ByteArrayOutputStream();
        var out = new GZIPOutputStream(bytes)) {
      out.write(raw);
      out.finish();
      return bytes.toByteArray();
    } catch (final IOException e) {
      throw new IllegalStateException("cannot gzip", e);
    }
  }

  URI baseUri() {
    return URI.create("http://127.0.0.1:" + server.getAddress().getPort());
  }

  FakeOpenAiServer respond(final int newStatus, final String body) {
    this.status = newStatus;
    this.responseBody = body;
    return this;
  }

  FakeOpenAiServer gzipped() {
    this.gzip = true;
    return this;
  }

  FakeOpenAiServer delay(final long millis) {
    this.delayMillis = millis;
    return this;
  }

  List<Captured> captured() {
    return new ArrayList<>(captured);
  }

  Captured lastRequest() {
    return captured.get(captured.size() - 1);
  }

  @Override
  public void close() {
    server.stop(0);
  }
}

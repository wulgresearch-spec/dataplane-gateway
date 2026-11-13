package io.reliabilityai.gateway.dataplane.app.testing;

import io.reliabilityai.gateway.common.Preconditions;
import java.io.ByteArrayOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.function.Consumer;

/**
 * Tiny portable HTTP/1.1 loopback provider for integration tests.
 *
 * <p>Uses only the standard {@code java.net} APIs, not the JDK's non-portable {@code
 * com.sun.net.httpserver} implementation. It accepts POST requests, drains their bodies, and
 * responds with either a fixed JSON entity or real, flushed chunked SSE frames.
 */
public final class LoopbackHttpProvider implements AutoCloseable {

  /** Minimal request metadata observed by provider-wiring assertions. */
  public record Request(String path, String authorization) {}

  private final ServerSocket listener;
  private final Thread worker;
  private final int status;
  private final String contentType;
  private final String[] frames;
  private final Consumer<Request> observer;
  private final boolean streaming;
  private volatile boolean closed;

  private LoopbackHttpProvider(
      final int status,
      final String contentType,
      final boolean streaming,
      final Consumer<Request> observer,
      final String... frames)
      throws IOException {
    this.status = status;
    this.contentType = contentType;
    this.streaming = streaming;
    this.observer = Preconditions.requireNonNull(observer, "observer");
    this.frames = frames.clone();
    listener = new ServerSocket();
    listener.bind(new InetSocketAddress("127.0.0.1", 0));
    worker = new Thread(this::acceptRequests, "loopback-provider");
    worker.setDaemon(true);
    worker.start();
  }

  /** Starts a fixed JSON response provider and optionally observes requests. */
  public static LoopbackHttpProvider fixed(
      final int status, final String json, final Consumer<Request> observer) throws IOException {
    return new LoopbackHttpProvider(
        status, "application/json", false, observer, Preconditions.requireNonNull(json, "json"));
  }

  /** Starts a chunked SSE response provider which flushes every frame. */
  public static LoopbackHttpProvider streaming(final String... sseFrames) throws IOException {
    return new LoopbackHttpProvider(200, "text/event-stream", true, request -> {}, sseFrames);
  }

  /** Returns the real bound loopback endpoint. */
  public URI uri() {
    return URI.create("http://127.0.0.1:" + listener.getLocalPort());
  }

  private void acceptRequests() {
    while (!closed) {
      try (Socket connection = listener.accept()) {
        connection.setSoTimeout(10_000);
        respond(connection);
      } catch (final SocketException e) {
        if (closed) {
          return;
        }
        throw new IllegalStateException("loopback provider socket failed", e);
      } catch (final IOException e) {
        if (!closed) {
          throw new IllegalStateException("loopback provider request failed", e);
        }
      }
    }
  }

  private void respond(final Socket connection) throws IOException {
    final InputStream in = connection.getInputStream();
    final String requestLine = readLine(in);
    if (requestLine == null) {
      return;
    }
    final String[] firstLine = requestLine.split(" ", 3);
    if (firstLine.length != 3) {
      throw new IOException("malformed provider test request");
    }
    String authorization = null;
    long length = 0L;
    boolean chunked = false;
    String header;
    while ((header = readLine(in)) != null && !header.isEmpty()) {
      final int colon = header.indexOf(':');
      if (colon < 0) {
        throw new IOException("malformed provider test header");
      }
      final String name = header.substring(0, colon).trim().toLowerCase(Locale.ROOT);
      final String value = header.substring(colon + 1).trim();
      if (name.equals("authorization")) {
        authorization = value;
      } else if (name.equals("content-length")) {
        length = Long.parseLong(value);
      } else if (name.equals("transfer-encoding")) {
        chunked = value.toLowerCase(Locale.ROOT).contains("chunked");
      }
    }
    if (chunked) {
      drainChunks(in);
    } else {
      drainBytes(in, length);
    }
    observer.accept(new Request(URI.create(firstLine[1]).getPath(), authorization));
    final OutputStream out = connection.getOutputStream();
    if (streaming) {
      writeHead(out, "Transfer-Encoding: chunked");
      for (final String frame : frames) {
        final byte[] bytes = frame.getBytes(StandardCharsets.UTF_8);
        out.write((Integer.toHexString(bytes.length) + "\r\n").getBytes(StandardCharsets.US_ASCII));
        out.write(bytes);
        out.write("\r\n".getBytes(StandardCharsets.US_ASCII));
        out.flush();
      }
      out.write("0\r\n\r\n".getBytes(StandardCharsets.US_ASCII));
    } else {
      final byte[] bytes = frames[0].getBytes(StandardCharsets.UTF_8);
      writeHead(out, "Content-Length: " + bytes.length);
      out.write(bytes);
    }
    out.flush();
  }

  private void writeHead(final OutputStream out, final String transferHeader) throws IOException {
    final String reason =
        switch (status) {
          case 200 -> "OK";
          case 429 -> "Too Many Requests";
          case 500 -> "Internal Server Error";
          default -> "Response";
        };
    final String head =
        "HTTP/1.1 "
            + status
            + " "
            + reason
            + "\r\n"
            + "Content-Type: "
            + contentType
            + "\r\n"
            + transferHeader
            + "\r\n"
            + "Connection: close\r\n\r\n";
    out.write(head.getBytes(StandardCharsets.US_ASCII));
  }

  private static String readLine(final InputStream in) throws IOException {
    final ByteArrayOutputStream buffer = new ByteArrayOutputStream();
    while (buffer.size() < 8192) {
      final int b = in.read();
      if (b == -1) {
        if (buffer.size() == 0) {
          return null;
        }
        throw new EOFException("partial HTTP line in test provider");
      }
      if (b == '\n') {
        final byte[] bytes = buffer.toByteArray();
        final int size =
            bytes.length > 0 && bytes[bytes.length - 1] == '\r' ? bytes.length - 1 : bytes.length;
        return new String(bytes, 0, size, StandardCharsets.US_ASCII);
      }
      buffer.write(b);
    }
    throw new IOException("oversized HTTP line in test provider");
  }

  private static void drainBytes(final InputStream in, final long length) throws IOException {
    if (length < 0 || length > 1_048_576L) {
      throw new IOException("invalid provider test body length");
    }
    long remaining = length;
    final byte[] buffer = new byte[4096];
    while (remaining > 0) {
      final int read = in.read(buffer, 0, (int) Math.min(buffer.length, remaining));
      if (read < 0) {
        throw new EOFException("truncated provider test request body");
      }
      remaining -= read;
    }
  }

  private static void drainChunks(final InputStream in) throws IOException {
    while (true) {
      final String sizeLine = readLine(in);
      if (sizeLine == null) {
        throw new EOFException("truncated chunked provider test request");
      }
      final int size = Integer.parseInt(sizeLine.split(";", 2)[0], 16);
      if (size == 0) {
        String trailer;
        while ((trailer = readLine(in)) != null && !trailer.isEmpty()) {
          // Ignore optional trailing request metadata.
        }
        return;
      }
      drainBytes(in, size);
      if (!"".equals(readLine(in))) {
        throw new IOException("invalid provider test chunk terminator");
      }
    }
  }

  @Override
  public void close() throws IOException {
    closed = true;
    listener.close();
  }
}

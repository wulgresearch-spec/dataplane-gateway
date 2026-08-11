package io.reliabilityai.gateway.provider.openai;

import io.reliabilityai.gateway.common.Preconditions;
import io.reliabilityai.gateway.dataplane.embedding.api.EmbeddingFailure;
import io.reliabilityai.gateway.dataplane.embedding.api.EmbeddingTransportException;
import io.reliabilityai.gateway.dataplane.embedding.api.EmbeddingTransportPort;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.ConnectException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpConnectTimeoutException;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.time.Duration;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.zip.GZIPInputStream;
import javax.net.ssl.SSLException;

/**
 * The production {@link EmbeddingTransportPort}, built on {@link HttpClient} only — closing B66.
 *
 * <p>Before this class the only implementation of the port in the entire repository was a test
 * double, so the embedding stack was complete and could not send a byte. That is the whole of what
 * this file changes.
 *
 * <p><b>Why it lives in a provider module despite containing no vendor concept.</b> Nothing here
 * knows OpenAI: the port hands it a method, a URL, headers and a body, and it performs one HTTP
 * exchange. But {@code EmbeddingNeutralityTest} forbids {@code java.net.}, {@code HttpClient},
 * {@code Socket} and {@code URLConnection} <em>anywhere</em> in {@code gateway-dp-embedding},
 * deliberately, so that the neutral module stays testable without a network. A generic HTTP class
 * is still a network class, so the only lawful home is a provider module. The consequence is stated
 * rather than hidden: a second provider would either depend on this module or copy sixty lines, and
 * the honest fix — shared transport infrastructure in the provider-adapter module — is an
 * architecture decision, not a refactor to slip in here.
 *
 * <p><b>This class never retries.</b> Not once, not for a connect failure, not for a 503 — the same
 * rule {@code OpenAiProviderTransport} follows for the inference path, and for the same reason.
 * Retry, backoff and the attempt budget belong to {@code EmbeddingPipeline}, which owns the failure
 * policy and the health state; a transport that quietly retried underneath it would double the real
 * attempt count and turn one provider brownout into a self-inflicted outage.
 *
 * <p>One {@link HttpClient} is shared for the transport's lifetime, which is what gives connection
 * pooling and HTTP/2 multiplexing. Constructing a client per call would defeat both and leak a
 * selector thread per request.
 */
public final class HttpEmbeddingTransport implements EmbeddingTransportPort {

  /**
   * Headers the caller may not set, because this class or the JDK owns them.
   *
   * <p>{@code Content-Length} and {@code Host} are restricted by the JDK and throw if set. The
   * others would let a caller change how the body is framed underneath the encoder that produced
   * it.
   */
  private static final Set<String> RESERVED =
      Set.of("content-length", "host", "connection", "upgrade", "transfer-encoding", "expect");

  /** Beyond this, a response body is refused rather than buffered. */
  private static final long DEFAULT_MAX_RESPONSE_BYTES = 64L * 1024L * 1024L;

  private final HttpClient httpClient;

  private final long maxResponseBytes;

  /**
   * Creates a transport with a client of its own.
   *
   * @param connectTimeout how long to wait for a connection
   */
  public HttpEmbeddingTransport(final Duration connectTimeout) {
    this(
        HttpClient.newBuilder()
            .version(HttpClient.Version.HTTP_2)
            .followRedirects(HttpClient.Redirect.NEVER)
            .connectTimeout(Preconditions.requireNonNull(connectTimeout, "connectTimeout"))
            .build(),
        DEFAULT_MAX_RESPONSE_BYTES);
  }

  /**
   * Creates a transport over a supplied client.
   *
   * <p>Redirects are never followed, and that is a security decision rather than a default. A
   * redirect on a credentialed POST would resend the {@code Authorization} header to whatever host
   * the response names — an open credential-forwarding primitive controlled by the far end.
   *
   * @param httpClient the shared, pooled client
   */
  public HttpEmbeddingTransport(final HttpClient httpClient) {
    this(httpClient, DEFAULT_MAX_RESPONSE_BYTES);
  }

  /**
   * Creates a transport with an explicit response-size ceiling.
   *
   * <p>The ceiling is configurable because it is a resource bound, and a deployment embedding
   * 3072-dimension vectors in batches of 2048 has a legitimately different one from a deployment
   * that does not. It is also what lets the bound be tested without moving 64 MiB through a socket.
   *
   * @param httpClient the shared, pooled client
   * @param maxResponseBytes the largest decompressed body to accept
   */
  public HttpEmbeddingTransport(final HttpClient httpClient, final long maxResponseBytes) {
    this.httpClient = Preconditions.requireNonNull(httpClient, "httpClient");
    if (maxResponseBytes < 1L) {
      throw new IllegalArgumentException("maxResponseBytes must be positive");
    }
    this.maxResponseBytes = maxResponseBytes;
  }

  @Override
  public Exchange send(final Call call) {
    Preconditions.requireNonNull(call, "call");
    final HttpRequest request = build(call);
    try {
      final HttpResponse<InputStream> response =
          httpClient.send(request, HttpResponse.BodyHandlers.ofInputStream());
      return new Exchange(response.statusCode(), read(response));
    } catch (final HttpConnectTimeoutException tooSlow) {
      throw new EmbeddingTransportException(
          EmbeddingFailure.TIMEOUT,
          "the embedding endpoint did not accept a connection in time",
          tooSlow);
    } catch (final HttpTimeoutException tooSlow) {
      throw new EmbeddingTransportException(
          EmbeddingFailure.TIMEOUT, "the embedding endpoint did not answer in time", tooSlow);
    } catch (final ConnectException unreachable) {
      throw new EmbeddingTransportException(
          EmbeddingFailure.NETWORK, "the embedding endpoint refused the connection", unreachable);
    } catch (final SSLException tls) {
      // NETWORK rather than AUTH_FAILED. A TLS failure is a transport fault — an expired server
      // certificate, an interception proxy — not a rejected credential, and classifying it as an
      // authentication failure would stop the pipeline retrying something that is usually
      // transient.
      throw new EmbeddingTransportException(
          EmbeddingFailure.NETWORK, "the TLS handshake with the embedding endpoint failed", tls);
    } catch (final IOException broken) {
      throw new EmbeddingTransportException(
          EmbeddingFailure.NETWORK, "the connection to the embedding endpoint failed", broken);
    } catch (final InterruptedException interrupted) {
      // The flag is restored before throwing. Swallowing it leaves a thread that looks healthy and
      // will not stop at the next blocking call, which is how a shutdown hangs.
      Thread.currentThread().interrupt();
      throw new EmbeddingTransportException(
          EmbeddingFailure.NETWORK, "the embedding call was interrupted", interrupted);
    }
  }

  /**
   * Builds the request.
   *
   * @param call what to send
   * @return the JDK request
   */
  private static HttpRequest build(final Call call) {
    final URI uri = uriOf(call.url());
    final HttpRequest.Builder builder =
        HttpRequest.newBuilder(uri)
            .timeout(Duration.ofMillis(Math.max(1L, call.timeoutMillis())))
            .method(
                call.method(),
                call.body() == null
                    ? HttpRequest.BodyPublishers.noBody()
                    : HttpRequest.BodyPublishers.ofByteArray(call.body()));
    if (call.headers() != null) {
      for (final Map.Entry<String, String> header : call.headers().entrySet()) {
        if (header.getKey() == null || header.getValue() == null) {
          continue;
        }
        if (RESERVED.contains(header.getKey().toLowerCase(Locale.ROOT))) {
          continue;
        }
        builder.header(header.getKey(), header.getValue());
      }
    }
    // Requested, not required. If the far end ignores it the body arrives plain and read() handles
    // both; asking is worth it because embedding responses are large float arrays in JSON text.
    builder.header("Accept-Encoding", "gzip");
    return builder.build();
  }

  /**
   * Parses and vets the target.
   *
   * @param url the absolute URL
   * @return the URI
   */
  private static URI uriOf(final String url) {
    final URI uri;
    try {
      uri = URI.create(Preconditions.requireNonBlank(url, "url"));
    } catch (final IllegalArgumentException malformed) {
      throw new EmbeddingTransportException(
          EmbeddingFailure.REJECTED, "the embedding endpoint URL is not a valid URI", malformed);
    }
    final String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
    if (!"https".equals(scheme) && !"http".equals(scheme)) {
      // REJECTED, not NETWORK: retrying a file: or a jar: target repeats the same mistake, and an
      // unchecked scheme here would let a misconfigured endpoint read the local filesystem.
      throw new EmbeddingTransportException(
          EmbeddingFailure.REJECTED, "the embedding endpoint must be http or https");
    }
    if (uri.getHost() == null) {
      throw new EmbeddingTransportException(
          EmbeddingFailure.REJECTED, "the embedding endpoint URL names no host");
    }
    return uri;
  }

  /**
   * Reads the body, decompressing when the far end says it is compressed.
   *
   * @param response the response
   * @return the decoded bytes
   * @throws IOException when the stream fails mid-read
   */
  private byte[] read(final HttpResponse<InputStream> response) throws IOException {
    final boolean gzip =
        response
            .headers()
            .firstValue("Content-Encoding")
            .map(value -> value.toLowerCase(Locale.ROOT).contains("gzip"))
            .orElse(false);
    try (InputStream raw = response.body();
        InputStream stream = gzip ? new GZIPInputStream(raw) : raw) {
      final ByteArrayOutputStream buffer = new ByteArrayOutputStream(8192);
      final byte[] chunk = new byte[8192];
      long total = 0L;
      int read;
      while ((read = stream.read(chunk)) >= 0) {
        total += read;
        // Bounded on the DECOMPRESSED size, which is the size that matters: a few kilobytes of gzip
        // can expand to gigabytes, and an unbounded read here turns a hostile or broken endpoint
        // into
        // an out-of-memory kill of the whole data plane.
        if (total > maxResponseBytes) {
          throw new EmbeddingTransportException(
              EmbeddingFailure.REJECTED, "the embedding response exceeded the size limit");
        }
        buffer.write(chunk, 0, read);
      }
      return buffer.toByteArray();
    }
  }
}

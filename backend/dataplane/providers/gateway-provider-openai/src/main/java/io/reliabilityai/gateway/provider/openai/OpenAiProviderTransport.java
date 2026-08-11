package io.reliabilityai.gateway.provider.openai;

import io.reliabilityai.gateway.canonical.secret.CredentialLease;
import io.reliabilityai.gateway.common.Preconditions;
import io.reliabilityai.gateway.dataplane.provider.api.ProviderTransportPort;
import io.reliabilityai.gateway.dataplane.provider.api.TransportException;
import io.reliabilityai.gateway.dataplane.provider.api.TransportRequest;
import io.reliabilityai.gateway.dataplane.provider.api.TransportResponse;
import io.reliabilityai.gateway.dataplane.provider.domain.TransportFailureKind;
import io.reliabilityai.gateway.ports.AttemptBudget;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.ConnectException;
import java.net.http.HttpClient;
import java.net.http.HttpConnectTimeoutException;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.zip.GZIPInputStream;
import javax.net.ssl.SSLException;

/**
 * The OpenAI HTTP transport, built on {@link HttpClient} only (Doc 25).
 *
 * <p><b>This class never retries.</b> Not once, not for a connect failure, not for a 503. Retry,
 * backoff, hedging and failover are the Reliability Engine's decisions (Doc 20) — it owns the retry
 * budget and the circuit state, and a transport that quietly retried underneath it would double the
 * real attempt count, corrupt the budget, and turn one provider brownout into a self-inflicted
 * outage. Every failure here is classified and surfaced upward immediately.
 *
 * <p>One {@link HttpClient} is shared for the adapter's lifetime, which is what gives connection
 * pooling and HTTP/2 multiplexing; constructing a client per request would defeat both.
 */
public final class OpenAiProviderTransport implements ProviderTransportPort {

  private final HttpClient httpClient;
  private final OpenAiConfiguration configuration;
  private final OpenAiAuthentication authentication;

  /**
   * Creates the transport with a client built from the configuration.
   *
   * @param configuration the adapter configuration
   * @param authentication the credential applier
   */
  public OpenAiProviderTransport(
      final OpenAiConfiguration configuration, final OpenAiAuthentication authentication) {
    this(defaultClient(configuration), configuration, authentication);
  }

  /**
   * Creates the transport with an explicit client (used by tests against a loopback server).
   *
   * @param httpClient the shared, pooled client
   * @param configuration the adapter configuration
   * @param authentication the credential applier
   */
  public OpenAiProviderTransport(
      final HttpClient httpClient,
      final OpenAiConfiguration configuration,
      final OpenAiAuthentication authentication) {
    this.httpClient = Preconditions.requireNonNull(httpClient, "httpClient");
    this.configuration = Preconditions.requireNonNull(configuration, "configuration");
    this.authentication = Preconditions.requireNonNull(authentication, "authentication");
  }

  /**
   * Builds the shared client: HTTP/2 with negotiation down to 1.1, pooled connections, and a
   * bounded connect timeout.
   *
   * @param configuration the adapter configuration
   * @return the client
   */
  public static HttpClient defaultClient(final OpenAiConfiguration configuration) {
    Preconditions.requireNonNull(configuration, "configuration");
    return HttpClient.newBuilder()
        .version(HttpClient.Version.HTTP_2)
        .followRedirects(HttpClient.Redirect.NEVER) // a redirected API call is a misconfiguration
        .connectTimeout(configuration.connectTimeout())
        .build();
  }

  @Override
  public TransportResponse exchange(
      final TransportRequest request, final CredentialLease credential, final AttemptBudget budget)
      throws TransportException {
    Preconditions.requireNonNull(request, "request");
    Preconditions.requireNonNull(credential, "credential");
    Preconditions.requireNonNull(budget, "budget");

    // TransportRequest carries no HTTP verb — the port is transport-neutral. An empty body means a
    // read (model list, health probe); a body means a completion. That is the whole of OpenAI's API
    // shape, so inferring here keeps the frozen port free of an HTTP concept it should not know.
    final byte[] body = request.body();
    final HttpRequest.Builder builder =
        HttpRequest.newBuilder()
            .uri(configuration.endpoint(request.endpointRef()))
            .timeout(budget.transportTimeout());
    if (body.length == 0) {
      builder.GET();
    } else {
      builder.POST(HttpRequest.BodyPublishers.ofByteArray(body));
    }
    for (final Map.Entry<String, String> header : request.headers().entrySet()) {
      builder.header(header.getKey(), header.getValue());
    }
    authentication.authorize(builder, credential);

    // A caller asking for text/event-stream gets a live body, not a buffered one. Deciding from the
    // Accept header the translator already set keeps the streaming choice in one place.
    if (isEventStream(request)) {
      return exchangeStreaming(builder.build());
    }

    final HttpResponse<byte[]> response;
    try {
      response = httpClient.send(builder.build(), HttpResponse.BodyHandlers.ofByteArray());
    } catch (final HttpConnectTimeoutException e) {
      throw new TransportException(TransportFailureKind.TIMEOUT, "connect-timeout", e);
    } catch (final HttpTimeoutException e) {
      throw new TransportException(TransportFailureKind.TIMEOUT, "request-timeout", e);
    } catch (final ConnectException e) {
      throw new TransportException(TransportFailureKind.CONNECT, "connect-failed", e);
    } catch (final SSLException e) {
      throw new TransportException(TransportFailureKind.TLS, "tls-failed", e);
    } catch (final IOException e) {
      throw new TransportException(TransportFailureKind.IO, "io-failed", e);
    } catch (final InterruptedException e) {
      Thread.currentThread().interrupt(); // never swallow the interrupt: shutdown depends on it
      throw new TransportException(TransportFailureKind.CANCELLED, "cancelled", e);
    }

    return new TransportResponse.Buffered(
        response.statusCode(), headersOf(response), decode(response));
  }

  private static boolean isEventStream(final TransportRequest request) {
    final String accept = request.headers().get("Accept");
    return accept != null
        && accept.toLowerCase(java.util.Locale.ROOT).contains("text/event-stream");
  }

  /**
   * Sends and returns the live body without reading it.
   *
   * <p>{@code ofInputStream} hands back as soon as the response headers arrive, so the first token
   * reaches the caller at the provider's pace rather than after the whole answer has been
   * collected. A non-2xx status is drained and closed here: an error body is small, bounded, and
   * the translator needs the status rather than a stream.
   */
  private TransportResponse exchangeStreaming(final HttpRequest request) throws TransportException {
    final HttpResponse<java.io.InputStream> response;
    try {
      response = httpClient.send(request, HttpResponse.BodyHandlers.ofInputStream());
    } catch (final HttpConnectTimeoutException e) {
      throw new TransportException(TransportFailureKind.TIMEOUT, "connect-timeout", e);
    } catch (final HttpTimeoutException e) {
      throw new TransportException(TransportFailureKind.TIMEOUT, "request-timeout", e);
    } catch (final ConnectException e) {
      throw new TransportException(TransportFailureKind.CONNECT, "connect-failed", e);
    } catch (final SSLException e) {
      throw new TransportException(TransportFailureKind.TLS, "tls-failed", e);
    } catch (final IOException e) {
      throw new TransportException(TransportFailureKind.IO, "io-failed", e);
    } catch (final InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new TransportException(TransportFailureKind.CANCELLED, "cancelled", e);
    }

    final Map<String, String> headers = new LinkedHashMap<>();
    response
        .headers()
        .map()
        .forEach(
            (name, values) -> {
              if (!values.isEmpty()) {
                headers.put(name, values.get(0));
              }
            });

    if (response.statusCode() < 200 || response.statusCode() >= 300) {
      try (java.io.InputStream errorBody = response.body()) {
        return new TransportResponse.Buffered(
            response.statusCode(), headers, errorBody.readNBytes(MAX_ERROR_BODY_BYTES));
      } catch (final IOException e) {
        return new TransportResponse.Buffered(response.statusCode(), headers, new byte[0]);
      }
    }

    return new TransportResponse.Streamed(
        response.statusCode(), headers, new OpenAiTransportStream(response.body()));
  }

  /** An error body is bounded: a provider must not be able to make us buffer without limit. */
  private static final int MAX_ERROR_BODY_BYTES = 64 * 1024;

  private static Map<String, String> headersOf(final HttpResponse<byte[]> response) {
    final Map<String, String> headers = new LinkedHashMap<>();
    response
        .headers()
        .map()
        .forEach(
            (name, values) -> {
              if (!values.isEmpty()) {
                headers.put(name, values.get(0));
              }
            });
    return headers;
  }

  /**
   * Transparently inflates a gzip body; a corrupt encoding is an IO failure, not a partial body.
   */
  private static byte[] decode(final HttpResponse<byte[]> response) throws TransportException {
    final boolean gzip =
        response
            .headers()
            .firstValue("content-encoding")
            .map(value -> value.toLowerCase(java.util.Locale.ROOT).contains("gzip"))
            .orElse(Boolean.FALSE);
    if (!gzip) {
      return response.body();
    }
    try (InputStream in = new GZIPInputStream(new java.io.ByteArrayInputStream(response.body()))) {
      final ByteArrayOutputStream out = new ByteArrayOutputStream(response.body().length * 4);
      in.transferTo(out);
      return out.toByteArray();
    } catch (final IOException e) {
      throw new TransportException(TransportFailureKind.IO, "gzip-decode-failed", e);
    }
  }
}

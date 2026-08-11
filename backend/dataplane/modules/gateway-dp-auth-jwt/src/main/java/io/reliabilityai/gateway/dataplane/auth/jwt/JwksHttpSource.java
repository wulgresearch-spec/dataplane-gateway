package io.reliabilityai.gateway.dataplane.auth.jwt;

import io.reliabilityai.gateway.common.Preconditions;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.function.Supplier;

/**
 * Fetches a JWKS document over HTTPS for {@link JwksKeyCache#refresh()} (Doc 37).
 *
 * <p>Called <b>only</b> from a refresh, never from a token verification. That separation is the
 * whole point: the request path must not block on the identity provider, so this class has no way
 * to be reached from one (AD-022).
 *
 * <p>Bounded and non-retrying: one attempt, explicit timeouts, no redirects. Retry policy belongs
 * to whatever schedules the refresh, and a redirected JWKS URL is a misconfiguration worth failing
 * on rather than following.
 */
public final class JwksHttpSource implements Supplier<String> {

  private final HttpClient httpClient;
  private final URI jwksUri;
  private final Duration requestTimeout;
  private final int maxDocumentBytes;

  /** The default ceiling on a JWKS document. */
  public static final int DEFAULT_MAX_DOCUMENT_BYTES = 512 * 1024;

  /**
   * Creates the source with a client built from the supplied timeouts.
   *
   * @param jwksUri the JWKS endpoint
   * @param connectTimeout the connect budget
   * @param requestTimeout the overall request budget
   */
  public JwksHttpSource(
      final URI jwksUri, final Duration connectTimeout, final Duration requestTimeout) {
    this(
        HttpClient.newBuilder()
            .version(HttpClient.Version.HTTP_2)
            .followRedirects(HttpClient.Redirect.NEVER)
            .connectTimeout(Preconditions.requireNonNull(connectTimeout, "connectTimeout"))
            .build(),
        jwksUri,
        requestTimeout,
        DEFAULT_MAX_DOCUMENT_BYTES);
  }

  /**
   * Creates the source with an explicit client (used by tests against a loopback server).
   *
   * @param httpClient the client
   * @param jwksUri the JWKS endpoint
   * @param requestTimeout the overall request budget
   * @param maxDocumentBytes the largest document accepted
   */
  public JwksHttpSource(
      final HttpClient httpClient,
      final URI jwksUri,
      final Duration requestTimeout,
      final int maxDocumentBytes) {
    this.httpClient = Preconditions.requireNonNull(httpClient, "httpClient");
    this.jwksUri = Preconditions.requireNonNull(jwksUri, "jwksUri");
    Preconditions.requireNonNull(requestTimeout, "requestTimeout");
    if (requestTimeout.isZero() || requestTimeout.isNegative()) {
      throw new IllegalArgumentException("requestTimeout must be positive");
    }
    this.requestTimeout = requestTimeout;
    if (maxDocumentBytes < 1) {
      throw new IllegalArgumentException("maxDocumentBytes must be >= 1");
    }
    this.maxDocumentBytes = maxDocumentBytes;
  }

  @Override
  public String get() {
    final HttpRequest request =
        HttpRequest.newBuilder()
            .uri(jwksUri)
            .timeout(requestTimeout)
            .header("Accept", "application/json")
            .GET()
            .build();
    try {
      final HttpResponse<String> response =
          httpClient.send(request, HttpResponse.BodyHandlers.ofString());
      if (response.statusCode() != 200) {
        throw new IllegalStateException("jwks status " + response.statusCode());
      }
      final String body = response.body();
      if (body == null || body.length() > maxDocumentBytes) {
        throw new IllegalStateException("jwks document rejected");
      }
      return body;
    } catch (final IOException io) {
      throw new IllegalStateException("jwks fetch failed", io);
    } catch (final InterruptedException interrupted) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException("jwks fetch interrupted", interrupted);
    }
  }
}

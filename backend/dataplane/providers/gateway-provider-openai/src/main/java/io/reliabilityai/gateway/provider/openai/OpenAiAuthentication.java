package io.reliabilityai.gateway.provider.openai;

import io.reliabilityai.gateway.canonical.secret.CredentialLease;
import io.reliabilityai.gateway.common.Preconditions;
import java.net.http.HttpRequest;

/**
 * Applies the OpenAI bearer credential to an outbound request (Doc 26 §17.1).
 *
 * <p>The key is read from the lease at the moment of sending and never before. It is never stored
 * in a field, never placed in a {@code TransportRequest} header (which the reliability engine may
 * retain across attempts), never logged, and never derived from an environment variable — the
 * secrets materialization path is the only source.
 *
 * <p><b>Honest limitation.</b> {@link HttpRequest.Builder#header} takes a {@code String}, and Java
 * strings are immutable and cannot be wiped. The header value therefore lives until it is
 * collected. What this class guarantees is the property that actually matters and is achievable:
 * the value is built per request from live lease material, is referenced by nothing after the
 * request is built, and is never cached or reused across requests.
 */
public final class OpenAiAuthentication {

  private static final String AUTHORIZATION = "Authorization";
  private static final String BEARER_PREFIX = "Bearer ";

  /**
   * Attaches the bearer credential to a request builder.
   *
   * @param builder the request under construction
   * @param lease the live credential lease
   * @throws IllegalStateException if the lease has already been closed or has expired
   */
  public void authorize(final HttpRequest.Builder builder, final CredentialLease lease) {
    Preconditions.requireNonNull(builder, "builder");
    Preconditions.requireNonNull(lease, "lease");
    if (!lease.active()) {
      // Sending an expired credential would surface as a confusing 401 from the provider; refusing
      // here keeps the cause local and obvious.
      throw new IllegalStateException("credential lease is not active");
    }
    lease.use(material -> builder.header(AUTHORIZATION, header(material)));
  }

  /**
   * Builds the header value from raw material. Package-private so a test can assert the format
   * without the value ever reaching a log or an assertion message.
   *
   * @param material the credential characters
   * @return the {@code Bearer …} header value
   */
  static String header(final char[] material) {
    final StringBuilder value = new StringBuilder(BEARER_PREFIX.length() + material.length);
    value.append(BEARER_PREFIX).append(material);
    return value.toString();
  }
}

package io.reliabilityai.gateway.provider.openai;

import io.reliabilityai.gateway.common.Preconditions;
import io.reliabilityai.gateway.dataplane.provider.api.PinnedVersion;
import java.net.URI;
import java.time.Duration;

/**
 * Immutable wiring for the OpenAI adapter (Doc 25).
 *
 * <p>Everything an operator can vary lives here, supplied by constructor — there is no environment
 * lookup, no system property and no static default endpoint. That matters for two reasons: a test
 * can point the adapter at a loopback server without touching global state, and a compromised
 * environment cannot silently redirect traffic to a different host.
 *
 * <p>Contains <b>no credential</b>. Keys arrive per-request through the secrets lease and never
 * live in configuration.
 *
 * @param baseUri the API root, e.g. {@code https://api.openai.com}
 * @param apiVersion the pinned provider API version recorded on every request
 * @param connectTimeout the TCP/TLS connect budget
 * @param requestTimeout the default per-request budget when the caller supplies no attempt budget
 * @param organization the optional {@code OpenAI-Organization} header value, or {@code null}
 * @param compressionEnabled whether to request gzip responses
 */
public record OpenAiConfiguration(
    URI baseUri,
    PinnedVersion apiVersion,
    Duration connectTimeout,
    Duration requestTimeout,
    String organization,
    boolean compressionEnabled) {

  /** The chat-completions path (Doc 25 §canonical-mapping). */
  public static final String CHAT_COMPLETIONS_PATH = "/v1/chat/completions";

  /** The embeddings path. */
  public static final String EMBEDDINGS_PATH = "/v1/embeddings";

  /** The model-list path, also used as the health probe. */
  public static final String MODELS_PATH = "/v1/models";

  /** Validates the configuration. */
  public OpenAiConfiguration {
    Preconditions.requireNonNull(baseUri, "baseUri");
    Preconditions.requireNonNull(apiVersion, "apiVersion");
    Preconditions.requireNonNull(connectTimeout, "connectTimeout");
    Preconditions.requireNonNull(requestTimeout, "requestTimeout");
    if (connectTimeout.isZero() || connectTimeout.isNegative()) {
      throw new IllegalArgumentException("connectTimeout must be positive");
    }
    if (requestTimeout.isZero() || requestTimeout.isNegative()) {
      throw new IllegalArgumentException("requestTimeout must be positive");
    }
  }

  /**
   * Standard production defaults against the public API.
   *
   * @return the default configuration
   */
  public static OpenAiConfiguration defaults() {
    return new OpenAiConfiguration(
        URI.create("https://api.openai.com"),
        new PinnedVersion("2024-10-01"),
        Duration.ofSeconds(5),
        Duration.ofSeconds(60),
        null,
        true);
  }

  /**
   * Resolves an endpoint path against the configured base.
   *
   * @param path the absolute path, beginning with {@code /}
   * @return the full endpoint URI
   */
  public URI endpoint(final String path) {
    Preconditions.requireNonBlank(path, "path");
    final String base = baseUri.toString();
    final String trimmed = base.endsWith("/") ? base.substring(0, base.length() - 1) : base;
    return URI.create(trimmed + path);
  }
}

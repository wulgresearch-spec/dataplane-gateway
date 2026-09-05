package io.reliabilityai.gateway.dataplane.app.launch;

import io.reliabilityai.gateway.canonical.identity.CanonicalModelId;
import java.net.URI;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Optional;

/**
 * The operator-supplied inputs a node needs before it can be wired, read from the process
 * environment.
 *
 * <p>Every value that differs between one deployment and the next lives here, and nothing else
 * does. Reading the environment is deliberately confined to {@link #fromEnvironment()} so the
 * wiring below it is a pure function of this record — the same settings always produce the same
 * node.
 *
 * <p>Fail-closed: a missing credential is an error at startup, never a default. A node that started
 * without its provider key would reach {@code READY} and then fail every request, which is a worse
 * outcome than refusing to start.
 *
 * <p>Nothing here names a vendor. The provider is selected by an opaque id and reached at an
 * operator-supplied URI, so the same binary fronts any adapter on the classpath.
 *
 * @param nodeId the node identity recorded on emitted facts
 * @param bindHost the ingress bind address
 * @param bindPort the ingress bind port
 * @param clientApiKey the shared secret callers present as a bearer token
 * @param providerApiKey the upstream provider credential
 * @param providerBaseUri the provider API root
 * @param providerId the adapter to select, or empty when only one is on the classpath
 * @param model the canonical model this node serves
 * @param dataDirectory the directory holding the write-ahead log and dead-letter file
 * @param connectTimeout the provider connect budget
 * @param requestTimeout the provider per-request budget
 * @param tlsRequired whether the provider transport must negotiate TLS
 */
record LaunchSettings(
    String nodeId,
    String bindHost,
    int bindPort,
    String clientApiKey,
    char[] providerApiKey,
    URI providerBaseUri,
    Optional<String> providerId,
    CanonicalModelId model,
    Path dataDirectory,
    Duration connectTimeout,
    Duration requestTimeout,
    boolean tlsRequired) {

  /**
   * Reads the settings from the process environment.
   *
   * @return the resolved settings
   * @throws LaunchConfigurationException if a required variable is absent or malformed
   */
  static LaunchSettings fromEnvironment() {
    final URI providerUri = uri();
    return new LaunchSettings(
        optional("GATEWAY_NODE_ID").orElse("gateway-local"),
        optional("GATEWAY_HOST").orElse("127.0.0.1"),
        port(),
        required("GATEWAY_API_KEY"),
        required("GATEWAY_PROVIDER_API_KEY").toCharArray(),
        providerUri,
        optional("GATEWAY_PROVIDER"),
        new CanonicalModelId(required("GATEWAY_MODEL")),
        Path.of(optional("GATEWAY_DATA_DIR").orElse("data")),
        Duration.ofSeconds(5),
        Duration.ofSeconds(60),
        "https".equals(providerUri.getScheme()));
  }

  private static URI uri() {
    final String raw = required("GATEWAY_PROVIDER_BASE_URI");
    final URI parsed;
    try {
      parsed = URI.create(raw);
    } catch (final IllegalArgumentException cause) {
      throw new LaunchConfigurationException(
          "GATEWAY_PROVIDER_BASE_URI is not a URI: " + raw, cause);
    }
    if (parsed.getScheme() == null || parsed.getHost() == null) {
      throw new LaunchConfigurationException(
          "GATEWAY_PROVIDER_BASE_URI needs a scheme and a host: " + raw);
    }
    return parsed;
  }

  private static int port() {
    final String raw = optional("GATEWAY_PORT").orElse("8080");
    final int parsed;
    try {
      parsed = Integer.parseInt(raw.trim());
    } catch (final NumberFormatException cause) {
      throw new LaunchConfigurationException("GATEWAY_PORT is not a number: " + raw, cause);
    }
    if (parsed < 1 || parsed > 65_535) {
      throw new LaunchConfigurationException("GATEWAY_PORT out of range: " + parsed);
    }
    return parsed;
  }

  private static String required(final String name) {
    return optional(name)
        .orElseThrow(
            () ->
                new LaunchConfigurationException("missing required environment variable " + name));
  }

  private static Optional<String> optional(final String name) {
    final String value = System.getenv(name);
    return value == null || value.isBlank() ? Optional.empty() : Optional.of(value.trim());
  }
}

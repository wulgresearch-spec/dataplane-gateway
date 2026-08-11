package io.reliabilityai.gateway.dataplane.ingress;

import io.reliabilityai.gateway.common.Preconditions;
import java.time.Duration;

/**
 * Immutable ingress wiring (Doc 30).
 *
 * @param host the bind address — loopback by default, because a gateway should not become publicly
 *     reachable by accident
 * @param port the bind port; {@code 0} binds an ephemeral port
 * @param maxBodyBytes the largest request body accepted before the server answers 413
 * @param requestTimeout how long one request may occupy a handler thread before the server answers
 *     504
 * @param backlog the accept backlog
 * @param shutdownGrace how long in-flight requests may finish while the server is closing
 */
public record IngressConfig(
    String host,
    int port,
    int maxBodyBytes,
    Duration requestTimeout,
    int backlog,
    Duration shutdownGrace) {

  /** The default body ceiling: 1 MiB. */
  public static final int DEFAULT_MAX_BODY_BYTES = 1024 * 1024;

  /** Validates the ingress wiring. */
  public IngressConfig {
    Preconditions.requireNonBlank(host, "host");
    if (port < 0 || port > 65_535) {
      throw new IllegalArgumentException("port must be within [0, 65535]");
    }
    if (maxBodyBytes < 1) {
      throw new IllegalArgumentException("maxBodyBytes must be >= 1");
    }
    Preconditions.requireNonNull(requestTimeout, "requestTimeout");
    if (requestTimeout.isZero() || requestTimeout.isNegative()) {
      throw new IllegalArgumentException("requestTimeout must be positive");
    }
    if (backlog < 0) {
      throw new IllegalArgumentException("backlog must be >= 0");
    }
    Preconditions.requireNonNull(shutdownGrace, "shutdownGrace");
    if (shutdownGrace.isNegative()) {
      throw new IllegalArgumentException("shutdownGrace must not be negative");
    }
  }

  /**
   * Loopback defaults suitable for a single VPS behind a reverse proxy.
   *
   * @param port the bind port
   * @return the default configuration
   */
  public static IngressConfig loopback(final int port) {
    return new IngressConfig(
        "127.0.0.1",
        port,
        DEFAULT_MAX_BODY_BYTES,
        Duration.ofSeconds(60),
        128,
        Duration.ofSeconds(10));
  }
}

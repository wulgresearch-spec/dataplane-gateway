package io.reliabilityai.gateway.dataplane.provider.api;

import io.reliabilityai.gateway.common.Preconditions;
import java.util.Map;

/**
 * A provider-neutral <em>transport envelope</em> produced by translate-out (Doc 25 §8/§9) and
 * handed to {@link ProviderTransportPort}. It is a wire-shaped carrier — an opaque endpoint
 * reference, transport headers, an opaque provider-native body, and the pinned version — and
 * carries <b>no provider-semantic meaning</b> the coordinator can read: only the provider-specific
 * {@link ProviderTranslator} authors and interprets the body bytes (the coordinator passes it
 * through opaquely). HTTP is a transport protocol, not a provider, so this envelope stays neutral
 * (AD-007).
 *
 * <p>Immutable value: headers are defensively copied and the body is defensively cloned on
 * construction and on read (Doc 11 R-005). The body is never logged or persisted.
 */
public final class TransportRequest {

  private final String endpointRef;
  private final Map<String, String> headers;
  private final byte[] body;
  private final PinnedVersion pinnedVersion;

  /**
   * Creates a transport request envelope.
   *
   * @param endpointRef the opaque endpoint/route reference (transport target)
   * @param headers the transport headers (defensively copied; never carries credentials — §31.1
   *     PL-3)
   * @param body the opaque provider-native request body (defensively cloned)
   * @param pinnedVersion the pinned provider API version (Doc 25 PA-D10)
   */
  public TransportRequest(
      final String endpointRef,
      final Map<String, String> headers,
      final byte[] body,
      final PinnedVersion pinnedVersion) {
    this.endpointRef = Preconditions.requireNonBlank(endpointRef, "endpointRef");
    this.headers = headers == null ? Map.of() : Map.copyOf(headers);
    this.pinnedVersion = Preconditions.requireNonNull(pinnedVersion, "pinnedVersion");
    Preconditions.requireNonNull(body, "body");
    this.body = body.clone();
  }

  /**
   * The opaque endpoint/route reference.
   *
   * @return the endpoint reference
   */
  public String endpointRef() {
    return endpointRef;
  }

  /**
   * The transport headers (immutable copy).
   *
   * @return the headers
   */
  public Map<String, String> headers() {
    return headers;
  }

  /**
   * A defensive clone of the opaque provider-native body.
   *
   * @return a clone of the body bytes
   */
  public byte[] body() {
    return body.clone();
  }

  /**
   * The pinned provider API version.
   *
   * @return the pinned version
   */
  public PinnedVersion pinnedVersion() {
    return pinnedVersion;
  }
}

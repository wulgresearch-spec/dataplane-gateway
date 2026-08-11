package io.reliabilityai.gateway.dataplane.provider.api;

import io.reliabilityai.gateway.common.Preconditions;
import java.util.Map;
import java.util.concurrent.Flow;

/**
 * A provider-neutral <em>transport envelope</em> returned by {@link ProviderTransportPort} and
 * consumed by translate-in (Doc 25 §8/§10/§11). A sealed pair: a fully-{@link Buffered} response or
 * a consumer-paced {@link Streamed} response. The body/frames are opaque provider-native bytes that
 * only the provider-specific {@link ProviderTranslator} interprets; the coordinator passes it
 * through opaquely. Streaming is consumer-paced (Doc 25 §31.1 PL-7): the transport reads from the
 * provider only as fast as the downstream drains.
 */
public sealed interface TransportResponse
    permits TransportResponse.Buffered, TransportResponse.Streamed {

  /**
   * The transport status code (e.g. HTTP status). Non-negative.
   *
   * @return the status code
   */
  int status();

  /**
   * The transport headers (immutable).
   *
   * @return the headers
   */
  Map<String, String> headers();

  /**
   * A fully-buffered (non-streaming) transport response. Immutable; the body is defensively cloned.
   */
  final class Buffered implements TransportResponse {

    private final int status;
    private final Map<String, String> headers;
    private final byte[] body;

    /**
     * Creates a buffered transport response.
     *
     * @param status the transport status code (non-negative)
     * @param headers the transport headers (defensively copied)
     * @param body the opaque provider-native body (defensively cloned)
     */
    public Buffered(final int status, final Map<String, String> headers, final byte[] body) {
      if (status < 0) {
        throw new IllegalArgumentException("status must be non-negative");
      }
      this.status = status;
      this.headers = headers == null ? Map.of() : Map.copyOf(headers);
      Preconditions.requireNonNull(body, "body");
      this.body = body.clone();
    }

    @Override
    public int status() {
      return status;
    }

    @Override
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
  }

  /**
   * A streaming transport response: an ordered publisher of opaque provider-native frames, consumed
   * under consumer-paced backpressure (Doc 25 §31.1 PL-7).
   */
  final class Streamed implements TransportResponse {

    private final int status;
    private final Map<String, String> headers;
    private final Flow.Publisher<byte[]> frames;

    /**
     * Creates a streaming transport response.
     *
     * @param status the transport status code (non-negative)
     * @param headers the transport headers (defensively copied)
     * @param frames the ordered publisher of opaque provider-native frames (consumer-paced)
     */
    public Streamed(
        final int status, final Map<String, String> headers, final Flow.Publisher<byte[]> frames) {
      if (status < 0) {
        throw new IllegalArgumentException("status must be non-negative");
      }
      this.status = status;
      this.headers = headers == null ? Map.of() : Map.copyOf(headers);
      this.frames = Preconditions.requireNonNull(frames, "frames");
    }

    @Override
    public int status() {
      return status;
    }

    @Override
    public Map<String, String> headers() {
      return headers;
    }

    /**
     * The ordered publisher of opaque provider-native frames (consumer-paced, Doc 25 §31.1 PL-7).
     *
     * @return the frame publisher
     */
    public Flow.Publisher<byte[]> frames() {
      return frames;
    }
  }
}

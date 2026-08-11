package io.reliabilityai.gateway.dataplane.app.binding;

import io.reliabilityai.gateway.common.Preconditions;
import io.reliabilityai.gateway.dataplane.streamguard.api.FramingType;
import io.reliabilityai.gateway.dataplane.streamguard.api.TransportSourcePort;
import io.reliabilityai.gateway.ports.ProviderTransportStream;

/**
 * Presents a {@link ProviderTransportStream} to StreamGuard as a {@link TransportSourcePort}.
 *
 * <p><b>A pass-through, not a translation.</b> Every byte StreamGuard sees is the byte array the
 * provider transport produced, forwarded unchanged. Nothing is decoded, re-encoded, re-framed or
 * synthesised — which is precisely what keeps StreamGuard's verdict a statement about the
 * provider's stream rather than about something this gateway manufactured.
 *
 * <p>The adapter exists only because the two ports live in modules that cannot see each other: the
 * StreamGuard module depends on {@code gateway-lib-ports}, so the provider-facing port had to be
 * declared below it. The composition ring is the one place that can see both, so the seam belongs
 * here. The two interfaces are deliberately the same shape; if {@code TransportSourcePort} is ever
 * moved down into {@code gateway-lib-ports}, this class disappears.
 */
public final class TransportStreamSource implements TransportSourcePort {

  private final ProviderTransportStream delegate;

  /**
   * Wraps a provider transport stream.
   *
   * @param delegate the provider's raw framed stream
   */
  public TransportStreamSource(final ProviderTransportStream delegate) {
    this.delegate = Preconditions.requireNonNull(delegate, "delegate");
  }

  @Override
  public FramingType framing() {
    return switch (delegate.framing()) {
      case SSE -> FramingType.SSE;
      case AWS_EVENT_STREAM -> FramingType.AWS_EVENT_STREAM;
      case JSON_ARRAY_CHUNKED -> FramingType.JSON_ARRAY_CHUNKED;
      case NDJSON -> FramingType.NDJSON;
    };
  }

  @Override
  public SourceChunk read() throws InterruptedException {
    final ProviderTransportStream.Chunk chunk = delegate.read();
    if (chunk instanceof ProviderTransportStream.Chunk.Data data) {
      return new SourceChunk.Data(data.bytes());
    }
    return new SourceChunk.Closed();
  }

  @Override
  public void cancel() {
    delegate.cancel();
  }
}

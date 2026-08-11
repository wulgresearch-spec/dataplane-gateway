package io.reliabilityai.gateway.dataplane.streamguard.application;

import io.reliabilityai.gateway.dataplane.streamguard.api.FramingType;
import io.reliabilityai.gateway.dataplane.streamguard.api.StreamGuardPolicy;
import io.reliabilityai.gateway.dataplane.streamguard.domain.AwsEventStreamFramingDecoder;
import io.reliabilityai.gateway.dataplane.streamguard.domain.FramingDecoder;
import io.reliabilityai.gateway.dataplane.streamguard.domain.JsonArrayFramingDecoder;
import io.reliabilityai.gateway.dataplane.streamguard.domain.NdjsonFramingDecoder;
import io.reliabilityai.gateway.dataplane.streamguard.domain.SseFramingDecoder;
import java.util.Optional;

/**
 * Selects a hardened, bounded {@link FramingDecoder} for a neutral {@link FramingType} (Doc 18 §11)
 * — keyed on the framing taxonomy, <b>never</b> on provider identity (SG-D2, AD-007). An
 * unregistered framing yields empty ⇒ the session fails closed ({@code UNSUPPORTED_FRAMING}, Doc 18
 * §11).
 *
 * <p><b>Scope:</b> all four transport framings — {@code SSE}, {@code NDJSON}, {@code
 * JSON_ARRAY_CHUNKED}, and {@code AWS_EVENT_STREAM} (binary prelude/headers/payload with prelude +
 * message CRC verification, Doc 18 §22) — are implemented here as pure, bounded, structurally-blind
 * byte decoders; none is hand-faked. The switch is total over {@link FramingType}, so a future
 * framing addition is a compile error until registered (fail closed by construction). The mandated
 * fuzz corpora (§55) are a CI-phase hardening layer over these decoders, not a precondition for
 * their logic.
 */
public final class FramingDecoderFactory {

  private FramingDecoderFactory() {}

  /**
   * Creates the bounded framing decoder for the given framing type (Doc 18 §11/§32).
   *
   * @param framing the neutral framing type
   * @param policy the transport policy (buffer bounds)
   * @return the decoder, or empty if the framing is not yet supported (fail closed)
   */
  public static Optional<FramingDecoder> create(
      final FramingType framing, final StreamGuardPolicy policy) {
    return switch (framing) {
      case SSE -> Optional.of(new SseFramingDecoder(policy.maxFramingBufferBytes()));
      case NDJSON -> Optional.of(new NdjsonFramingDecoder(policy.maxFramingBufferBytes()));
      case JSON_ARRAY_CHUNKED ->
          Optional.of(new JsonArrayFramingDecoder(policy.maxFramingBufferBytes()));
      case AWS_EVENT_STREAM ->
          Optional.of(new AwsEventStreamFramingDecoder(policy.maxFramingBufferBytes()));
    };
  }
}

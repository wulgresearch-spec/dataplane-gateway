package io.reliabilityai.gateway.dataplane.streamguard.application;

import io.reliabilityai.gateway.common.Preconditions;
import io.reliabilityai.gateway.dataplane.streamguard.api.StreamGuardPolicy;
import io.reliabilityai.gateway.dataplane.streamguard.api.StreamGuardPort;
import io.reliabilityai.gateway.dataplane.streamguard.api.TransportOutcomeSink;
import io.reliabilityai.gateway.dataplane.streamguard.api.TransportSession;
import io.reliabilityai.gateway.dataplane.streamguard.api.TransportSourcePort;
import io.reliabilityai.gateway.dataplane.streamguard.domain.FramingDecoder;
import io.reliabilityai.gateway.ports.ClockPort;
import java.util.Optional;

/**
 * The StreamGuard engine (Doc 18 §7) — the sole ingestion + transport-parse entry point
 * (SG-A1/SG-A16). Opens a fresh, isolated {@link StreamSession} per request (AD-021, Doc 18 §35 —
 * never merges sessions); selects a bounded framing decoder for the source's neutral framing (Doc
 * 18 §11), failing closed on an unsupported framing. Stateless and virtual-thread-friendly (Doc 18
 * §39): it holds no per-request state itself — all transport state lives in the per-session object.
 */
public final class StreamGuardService implements StreamGuardPort {

  private final ClockPort clock;
  private final TransportOutcomeSink outcomeSink;

  /**
   * Creates the engine against its injected seams (AD-002).
   *
   * @param clock the deterministic time seam (Doc 18 §34)
   * @param outcomeSink the content-free transport-outcome sink (Doc 18 §45); use {@link
   *     TransportOutcomeSink#NO_OP} to omit
   */
  public StreamGuardService(final ClockPort clock, final TransportOutcomeSink outcomeSink) {
    this.clock = Preconditions.requireNonNull(clock, "clock");
    this.outcomeSink = Preconditions.requireNonNull(outcomeSink, "outcomeSink");
  }

  @Override
  public TransportSession openTransport(
      final TransportSourcePort source, final StreamGuardPolicy policy) {
    Preconditions.requireNonNull(source, "source");
    Preconditions.requireNonNull(policy, "policy");
    final Optional<FramingDecoder> decoder = FramingDecoderFactory.create(source.framing(), policy);
    // decoder empty ⇒ unsupported framing: the session fails closed on first pull (Doc 18 §11).
    return new StreamSession(source, decoder.orElse(null), policy, clock, outcomeSink);
  }
}

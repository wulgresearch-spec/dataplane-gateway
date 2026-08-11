package io.reliabilityai.gateway.ports;

import io.reliabilityai.gateway.canonical.stream.StreamChunk;
import java.util.concurrent.Flow;

/**
 * The StreamGuard port (C4, Doc 18, AD-002). Wraps a provider-paced canonical stream and returns a
 * consumer-paced, guarded stream that enforces ordering, terminal-state integrity, and
 * authoritative usage extraction (Doc 18 CV-5, Doc 25 §20.1). StreamGuard is the sole authority for
 * finalized streaming usage; downstream consumers subscribe to the guarded publisher only.
 */
public interface StreamGuardPort {

  /**
   * Guards a provider-paced canonical stream, returning a consumer-paced canonical stream (Doc 18).
   *
   * @param providerStream the raw canonical stream from the adapter (Doc 25 §7)
   * @return the guarded, consumer-paced canonical stream
   */
  Flow.Publisher<StreamChunk> guard(Flow.Publisher<StreamChunk> providerStream);
}

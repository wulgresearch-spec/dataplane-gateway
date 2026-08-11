package io.reliabilityai.gateway.dataplane.streamguard.api;

/**
 * The StreamGuard inbound port (Doc 18 §7) — the sole ingestion + transport-parse entry point for
 * the streaming pipeline (Doc 18 §10/§10.1, SG-A1/SG-A16). All provider streaming traffic MUST
 * transit StreamGuard; there is no parser elsewhere and no pass-through path (Doc 18 §10.1). It
 * opens a transport session over a raw provider source under an injected policy and returns a
 * pull-based, effectively-once {@link TransportSession}.
 */
public interface StreamGuardPort {

  /**
   * Opens a transport-integrity session over the given source (Doc 18 §9).
   *
   * @param source the raw provider byte/chunk source + neutral framing (Doc 18 §10)
   * @param policy the injected transport budgets/limits (Doc 18 §32)
   * @return a pull-based transport session
   */
  TransportSession openTransport(TransportSourcePort source, StreamGuardPolicy policy);
}

package io.reliabilityai.gateway.dataplane.streamguard.api;

/**
 * The content-free transport-integrity outcome seam (Doc 18 §7/§45/§50/§51). A single terminal
 * {@link TransportVerdict} per session is fanned out to Metering (C5, transport counters), Audit
 * (C10, WORM tamper-evident), and Observability (C9, integrity metrics) — all <b>content-free</b>:
 * never bytes/payload/prompt/completion (Doc 18 §40/§44, SG-A8). A no-op default lets composition
 * omit the sink without a null seam. Best-effort: it never alters the transport verdict.
 */
public interface TransportOutcomeSink {

  /** A no-op sink (safe default). */
  TransportOutcomeSink NO_OP = verdict -> {};

  /**
   * Records the terminal transport-integrity outcome (Doc 18 §45) — content-free.
   *
   * @param verdict the terminal transport verdict (no payload content)
   */
  void onVerdict(TransportVerdict verdict);
}

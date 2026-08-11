package io.reliabilityai.gateway.dataplane.streamguard.api;

/**
 * A per-request transport session (Doc 18 §7/§9). Pull-based and effectively-once: each {@link
 * #next()} yields the next arrival-ordered {@link TransportEvent} — zero or more {@code Delta}s
 * then exactly one terminal {@code Completed} verdict (Doc 18 §29/§30). After the terminal verdict,
 * {@code next()} returns that same verdict (idempotent). Not shared across requests (AD-021);
 * virtual-thread-friendly (Doc 18 §39).
 */
public interface TransportSession {

  /**
   * Pulls the next transport event (Doc 18 §7). Blocks until a delta is ready or the verdict is
   * determined. Never emits a completed stream unless transport integrity is proven (SG-INV).
   *
   * @return the next delta or the terminal verdict
   */
  TransportEvent next();

  /**
   * Cooperatively cancels the session: cancels the provider stream, releases buffers, and yields a
   * {@code CANCELLED} verdict (Doc 18 §27). Idempotent and race-safe with completion (a cancel
   * after the terminal is a no-op returning the completed verdict).
   *
   * @param reason a content-free cancellation reason
   */
  void cancel(String reason);
}

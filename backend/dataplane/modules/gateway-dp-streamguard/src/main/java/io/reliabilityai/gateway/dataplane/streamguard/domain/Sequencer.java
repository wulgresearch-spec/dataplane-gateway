package io.reliabilityai.gateway.dataplane.streamguard.domain;

/**
 * Synthesizes a monotonic per-session transport sequence (Doc 18 §16, SG-D3). Assigned in
 * transport-arrival order as each valid decoded unit is accepted; StreamGuard emits strictly in
 * this order and never reorders emitted deltas (Doc 18 §18). A 64-bit counter with an overflow
 * guard that fails closed if ever approached (Doc 18 §16 failure modes) — overflow is a non-threat
 * within a single stream, but the guard is mandatory. Per-session state; never shared across
 * sessions (AD-021).
 */
public final class Sequencer {

  private long next;

  /**
   * Assigns the next monotonic sequence position (Doc 18 §16).
   *
   * @return the next sequence position ({@code >= 0}, strictly increasing)
   * @throws TransportIntegrityException {@code OVERFLOW} if the 64-bit sequence space is exhausted
   */
  public long nextSeq() throws TransportIntegrityException {
    if (next == Long.MAX_VALUE) {
      throw new TransportIntegrityException(
          TransportFailureClass.OVERFLOW, "sequence space exhausted");
    }
    return next++;
  }

  /**
   * The number of sequence positions assigned so far.
   *
   * @return the count of assigned positions
   */
  public long assigned() {
    return next;
  }
}

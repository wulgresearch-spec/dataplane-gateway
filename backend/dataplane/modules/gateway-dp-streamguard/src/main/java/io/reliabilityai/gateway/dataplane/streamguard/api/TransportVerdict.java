package io.reliabilityai.gateway.dataplane.streamguard.api;

import io.reliabilityai.gateway.dataplane.streamguard.domain.TransportFailureClass;

/**
 * The transport-integrity verdict (Doc 18 §6/§9/§30). {@code PROVEN} + {@code completed} is issued
 * only on an explicit transport terminal after all sequence/gap/decode/liveness checks pass (Doc 18
 * §21); any transport failure yields {@code FAILED} + a {@link TransportFailureClass} — never a
 * silent completion (SG-INV). Terminal success to the client is gated jointly with SchemaLock's
 * completion verdict (Doc 18 §30). Immutable.
 *
 * @param integrity whether transport integrity was proven or failed
 * @param failureClass the failure class (non-null iff {@code FAILED}; null iff {@code PROVEN})
 * @param deltasEmitted the count of data deltas emitted before this verdict
 * @param completed whether the stream completed on an explicit terminal ({@code true} iff {@code
 *     PROVEN})
 */
public record TransportVerdict(
    Integrity integrity,
    TransportFailureClass failureClass,
    long deltasEmitted,
    boolean completed) {

  /** Whether transport integrity was proven or failed (Doc 18 §6). */
  public enum Integrity {
    /** Transport integrity proven (explicit terminal + all checks passed). */
    PROVEN,
    /** Transport integrity could not be proven — fail closed (Doc 18 §53). */
    FAILED
  }

  /** Compact constructor enforcing the PROVEN/FAILED invariants (Doc 18 §21/§53). */
  public TransportVerdict {
    if (integrity == null) {
      throw new NullPointerException("integrity must not be null");
    }
    if (deltasEmitted < 0) {
      throw new IllegalArgumentException("deltasEmitted must be non-negative");
    }
    if (integrity == Integrity.PROVEN) {
      if (failureClass != null || !completed) {
        throw new IllegalArgumentException("PROVEN requires no failureClass and completed=true");
      }
    } else if (failureClass == null || completed) {
      throw new IllegalArgumentException("FAILED requires a failureClass and completed=false");
    }
  }

  /**
   * Creates a proven, completed verdict (Doc 18 §21/§30).
   *
   * @param deltasEmitted the count of data deltas emitted
   * @return a {@code PROVEN} verdict
   */
  public static TransportVerdict proven(final long deltasEmitted) {
    return new TransportVerdict(Integrity.PROVEN, null, deltasEmitted, true);
  }

  /**
   * Creates a fail-closed verdict (Doc 18 §53).
   *
   * @param failureClass the neutral failure class
   * @param deltasEmitted the count of data deltas emitted before failure
   * @return a {@code FAILED} verdict
   */
  public static TransportVerdict failed(
      final TransportFailureClass failureClass, final long deltasEmitted) {
    if (failureClass == null) {
      throw new NullPointerException("failureClass must not be null");
    }
    return new TransportVerdict(Integrity.FAILED, failureClass, deltasEmitted, false);
  }
}

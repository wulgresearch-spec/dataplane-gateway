package io.reliabilityai.gateway.dataplane.streamguard.domain;

import io.reliabilityai.gateway.common.Preconditions;

/**
 * An internal, checked signal that transport integrity could not be proven (Doc 18 §53, SG-INV). It
 * carries a neutral {@link TransportFailureClass} and an opaque, content-free reason — <b>never</b>
 * payload bytes (Doc 18 §40/§44). The session catches it and fails closed to a {@code FAILED}
 * {@code TransportVerdict}; it is never surfaced to a client as-is and never carries content.
 */
public final class TransportIntegrityException extends Exception {

  private static final long serialVersionUID = 1L;

  private final TransportFailureClass failureClass;

  /**
   * Creates a transport-integrity failure.
   *
   * @param failureClass the neutral failure class
   * @param reason a content-free reason (no payload bytes)
   */
  public TransportIntegrityException(
      final TransportFailureClass failureClass, final String reason) {
    super(Preconditions.requireNonNull(failureClass, "failureClass").name() + ": " + reason);
    this.failureClass = failureClass;
  }

  /**
   * The neutral transport failure class.
   *
   * @return the failure class
   */
  public TransportFailureClass failureClass() {
    return failureClass;
  }
}

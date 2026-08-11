package io.reliabilityai.gateway.dataplane.embedding.api;

/**
 * Raised when an attempt failed before the provider reached any verdict (AD-030 SS9).
 *
 * <p>The one condition an adapter throws rather than reports. A connect failure, a reset or a read
 * timeout means nothing is known about whether the work happened, and that is exactly the case the
 * retry policy must always treat as retryable. Everything the provider actually answered — a rate
 * limit, a rejection, an oversized input — comes back as a Failed outcome instead.
 *
 * <p>Messages name the condition and never the input text.
 */
public final class EmbeddingTransportException extends RuntimeException {

  private static final long serialVersionUID = 1L;

  private final EmbeddingFailure reason;

  /**
   * Creates the exception.
   *
   * @param reason the neutral failure kind
   * @param message a description that quotes no input
   */
  public EmbeddingTransportException(final EmbeddingFailure reason, final String message) {
    super(message);
    this.reason = reason;
  }

  /**
   * Creates the exception with a cause.
   *
   * @param reason the neutral failure kind
   * @param message a description that quotes no input
   * @param cause the underlying failure
   */
  public EmbeddingTransportException(
      final EmbeddingFailure reason, final String message, final Throwable cause) {
    super(message, cause);
    this.reason = reason;
  }

  /**
   * The neutral failure kind.
   *
   * @return why the attempt failed
   */
  public EmbeddingFailure reason() {
    return reason;
  }
}

package io.reliabilityai.gateway.dataplane.eventpublisher.api;

import io.reliabilityai.gateway.common.Preconditions;

/**
 * A typed broker failure (Doc 07). Carries a {@code retryable} flag distinguishing a transient
 * fault (broker unavailable, timeout, leader election) from a permanent one (invalid topic,
 * serialization). {@link BrokerPort} implementations map every provider exception to this type —
 * never a raw client exception (fail-closed, content-free).
 */
public final class BrokerException extends RuntimeException {

  private static final long serialVersionUID = 1L;
  private final boolean retryable;

  /**
   * Creates a broker exception.
   *
   * @param retryable whether the fault is transient and worth retrying
   * @param message a content-free message
   */
  public BrokerException(final boolean retryable, final String message) {
    super(Preconditions.requireNonBlank(message, "message"));
    this.retryable = retryable;
  }

  /**
   * Whether the fault is transient and retryable.
   *
   * @return {@code true} if retryable
   */
  public boolean isRetryable() {
    return retryable;
  }
}

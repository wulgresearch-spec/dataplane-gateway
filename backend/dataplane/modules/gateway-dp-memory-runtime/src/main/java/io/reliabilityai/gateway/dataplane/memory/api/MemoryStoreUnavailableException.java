package io.reliabilityai.gateway.dataplane.memory.api;

/**
 * A storage dependency could not be reached.
 *
 * <p>Thrown, never signalled by an empty result (AD-026 §12.3 A4). An empty result is
 * indistinguishable from "this tenant has no memories", so a store that reported unavailability
 * that way would present an outage as data loss — and in a write-if-absent flow would cause real
 * data loss, because the caller would write a duplicate over a record it could not see.
 */
public final class MemoryStoreUnavailableException extends RuntimeException {

  private static final long serialVersionUID = 1L;

  /**
   * Creates the exception.
   *
   * @param message what could not be reached and why
   */
  public MemoryStoreUnavailableException(final String message) {
    super(message);
  }

  /**
   * Creates the exception with a cause.
   *
   * @param message what could not be reached and why
   * @param cause the underlying failure
   */
  public MemoryStoreUnavailableException(final String message, final Throwable cause) {
    super(message, cause);
  }
}

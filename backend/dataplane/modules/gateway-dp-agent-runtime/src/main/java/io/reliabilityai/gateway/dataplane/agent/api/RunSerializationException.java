package io.reliabilityai.gateway.dataplane.agent.api;

/**
 * A journal record could not be encoded or decoded.
 *
 * <p>Never recovered from by guessing. A partially-decoded event would put a fabricated fact into a
 * run's history, and every downstream guarantee — replay, audit, cost attribution — is built on the
 * history being exactly what was written.
 */
public final class RunSerializationException extends RuntimeException {

  private static final long serialVersionUID = 1L;

  /**
   * Creates the exception.
   *
   * @param message what could not be encoded or decoded
   */
  public RunSerializationException(final String message) {
    super(message);
  }

  /**
   * Creates the exception with a cause.
   *
   * @param message what could not be encoded or decoded
   * @param cause the underlying failure
   */
  public RunSerializationException(final String message, final Throwable cause) {
    super(message, cause);
  }
}

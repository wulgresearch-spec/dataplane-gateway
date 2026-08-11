package io.reliabilityai.gateway.dataplane.app;

/**
 * Thrown when the data-plane deployable cannot activate because one or more mandatory stages are
 * unbound (Doc 06 §8, AD-018). Activation is refused — the deployable never starts a bypassed
 * pipeline.
 */
public final class StartupValidationException extends RuntimeException {

  private static final long serialVersionUID = 1L;

  /**
   * Creates the exception with a deterministic diagnostic message.
   *
   * @param message the deterministic, content-free diagnostic
   */
  public StartupValidationException(final String message) {
    super(message);
  }
}

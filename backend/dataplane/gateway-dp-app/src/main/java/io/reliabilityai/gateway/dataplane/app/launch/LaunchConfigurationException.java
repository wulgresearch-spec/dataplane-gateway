package io.reliabilityai.gateway.dataplane.app.launch;

/** Raised when the environment does not describe a startable node. */
final class LaunchConfigurationException extends RuntimeException {

  private static final long serialVersionUID = 1L;

  /**
   * Creates an exception with a message.
   *
   * @param message what was missing or malformed
   */
  LaunchConfigurationException(final String message) {
    super(message);
  }

  /**
   * Creates an exception with a message and cause.
   *
   * @param message what was missing or malformed
   * @param cause the underlying parse failure
   */
  LaunchConfigurationException(final String message, final Throwable cause) {
    super(message, cause);
  }
}

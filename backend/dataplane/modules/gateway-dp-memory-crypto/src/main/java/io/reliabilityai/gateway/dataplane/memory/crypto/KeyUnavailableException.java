package io.reliabilityai.gateway.dataplane.memory.crypto;

/**
 * Raised when key material cannot be loaded at all (AD-028 §6).
 *
 * <p>The message is deliberately coarse. It names the version identifier — a label, not a secret —
 * and nothing else. It never carries key bytes, plaintext, ciphertext, a file path's contents or a
 * decoded value, because exception messages end up in logs, in bug reports and in HTTP responses
 * that were never meant to carry them.
 */
public final class KeyUnavailableException extends RuntimeException {

  private static final long serialVersionUID = 1L;

  /**
   * Creates the exception.
   *
   * @param message a description that contains no key material
   */
  public KeyUnavailableException(final String message) {
    super(message);
  }

  /**
   * Creates the exception with a cause.
   *
   * <p>The cause is retained because a missing file or an unreadable directory is genuinely useful
   * to an operator. Implementations must not pass a cause whose own message embeds key material.
   *
   * @param message a description that contains no key material
   * @param cause the underlying failure
   */
  public KeyUnavailableException(final String message, final Throwable cause) {
    super(message, cause);
  }
}

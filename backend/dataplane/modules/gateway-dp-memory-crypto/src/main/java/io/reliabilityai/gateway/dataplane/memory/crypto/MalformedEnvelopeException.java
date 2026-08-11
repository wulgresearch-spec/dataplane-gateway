package io.reliabilityai.gateway.dataplane.memory.crypto;

/**
 * Raised while parsing a sealed body that is not a well-formed envelope (AD-028 §4).
 *
 * <p>Package-private on purpose. It never escapes the cipher: {@code unseal} catches it and returns
 * an empty result, so a caller cannot distinguish "this is not an envelope" from "this envelope
 * does not verify". Letting the two be told apart would hand an attacker a structural oracle for
 * free.
 *
 * <p>Messages describe the <em>shape</em> that was wrong and never echo the input, because the
 * input is ciphertext and an exception message is a log line.
 */
final class MalformedEnvelopeException extends RuntimeException {

  private static final long serialVersionUID = 1L;

  /**
   * Creates the exception.
   *
   * @param message a description of the structural fault that echoes no input
   */
  MalformedEnvelopeException(final String message) {
    super(message);
  }
}

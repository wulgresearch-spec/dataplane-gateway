package io.reliabilityai.gateway.dataplane.secrets.adapter;

import io.reliabilityai.gateway.common.Preconditions;

/**
 * A typed, content-free error from the KMS / envelope-decryption path (Doc 26 §21). It never
 * carries key material, ciphertext, or a provider exception message — only a bounded {@link
 * Reason}. All failures are fail-closed: the caller maps this to {@code CredentialUnavailable} (Doc
 * 26 §21.2).
 */
public final class KmsException extends RuntimeException {

  private static final long serialVersionUID = 1L;

  /** The content-free failure category. */
  public enum Reason {
    /** The data key could not be unwrapped (KMS unavailable / access denied / not found). */
    KEY_UNAVAILABLE,
    /** AEAD authentication failed — wrong key, tampered ciphertext, or wrong encryption context. */
    INTEGRITY_FAILURE,
    /** The envelope (nonce/ciphertext/key sizes) is structurally invalid. */
    INVALID_ENVELOPE,
    /** Decryption failed for another cryptographic reason. */
    DECRYPT_FAILURE
  }

  /**
   * The failure category.
   *
   * <p>Deliberately <b>not</b> {@code transient}. It was, and that was the wrong instinct applied
   * to the right rule: Doc 26 §17.1 forbids serializing credential material, and {@link Reason} is
   * a four-constant enum documented as carrying none — no key material, no ciphertext, no provider
   * message. Marking it transient bought no secrecy and cost an invariant, because {@code
   * KmsException} is serializable whether or not anyone wants it to be ({@code Throwable implements
   * Serializable}) and declares a {@code serialVersionUID}. A transient field with no {@code
   * readObject} to restore it deserializes to {@code null}, so a round-tripped exception would
   * return {@code null} from {@link #reason()} — which the constructor guarantees can never happen
   * — and every caller that switches on the reason would fail on the error path, where it is
   * hardest to notice.
   */
  private final Reason reason;

  /**
   * Creates a typed KMS exception with a content-free reason.
   *
   * @param reason the failure category
   * @param message a content-free, non-sensitive message
   */
  public KmsException(final Reason reason, final String message) {
    super(message);
    this.reason = Preconditions.requireNonNull(reason, "reason");
  }

  /**
   * The content-free failure category.
   *
   * @return the reason
   */
  public Reason reason() {
    return reason;
  }
}

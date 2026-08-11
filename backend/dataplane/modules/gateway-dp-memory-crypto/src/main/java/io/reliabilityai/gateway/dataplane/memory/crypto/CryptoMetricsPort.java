package io.reliabilityai.gateway.dataplane.memory.crypto;

/**
 * Observability for the cipher (AD-028 §8).
 *
 * <p>Every parameter on every method here is either a key <em>version label</em>, a size or an
 * enumerated reason. None of them is key material, plaintext, ciphertext, a nonce or a tag, and
 * none of them can be widened to carry one without changing this interface — which is the point.
 * Metrics are the most casually exported surface a service has; if key material can reach a metric,
 * it will eventually reach a dashboard shared with someone who should not have it.
 *
 * <p>The failure reasons are reported <em>here</em> and deliberately not to the caller of {@code
 * unseal}, which sees only an empty result. An operator needs to tell a retired key from a forged
 * tag; a tenant supplying ciphertext must not be handed that distinction, because a decryption
 * oracle that answers "wrong key" versus "bad tag" is a decryption oracle.
 */
public interface CryptoMetricsPort {

  /** Why a sealed body could not be opened. */
  enum OpenFailure {
    /**
     * The recorded key version is not one this provider can load — retired, or another deployment.
     */
    UNKNOWN_KEY_VERSION,
    /** The version inside the envelope disagrees with the key reference stored beside it. */
    KEY_REF_MISMATCH,
    /** The envelope is not a well-formed envelope: bad magic, bad framing, truncated. */
    MALFORMED_ENVELOPE,
    /**
     * The tag did not verify: modified bytes, a swapped record, the wrong tenant, or the wrong key.
     */
    AUTHENTICATION_FAILED
  }

  /**
   * A body was sealed.
   *
   * @param keyVersion the version it was sealed under
   * @param plaintextBytes how large the body was, for capacity work
   */
  void sealed(String keyVersion, int plaintextBytes);

  /**
   * A body was opened.
   *
   * @param keyVersion the version it was sealed under
   */
  void opened(String keyVersion);

  /**
   * A body could not be opened.
   *
   * @param keyVersion the version recorded against it, or {@code "unknown"} when unparseable
   * @param reason which check refused it
   */
  void openFailed(String keyVersion, OpenFailure reason);

  /**
   * A freshly generated nonce matched one recently used under the same key.
   *
   * <p>Under a healthy generator this is astronomically rare, so a non-zero rate here is the
   * earliest signal that the random source has degraded — a cloned VM image, an exhausted entropy
   * pool, a misconfigured provider. It deserves an alert, not a dashboard tile.
   *
   * @param keyVersion the version whose nonce space is affected
   */
  void nonceCollisionSuspected(String keyVersion);

  /**
   * A key version has wrapped more data keys than its safe nonce budget allows.
   *
   * @param keyVersion the version that must be rotated
   */
  void wrapBudgetExceeded(String keyVersion);

  /**
   * The primary key version changed.
   *
   * @param fromVersion the previous primary
   * @param toVersion the new primary
   */
  void rotated(String fromVersion, String toVersion);

  /**
   * A record was re-sealed under a newer key version.
   *
   * @param fromVersion the version it was sealed under
   * @param toVersion the version it is now sealed under
   */
  void rewrapped(String fromVersion, String toVersion);
}

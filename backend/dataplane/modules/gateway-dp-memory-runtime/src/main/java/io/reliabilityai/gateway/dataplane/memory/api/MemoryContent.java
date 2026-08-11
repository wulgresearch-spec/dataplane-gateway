package io.reliabilityai.gateway.dataplane.memory.api;

import io.reliabilityai.gateway.common.Preconditions;
import java.util.Optional;

/**
 * The stored body of a memory, sealed or in the clear.
 *
 * <p>Content is either {@code PLAIN} or {@code SEALED}. A sealed body carries ciphertext and a
 * <em>key reference</em>, never a key: MEM-8 keeps key material entirely outside this runtime, so
 * the worst a memory-plane compromise yields is ciphertext and the name of a key held elsewhere.
 *
 * <p>The digest is always over the <b>plaintext</b>, computed before sealing. Digesting ciphertext
 * would make integrity verification impossible for anyone who could not decrypt, and would change
 * every digest on key rotation even though no content changed.
 *
 * @param body the plaintext, or the ciphertext when sealed
 * @param digest a stable digest of the plaintext, used for integrity (MEM-24) and audit
 * @param sealed whether {@code body} is ciphertext
 * @param keyRef the key reference needed to unseal, present only when sealed
 * @param sizeBytes the plaintext size, retained so bounds and metering survive sealing
 */
public record MemoryContent(
    String body, String digest, boolean sealed, Optional<String> keyRef, int sizeBytes) {

  /**
   * The largest plaintext a single memory may hold. Beyond this a write is refused, never
   * truncated.
   */
  public static final int MAX_BYTES = 256 * 1024;

  /**
   * Validates the content.
   *
   * @param body the plaintext or ciphertext
   * @param digest the plaintext digest
   * @param sealed whether the body is ciphertext
   * @param keyRef the key reference, required when sealed
   * @param sizeBytes the plaintext size
   */
  public MemoryContent {
    Preconditions.requireNonNull(body, "body");
    Preconditions.requireNonBlank(digest, "digest");
    Preconditions.requireNonNull(keyRef, "keyRef");
    Preconditions.requireNonNegative(sizeBytes, "sizeBytes");
    // Sealed with no key reference is unreadable forever. Refusing to construct it is the only way
    // to
    // guarantee that a sealed record can always, in principle, be opened by whoever holds the key.
    if (sealed && keyRef.isEmpty()) {
      throw new IllegalArgumentException("sealed content requires a key reference");
    }
    if (!sealed && keyRef.isPresent()) {
      throw new IllegalArgumentException("plain content must not carry a key reference");
    }
  }

  /**
   * Creates unsealed content.
   *
   * @param body the plaintext
   * @param digest the plaintext digest
   * @return the content
   */
  public static MemoryContent plain(final String body, final String digest) {
    return new MemoryContent(body, digest, false, Optional.empty(), body.length());
  }

  /**
   * Creates sealed content, preserving the plaintext digest and size.
   *
   * @param ciphertext the sealed body
   * @param plaintextDigest the digest of the plaintext, computed before sealing
   * @param keyRef the reference to the key that will unseal it
   * @param plaintextSize the plaintext size
   * @return the content
   */
  public static MemoryContent sealed(
      final String ciphertext,
      final String plaintextDigest,
      final String keyRef,
      final int plaintextSize) {
    Preconditions.requireNonBlank(keyRef, "keyRef");
    return new MemoryContent(ciphertext, plaintextDigest, true, Optional.of(keyRef), plaintextSize);
  }

  /**
   * Reports whether the plaintext is readable without unsealing.
   *
   * @return true when the content is not sealed
   */
  public boolean readable() {
    return !sealed;
  }

  /**
   * Returns the plaintext when it is available in the clear.
   *
   * <p>Returns empty rather than the ciphertext for sealed content. Handing back ciphertext from a
   * method named for plaintext is how ciphertext ends up in a prompt.
   *
   * @return the plaintext, or empty when sealed
   */
  public Optional<String> plaintext() {
    return sealed ? Optional.empty() : Optional.of(body);
  }
}

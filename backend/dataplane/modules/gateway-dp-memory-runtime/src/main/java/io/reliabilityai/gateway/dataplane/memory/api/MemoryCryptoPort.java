package io.reliabilityai.gateway.dataplane.memory.api;

import io.reliabilityai.gateway.common.Preconditions;
import java.util.Optional;

/**
 * Seals and unseals memory content (MEM-8).
 *
 * <p><b>The runtime holds no key material.</b> It hands plaintext to this port and receives
 * ciphertext and a <em>key reference</em>; it never sees a key, never chooses an algorithm and
 * never rotates anything. The worst a memory-plane compromise yields is ciphertext and the name of
 * a key held elsewhere — which is the entire reason custody belongs to C14 Secrets rather than
 * here.
 *
 * <p>Algorithm, key derivation, rotation and envelope structure are the adapter's business. This
 * port cannot express any of them, deliberately: a port that could would be a port that constrains
 * which cryptography is acceptable, and this module is not qualified to impose that constraint.
 */
public interface MemoryCryptoPort {

  /**
   * A sealed body and the reference needed to open it.
   *
   * @param ciphertext the sealed body
   * @param keyRef the reference to the key that will unseal it
   */
  record Sealed(String ciphertext, String keyRef) {

    /**
     * Validates the pair.
     *
     * @param ciphertext the sealed body
     * @param keyRef the key reference
     */
    public Sealed {
      Preconditions.requireNonNull(ciphertext, "ciphertext");
      Preconditions.requireNonBlank(keyRef, "keyRef");
    }
  }

  /**
   * Seals content for a scope.
   *
   * <p>The scope is passed so an adapter may choose a per-tenant key. It is not a hint the runtime
   * verifies — the runtime cannot tell one key from another and must not try.
   *
   * @param scope whose content this is
   * @param plaintext the body to seal
   * @return the ciphertext and its key reference
   * @throws MemoryStoreUnavailableException when the provider cannot be reached, so a write
   *     requiring sealing is refused rather than quietly stored in the clear
   */
  Sealed seal(MemoryScope scope, String plaintext);

  /**
   * Unseals content.
   *
   * <p>Returns empty rather than throwing when the key is unavailable or unusable. A record that
   * cannot be opened comes back marked undecryptable (AD-026 §11): the ciphertext is not a leak,
   * and failing the whole read would lose availability for nothing.
   *
   * @param scope whose content this is
   * @param ciphertext the sealed body
   * @param keyRef the key reference recorded alongside it
   * @return the plaintext, or empty when it cannot be opened
   */
  Optional<String> unseal(MemoryScope scope, String ciphertext, String keyRef);
}

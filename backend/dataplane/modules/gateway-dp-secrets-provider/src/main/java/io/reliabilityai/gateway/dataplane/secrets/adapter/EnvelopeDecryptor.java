package io.reliabilityai.gateway.dataplane.secrets.adapter;

import io.reliabilityai.gateway.common.Preconditions;
import java.security.GeneralSecurityException;
import java.util.Arrays;
import javax.crypto.AEADBadTagException;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * Authenticated envelope decryption for credential material (Doc 26 §17.1, AD-022 / IR-7). Uses
 * <b>only vetted JDK cryptography</b> — AES-256-GCM via {@link Cipher} ({@code AES/GCM/NoPadding},
 * 128-bit tag) — no bespoke cryptography. The AES data key must already be <b>unwrapped by KMS</b>
 * (that call is the only cloud dependency, behind {@link KmsUnwrapPort}); this class assumes it is
 * given the plaintext data key and performs the local decrypt.
 *
 * <p><b>Security.</b> GCM is an AEAD cipher: any tamper of the ciphertext, wrong key, or wrong AAD
 * (encryption context) causes {@link AEADBadTagException} → a fail-closed {@link KmsException}
 * ({@link KmsException.Reason#INTEGRITY_FAILURE}). The decrypted plaintext is delivered to a scoped
 * {@link PlaintextConsumer} and then <b>deterministically zeroized</b> — it is never returned as a
 * {@code byte[]} the caller could retain (Doc 26 §17.1). Stateless and thread-safe (a fresh {@link
 * Cipher} per call; VT-safe, no locks).
 *
 * <p><b>Honest limit (MSC-5).</b> {@link SecretKeySpec} copies the data-key bytes internally; the
 * JVM copy is not zeroizable, so absolute erasure of every transient copy is not guaranteed on a
 * managed runtime. This is runtime memory hygiene, not cryptographic destruction (C14/KMS-owned).
 */
public final class EnvelopeDecryptor {

  private static final String TRANSFORM = "AES/GCM/NoPadding";
  private static final int GCM_TAG_BITS = 128;
  private static final int GCM_NONCE_BYTES = 12;
  private static final int AES_256_KEY_BYTES = 32;
  private static final int AES_128_KEY_BYTES = 16;

  /**
   * Scoped consumer of decrypted plaintext; the array is owned by the decryptor and zeroized after.
   */
  @FunctionalInterface
  public interface PlaintextConsumer {
    /**
     * Applies the decrypted plaintext within a bounded scope; the array MUST NOT be retained.
     *
     * @param plaintext the decrypted credential material (zeroized when this returns)
     */
    void accept(char[] plaintext);
  }

  /**
   * Decrypts the envelope and applies the plaintext to the consumer, then zeroizes it (Doc 26
   * §17.1).
   *
   * @param dataKey the plaintext AES data key (16 or 32 bytes; unwrapped by KMS)
   * @param nonce the 12-byte GCM nonce
   * @param ciphertext the AES-GCM ciphertext including the authentication tag
   * @param aad the additional authenticated data (encryption context), or {@code null}/empty
   * @param consumer the scoped plaintext consumer
   * @throws KmsException fail-closed on any invalid input or authentication/decryption failure
   */
  public void decryptInto(
      final byte[] dataKey,
      final byte[] nonce,
      final byte[] ciphertext,
      final byte[] aad,
      final PlaintextConsumer consumer) {
    Preconditions.requireNonNull(dataKey, "dataKey");
    Preconditions.requireNonNull(nonce, "nonce");
    Preconditions.requireNonNull(ciphertext, "ciphertext");
    Preconditions.requireNonNull(consumer, "consumer");
    if (dataKey.length != AES_256_KEY_BYTES && dataKey.length != AES_128_KEY_BYTES) {
      throw new KmsException(KmsException.Reason.INVALID_ENVELOPE, "invalid data key length");
    }
    if (nonce.length != GCM_NONCE_BYTES) {
      throw new KmsException(KmsException.Reason.INVALID_ENVELOPE, "invalid nonce length");
    }
    if (ciphertext.length <= GCM_TAG_BITS / 8) {
      throw new KmsException(KmsException.Reason.INVALID_ENVELOPE, "ciphertext too short");
    }

    final SecretKeySpec key = new SecretKeySpec(dataKey, "AES");
    byte[] plaintextBytes = null;
    try {
      final Cipher cipher = Cipher.getInstance(TRANSFORM);
      cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(GCM_TAG_BITS, nonce));
      if (aad != null && aad.length > 0) {
        cipher.updateAAD(aad);
      }
      plaintextBytes = cipher.doFinal(ciphertext);
      final char[] plaintext = toChars(plaintextBytes);
      try {
        consumer.accept(plaintext);
      } finally {
        Arrays.fill(plaintext, (char) 0);
      }
    } catch (final AEADBadTagException e) {
      // Wrong key, tampered ciphertext, or wrong encryption context — indistinguishable by design.
      throw new KmsException(
          KmsException.Reason.INTEGRITY_FAILURE, "envelope authentication failed");
    } catch (final GeneralSecurityException e) {
      throw new KmsException(KmsException.Reason.DECRYPT_FAILURE, "envelope decryption failed");
    } finally {
      if (plaintextBytes != null) {
        Arrays.fill(plaintextBytes, (byte) 0);
      }
    }
  }

  private static char[] toChars(final byte[] bytes) {
    // Credential material (API keys, bearer/JWT tokens, base64 secrets) is ASCII/Latin-1; this
    // exact
    // byte→char mapping avoids minting an immutable String (Doc 26 MSC-1). Non-ASCII secrets would
    // require a CharsetDecoder; credentials are ASCII by construction.
    final char[] chars = new char[bytes.length];
    for (int i = 0; i < bytes.length; i++) {
      chars[i] = (char) (bytes[i] & 0xFF);
    }
    return chars;
  }
}

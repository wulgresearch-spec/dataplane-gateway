package io.reliabilityai.gateway.dataplane.secrets.adapter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import org.junit.jupiter.api.Test;

/**
 * Real AES-256-GCM tests for the envelope decryptor (Doc 26 §17.1). Encrypts with genuine JDK
 * crypto and asserts authenticated decryption + fail-closed rejection of tampering, wrong key, and
 * wrong AAD.
 */
class EnvelopeDecryptorTest {

  private static final byte[] NONCE =
      new byte[12]; // fixed nonce is fine for a one-shot key in tests
  private static final byte[] AAD =
      "region=us-east-1;tenant=org-1".getBytes(StandardCharsets.UTF_8);
  private static final byte[] PLAINTEXT =
      "sk-live-credential-material".getBytes(StandardCharsets.UTF_8);

  private final EnvelopeDecryptor decryptor = new EnvelopeDecryptor();

  private static SecretKey aes256() throws Exception {
    final KeyGenerator gen = KeyGenerator.getInstance("AES");
    gen.init(256);
    return gen.generateKey();
  }

  private static byte[] encrypt(final SecretKey key, final byte[] aad, final byte[] plaintext)
      throws Exception {
    final Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
    cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(128, NONCE));
    if (aad != null && aad.length > 0) {
      cipher.updateAAD(aad);
    }
    return cipher.doFinal(plaintext);
  }

  @Test
  void decryptsAuthenticatedCiphertext() throws Exception {
    final SecretKey key = aes256();
    final byte[] ciphertext = encrypt(key, AAD, PLAINTEXT);
    final char[][] observed = new char[1][];
    decryptor.decryptInto(key.getEncoded(), NONCE, ciphertext, AAD, pt -> observed[0] = pt.clone());
    assertThat(new String(observed[0])).isEqualTo("sk-live-credential-material");
  }

  @Test
  void failsClosedOnTamperedCiphertext() throws Exception {
    final SecretKey key = aes256();
    final byte[] ciphertext = encrypt(key, AAD, PLAINTEXT);
    ciphertext[0] ^= 0x01; // flip a bit
    assertThatThrownBy(
            () -> decryptor.decryptInto(key.getEncoded(), NONCE, ciphertext, AAD, pt -> {}))
        .isInstanceOf(KmsException.class)
        .extracting(e -> ((KmsException) e).reason())
        .isEqualTo(KmsException.Reason.INTEGRITY_FAILURE);
  }

  @Test
  void failsClosedOnWrongEncryptionContext() throws Exception {
    final SecretKey key = aes256();
    final byte[] ciphertext = encrypt(key, AAD, PLAINTEXT);
    final byte[] wrongAad = "region=eu-west-1".getBytes(StandardCharsets.UTF_8);
    assertThatThrownBy(
            () -> decryptor.decryptInto(key.getEncoded(), NONCE, ciphertext, wrongAad, pt -> {}))
        .isInstanceOf(KmsException.class)
        .extracting(e -> ((KmsException) e).reason())
        .isEqualTo(KmsException.Reason.INTEGRITY_FAILURE);
  }

  @Test
  void failsClosedOnWrongKey() throws Exception {
    final SecretKey key = aes256();
    final SecretKey other = aes256();
    final byte[] ciphertext = encrypt(key, AAD, PLAINTEXT);
    assertThatThrownBy(
            () -> decryptor.decryptInto(other.getEncoded(), NONCE, ciphertext, AAD, pt -> {}))
        .isInstanceOf(KmsException.class)
        .extracting(e -> ((KmsException) e).reason())
        .isEqualTo(KmsException.Reason.INTEGRITY_FAILURE);
  }

  @Test
  void rejectsInvalidEnvelopeSizes() throws Exception {
    final byte[] key = aes256().getEncoded();
    final byte[] ciphertext = encrypt(aes256(), AAD, PLAINTEXT);
    assertThatThrownBy(() -> decryptor.decryptInto(new byte[7], NONCE, ciphertext, AAD, pt -> {}))
        .isInstanceOf(KmsException.class)
        .extracting(e -> ((KmsException) e).reason())
        .isEqualTo(KmsException.Reason.INVALID_ENVELOPE);
    assertThatThrownBy(() -> decryptor.decryptInto(key, new byte[8], ciphertext, AAD, pt -> {}))
        .isInstanceOf(KmsException.class)
        .extracting(e -> ((KmsException) e).reason())
        .isEqualTo(KmsException.Reason.INVALID_ENVELOPE);
    assertThatThrownBy(() -> decryptor.decryptInto(key, NONCE, new byte[4], AAD, pt -> {}))
        .isInstanceOf(KmsException.class)
        .extracting(e -> ((KmsException) e).reason())
        .isEqualTo(KmsException.Reason.INVALID_ENVELOPE);
  }

  @Test
  void rejectsCiphertextExactlyAtTheTagLength() throws Exception {
    // GCM_TAG_BITS / 8 == 16. A ciphertext of exactly the tag length carries no plaintext at all,
    // so it must be refused structurally. rejectsInvalidEnvelopeSizes only reaches byte[4], which
    // leaves the boundary itself untested: a `<=` that drifted to `<` would let this envelope
    // reach Cipher and surface as INTEGRITY_FAILURE, losing the real (structural) reason.
    final byte[] key = aes256().getEncoded();

    assertThatThrownBy(() -> decryptor.decryptInto(key, NONCE, new byte[16], AAD, pt -> {}))
        .isInstanceOf(KmsException.class)
        .hasMessageContaining("ciphertext too short")
        .extracting(e -> ((KmsException) e).reason())
        .isEqualTo(KmsException.Reason.INVALID_ENVELOPE);
  }

  @Test
  void plaintextIsZeroizedAfterConsumerReturns() throws Exception {
    final SecretKey key = aes256();
    final byte[] ciphertext = encrypt(key, AAD, PLAINTEXT);
    final char[][] captured = new char[1][];
    decryptor.decryptInto(key.getEncoded(), NONCE, ciphertext, AAD, pt -> captured[0] = pt);
    // The decryptor zeroizes the array it passed to the consumer after the callback returns.
    assertThat(captured[0]).containsOnly((char) 0);
  }
}

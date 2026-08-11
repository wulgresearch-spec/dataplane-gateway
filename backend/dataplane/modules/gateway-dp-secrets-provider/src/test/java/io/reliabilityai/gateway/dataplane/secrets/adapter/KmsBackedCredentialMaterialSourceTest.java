package io.reliabilityai.gateway.dataplane.secrets.adapter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.reliabilityai.gateway.canonical.identity.SnapshotVersion;
import io.reliabilityai.gateway.canonical.identity.TenantScope;
import io.reliabilityai.gateway.canonical.snapshot.CredentialSnapshotRef;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import org.junit.jupiter.api.Test;

/**
 * End-to-end envelope-encryption tests for the KMS-backed credential source (Doc 26 §7). Uses
 * genuine AES-256-GCM; only the KMS <b>unwrap</b> is a test double (the declared cloud seam).
 * Proves the full unwrap→decrypt→copy path and fail-closed behaviour on KMS/integrity failure.
 */
class KmsBackedCredentialMaterialSourceTest {

  private static final byte[] NONCE = new byte[12];
  private static final byte[] AAD =
      "region=us-east-1;tenant=org-1".getBytes(StandardCharsets.UTF_8);
  private static final String SECRET = "sk-live-credential-xyz";

  private final EnvelopeDecryptor decryptor = new EnvelopeDecryptor();

  private static CredentialSnapshotRef ref() {
    return new CredentialSnapshotRef(
        new SnapshotVersion("credential", "v1"),
        TenantScope.of("org-1", "tenant-1"),
        Instant.parse("2999-01-01T00:00:00Z"));
  }

  private static SecretKey aes256() throws Exception {
    final KeyGenerator gen = KeyGenerator.getInstance("AES");
    gen.init(256);
    return gen.generateKey();
  }

  private static byte[] encrypt(final SecretKey key) throws Exception {
    final Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
    cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(128, NONCE));
    cipher.updateAAD(AAD);
    return cipher.doFinal(SECRET.getBytes(StandardCharsets.UTF_8));
  }

  private EncryptedCredentialSnapshot snapshot(final SecretKey dataKey, final byte[] ciphertext) {
    // In these tests the "wrapped" data key is the raw key bytes; the fake KMS returns them
    // unchanged.
    return new EncryptedCredentialSnapshot(
        ref(), dataKey.getEncoded(), NONCE, ciphertext, AAD, SECRET.length());
  }

  /**
   * A test KMS that returns the wrapped bytes as the data key (the real KMS Decrypt is the seam).
   */
  private static final KmsUnwrapPort PASS_THROUGH_KMS =
      (wrapped, context, consumer) -> consumer.accept(wrapped.clone());

  @Test
  void unwrapsDecryptsAndCopiesCredential() throws Exception {
    final SecretKey key = aes256();
    final var source =
        new KmsBackedCredentialMaterialSource(
            snapshot(key, encrypt(key)), PASS_THROUGH_KMS, decryptor);
    assertThat(source.length()).isEqualTo(SECRET.length());
    final char[] dest = new char[source.length()];
    source.copyInto(dest);
    assertThat(new String(dest)).isEqualTo(SECRET);
  }

  @Test
  void failsClosedWhenKmsUnwrapFails() throws Exception {
    final SecretKey key = aes256();
    final KmsUnwrapPort failing =
        (wrapped, context, consumer) -> {
          throw new KmsException(KmsException.Reason.KEY_UNAVAILABLE, "kms unavailable");
        };
    final var source =
        new KmsBackedCredentialMaterialSource(snapshot(key, encrypt(key)), failing, decryptor);
    assertThatThrownBy(() -> source.copyInto(new char[source.length()]))
        .isInstanceOf(KmsException.class)
        .extracting(e -> ((KmsException) e).reason())
        .isEqualTo(KmsException.Reason.KEY_UNAVAILABLE);
  }

  @Test
  void failsClosedOnTamperedCiphertext() throws Exception {
    final SecretKey key = aes256();
    final byte[] ciphertext = encrypt(key);
    ciphertext[0] ^= 0x01;
    final var source =
        new KmsBackedCredentialMaterialSource(
            snapshot(key, ciphertext), PASS_THROUGH_KMS, decryptor);
    assertThatThrownBy(() -> source.copyInto(new char[source.length()]))
        .isInstanceOf(KmsException.class)
        .extracting(e -> ((KmsException) e).reason())
        .isEqualTo(KmsException.Reason.INTEGRITY_FAILURE);
  }

  @Test
  void rejectsTooSmallDestination() throws Exception {
    final SecretKey key = aes256();
    final var source =
        new KmsBackedCredentialMaterialSource(
            snapshot(key, encrypt(key)), PASS_THROUGH_KMS, decryptor);
    assertThatThrownBy(() -> source.copyInto(new char[1]))
        .isInstanceOf(IllegalArgumentException.class);
  }
}

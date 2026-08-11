package io.reliabilityai.gateway.dataplane.secrets.adapter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Base64;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.Test;

/**
 * Real AES-256-GCM tests for the single-VPS {@link LocalMasterKeyKmsUnwrapAdapter} (Doc 26 IR-7).
 * Wraps data keys with genuine JDK crypto and asserts authenticated unwrap, fail-closed rejection
 * of tampering / wrong key / wrong context, scoped delivery + zeroization, and composition with
 * {@link EnvelopeDecryptor} — the full on-VPS envelope flow with no cloud KMS.
 */
class LocalMasterKeyKmsUnwrapAdapterTest {

  private static final byte[] NONCE =
      new byte[12]; // fixed nonce is fine for one-shot keys in tests
  private static final byte[] CONTEXT =
      "region=us-east-1;tenant=org-1".getBytes(StandardCharsets.UTF_8);

  private static byte[] filled(final byte value, final int length) {
    final byte[] out = new byte[length];
    Arrays.fill(out, value);
    return out;
  }

  private static byte[] masterKey(final byte value) {
    return filled(value, 32);
  }

  /** Wraps a data key: {@code nonce || AES-256-GCM(masterKey, aad=context, plaintext=dataKey)}. */
  private static byte[] wrap(final byte[] masterKey, final byte[] context, final byte[] dataKey)
      throws Exception {
    final Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
    cipher.init(
        Cipher.ENCRYPT_MODE, new SecretKeySpec(masterKey, "AES"), new GCMParameterSpec(128, NONCE));
    if (context != null && context.length > 0) {
      cipher.updateAAD(context);
    }
    final byte[] ciphertext = cipher.doFinal(dataKey);
    final byte[] out = new byte[NONCE.length + ciphertext.length];
    System.arraycopy(NONCE, 0, out, 0, NONCE.length);
    System.arraycopy(ciphertext, 0, out, NONCE.length, ciphertext.length);
    return out;
  }

  @Test
  void unwrapsWrappedDataKeyToConsumer() throws Exception {
    final byte[] mk = masterKey((byte) 0x11);
    final byte[] expected = filled((byte) 0xAB, 32);
    final byte[] wrapped = wrap(mk, CONTEXT, expected);
    final byte[][] seen = new byte[1][];
    new LocalMasterKeyKmsUnwrapAdapter(mk).unwrapInto(wrapped, CONTEXT, dk -> seen[0] = dk.clone());
    assertThat(seen[0]).isEqualTo(expected);
  }

  @Test
  void unwrapsAes128DataKey() throws Exception {
    final byte[] mk = masterKey((byte) 0x11);
    final byte[] expected = filled((byte) 0x7E, 16);
    final byte[] wrapped = wrap(mk, CONTEXT, expected);
    final byte[][] seen = new byte[1][];
    new LocalMasterKeyKmsUnwrapAdapter(mk).unwrapInto(wrapped, CONTEXT, dk -> seen[0] = dk.clone());
    assertThat(seen[0]).isEqualTo(expected);
  }

  @Test
  void zeroizesDataKeyAfterConsumerReturns() throws Exception {
    final byte[] mk = masterKey((byte) 0x11);
    final byte[] wrapped = wrap(mk, CONTEXT, filled((byte) 0xAB, 32));
    final byte[][] captured = new byte[1][];
    // Capture the array reference (not a clone) to observe post-scope zeroization (Doc 26 §17.1).
    new LocalMasterKeyKmsUnwrapAdapter(mk).unwrapInto(wrapped, CONTEXT, dk -> captured[0] = dk);
    assertThat(captured[0]).containsOnly((byte) 0);
  }

  @Test
  void failsClosedOnTamperedWrappedKey() throws Exception {
    final byte[] mk = masterKey((byte) 0x11);
    final byte[] wrapped = wrap(mk, CONTEXT, filled((byte) 0xAB, 32));
    wrapped[wrapped.length - 1] ^= 0x01;
    assertThatThrownBy(
            () -> new LocalMasterKeyKmsUnwrapAdapter(mk).unwrapInto(wrapped, CONTEXT, dk -> {}))
        .isInstanceOf(KmsException.class)
        .extracting(e -> ((KmsException) e).reason())
        .isEqualTo(KmsException.Reason.INTEGRITY_FAILURE);
  }

  @Test
  void failsClosedOnWrongEncryptionContext() throws Exception {
    final byte[] mk = masterKey((byte) 0x11);
    final byte[] wrapped = wrap(mk, CONTEXT, filled((byte) 0xAB, 32));
    final byte[] wrongContext = "region=eu-west-1".getBytes(StandardCharsets.UTF_8);
    assertThatThrownBy(
            () ->
                new LocalMasterKeyKmsUnwrapAdapter(mk).unwrapInto(wrapped, wrongContext, dk -> {}))
        .isInstanceOf(KmsException.class)
        .extracting(e -> ((KmsException) e).reason())
        .isEqualTo(KmsException.Reason.INTEGRITY_FAILURE);
  }

  @Test
  void failsClosedOnWrongMasterKey() throws Exception {
    final byte[] wrapped = wrap(masterKey((byte) 0x11), CONTEXT, filled((byte) 0xAB, 32));
    assertThatThrownBy(
            () ->
                new LocalMasterKeyKmsUnwrapAdapter(masterKey((byte) 0x22))
                    .unwrapInto(wrapped, CONTEXT, dk -> {}))
        .isInstanceOf(KmsException.class)
        .extracting(e -> ((KmsException) e).reason())
        .isEqualTo(KmsException.Reason.INTEGRITY_FAILURE);
  }

  @Test
  void failsClosedOnShortEnvelope() {
    final byte[] mk = masterKey((byte) 0x11);
    assertThatThrownBy(
            () ->
                new LocalMasterKeyKmsUnwrapAdapter(mk).unwrapInto(new byte[10], CONTEXT, dk -> {}))
        .isInstanceOf(KmsException.class)
        .extracting(e -> ((KmsException) e).reason())
        .isEqualTo(KmsException.Reason.INVALID_ENVELOPE);
  }

  @Test
  void rejectsWrappedDataKeyExactlyAtTheMinimumLength() {
    // GCM_NONCE_BYTES (12) + GCM_TAG_BYTES (16) == 28: a nonce and a tag with zero ciphertext
    // between them. failsClosedOnShortEnvelope only reaches byte[10], so the boundary itself is
    // untested — a `<=` that drifted to `<` would hand a zero-length ciphertext to Cipher.
    final byte[] mk = masterKey((byte) 0x11);

    assertThatThrownBy(
            () ->
                new LocalMasterKeyKmsUnwrapAdapter(mk).unwrapInto(new byte[28], CONTEXT, dk -> {}))
        .isInstanceOf(KmsException.class)
        .hasMessageContaining("wrapped data key too short")
        .extracting(e -> ((KmsException) e).reason())
        .isEqualTo(KmsException.Reason.INVALID_ENVELOPE);
  }

  @Test
  void failsClosedWhenUnwrappedKeyHasInvalidLength() throws Exception {
    final byte[] mk = masterKey((byte) 0x11);
    final byte[] wrapped = wrap(mk, CONTEXT, filled((byte) 0xAB, 20)); // 20 is neither 16 nor 32
    assertThatThrownBy(
            () -> new LocalMasterKeyKmsUnwrapAdapter(mk).unwrapInto(wrapped, CONTEXT, dk -> {}))
        .isInstanceOf(KmsException.class)
        .extracting(e -> ((KmsException) e).reason())
        .isEqualTo(KmsException.Reason.INVALID_ENVELOPE);
  }

  @Test
  void rejectsInvalidMasterKeyLength() {
    assertThatThrownBy(() -> new LocalMasterKeyKmsUnwrapAdapter(new byte[20]))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void fromBase64ConstructsWorkingAdapter() throws Exception {
    final byte[] mk = masterKey((byte) 0x11);
    final byte[] expected = filled((byte) 0xCD, 32);
    final byte[] wrapped = wrap(mk, CONTEXT, expected);
    final LocalMasterKeyKmsUnwrapAdapter adapter =
        LocalMasterKeyKmsUnwrapAdapter.fromBase64(Base64.getEncoder().encodeToString(mk));
    final byte[][] seen = new byte[1][];
    adapter.unwrapInto(wrapped, CONTEXT, dk -> seen[0] = dk.clone());
    assertThat(seen[0]).isEqualTo(expected);
  }

  @Test
  void fromBase64RejectsInvalidBase64() {
    assertThatThrownBy(() -> LocalMasterKeyKmsUnwrapAdapter.fromBase64("not valid base64 !!!"))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void unwrappedKeyDecryptsRealCredentialViaEnvelopeDecryptor() throws Exception {
    // The full on-VPS envelope flow: unwrap the data key with the local master key, then use it to
    // AES-256-GCM decrypt the actual credential material — the two adapters compose (Doc 26 §17.1).
    final byte[] mk = masterKey((byte) 0x11);
    final byte[] realDataKey = filled((byte) 0x5A, 32);
    final byte[] wrappedDataKey = wrap(mk, CONTEXT, realDataKey);

    final Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
    cipher.init(
        Cipher.ENCRYPT_MODE,
        new SecretKeySpec(realDataKey, "AES"),
        new GCMParameterSpec(128, NONCE));
    cipher.updateAAD(CONTEXT);
    final byte[] credentialCiphertext =
        cipher.doFinal("sk-live-abc123".getBytes(StandardCharsets.UTF_8));

    final EnvelopeDecryptor decryptor = new EnvelopeDecryptor();
    final String[] recovered = new String[1];
    new LocalMasterKeyKmsUnwrapAdapter(mk)
        .unwrapInto(
            wrappedDataKey,
            CONTEXT,
            dataKey ->
                decryptor.decryptInto(
                    dataKey,
                    NONCE,
                    credentialCiphertext,
                    CONTEXT,
                    plaintext -> recovered[0] = new String(plaintext)));
    assertThat(recovered[0]).isEqualTo("sk-live-abc123");
  }
}

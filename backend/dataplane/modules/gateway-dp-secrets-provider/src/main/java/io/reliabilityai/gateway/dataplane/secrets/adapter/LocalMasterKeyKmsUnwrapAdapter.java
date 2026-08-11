package io.reliabilityai.gateway.dataplane.secrets.adapter;

import io.reliabilityai.gateway.common.Preconditions;
import java.security.GeneralSecurityException;
import java.util.Arrays;
import java.util.Base64;
import javax.crypto.AEADBadTagException;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * Single-VPS realization of {@link KmsUnwrapPort} (Doc 26 IR-7). Instead of a cloud KMS/CMK, the
 * wrapped AES data key is unwrapped with a <b>local master key</b> held in process memory (supplied
 * at the composition root from an environment variable or a permission-restricted key file) using
 * the same vetted JDK AES-256-GCM primitive as {@link EnvelopeDecryptor} — no cloud, no SDK, no
 * network. The {@code encryptionContext} is applied as GCM AAD exactly as a cloud KMS applies it,
 * so the wire format of a wrapped data key is identical and a later swap to a cloud adapter is
 * transparent to callers.
 *
 * <p><b>Wrapped-data-key envelope:</b> {@code nonce(12 bytes) || ciphertext+tag}, AES-256-GCM under
 * the master key with the encryption context as AAD. Unwrapping is the inverse; wrapping (the
 * equivalent of KMS {@code GenerateDataKey}) is an offline provisioning concern, never a data-plane
 * operation.
 *
 * <p><b>Invariants.</b> Fail-closed on every error, mapped to a content-free {@link KmsException}
 * (never a raw crypto message, never key/ciphertext bytes); the plaintext data key is delivered
 * only to the scoped {@link DataKeyConsumer} and <b>zeroized</b> immediately after (Doc 26 §17.1).
 * Deterministic given (master key, wrapped key, context). Stateless per call (a fresh {@link
 * Cipher}); thread- and virtual-thread-safe (the master key is immutable and read-only; no locks).
 *
 * <p><b>AWS migration:</b> replace this adapter with an {@code AwsKmsUnwrapAdapter} implementing
 * the same {@link KmsUnwrapPort} (KMS {@code Decrypt} with the encryption context as AAD) and wire
 * it at the composition root. No business logic, no caller, and no envelope format changes.
 */
public final class LocalMasterKeyKmsUnwrapAdapter implements KmsUnwrapPort {

  private static final String TRANSFORM = "AES/GCM/NoPadding";
  private static final int GCM_TAG_BITS = 128;
  private static final int GCM_TAG_BYTES = GCM_TAG_BITS / 8;
  private static final int GCM_NONCE_BYTES = 12;
  private static final int AES_256_KEY_BYTES = 32;
  private static final int AES_128_KEY_BYTES = 16;

  private final byte[] masterKey;

  /**
   * Creates the adapter with a local AES master key (16 or 32 bytes). The key is defensively cloned
   * and never exposed; sourcing it (env var / key file) is a composition-root concern, kept out of
   * this adapter so it stays pure and deterministic.
   *
   * @param masterKey the AES-128/256 master key bytes
   */
  public LocalMasterKeyKmsUnwrapAdapter(final byte[] masterKey) {
    Preconditions.requireNonNull(masterKey, "masterKey");
    if (masterKey.length != AES_128_KEY_BYTES && masterKey.length != AES_256_KEY_BYTES) {
      throw new IllegalArgumentException("masterKey must be 16 or 32 bytes");
    }
    this.masterKey = masterKey.clone();
  }

  /**
   * Constructs the adapter from a Base64-encoded master key (the composition-root env-var form).
   *
   * @param base64MasterKey the Base64 (standard alphabet) master key, 16 or 32 decoded bytes
   * @return the adapter
   */
  public static LocalMasterKeyKmsUnwrapAdapter fromBase64(final String base64MasterKey) {
    Preconditions.requireNonNull(base64MasterKey, "base64MasterKey");
    final byte[] decoded;
    try {
      decoded = Base64.getDecoder().decode(base64MasterKey.trim());
    } catch (final IllegalArgumentException e) {
      throw new IllegalArgumentException("masterKey is not valid Base64");
    }
    try {
      return new LocalMasterKeyKmsUnwrapAdapter(decoded);
    } finally {
      Arrays.fill(decoded, (byte) 0);
    }
  }

  @Override
  public void unwrapInto(
      final byte[] wrappedDataKey, final byte[] encryptionContext, final DataKeyConsumer consumer) {
    Preconditions.requireNonNull(wrappedDataKey, "wrappedDataKey");
    Preconditions.requireNonNull(consumer, "consumer");
    if (wrappedDataKey.length <= GCM_NONCE_BYTES + GCM_TAG_BYTES) {
      throw new KmsException(KmsException.Reason.INVALID_ENVELOPE, "wrapped data key too short");
    }

    final byte[] nonce = Arrays.copyOfRange(wrappedDataKey, 0, GCM_NONCE_BYTES);
    final byte[] ciphertext =
        Arrays.copyOfRange(wrappedDataKey, GCM_NONCE_BYTES, wrappedDataKey.length);
    final SecretKeySpec key = new SecretKeySpec(masterKey, "AES");
    byte[] dataKey = null;
    try {
      final Cipher cipher = Cipher.getInstance(TRANSFORM);
      cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(GCM_TAG_BITS, nonce));
      if (encryptionContext != null && encryptionContext.length > 0) {
        cipher.updateAAD(encryptionContext);
      }
      dataKey = cipher.doFinal(ciphertext);
      if (dataKey.length != AES_128_KEY_BYTES && dataKey.length != AES_256_KEY_BYTES) {
        throw new KmsException(
            KmsException.Reason.INVALID_ENVELOPE, "unwrapped data key has invalid length");
      }
      consumer.accept(dataKey);
    } catch (final AEADBadTagException e) {
      // Wrong master key, tampered wrapped key, or wrong encryption context — indistinguishable.
      throw new KmsException(
          KmsException.Reason.INTEGRITY_FAILURE, "data key unwrap authentication failed");
    } catch (final GeneralSecurityException e) {
      throw new KmsException(KmsException.Reason.DECRYPT_FAILURE, "data key unwrap failed");
    } finally {
      if (dataKey != null) {
        Arrays.fill(dataKey, (byte) 0); // never retained beyond the scoped consumer (Doc 26 §17.1)
      }
    }
  }
}

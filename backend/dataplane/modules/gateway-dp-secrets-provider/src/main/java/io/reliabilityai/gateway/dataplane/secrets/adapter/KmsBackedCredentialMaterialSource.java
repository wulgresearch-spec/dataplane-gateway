package io.reliabilityai.gateway.dataplane.secrets.adapter;

import io.reliabilityai.gateway.canonical.snapshot.CredentialSnapshotRef;
import io.reliabilityai.gateway.common.Preconditions;
import io.reliabilityai.gateway.dataplane.secrets.api.CredentialMaterialSource;

/**
 * A KMS-backed envelope decryptor (Doc 26 §7, AD-022 / IR-7). Each {@link #copyInto} (1) unwraps
 * the data key via {@link KmsUnwrapPort} (a cloud/KMS call), then (2) authenticated-decrypts the
 * credential ciphertext via {@link EnvelopeDecryptor}, and (3) copies the plaintext straight into
 * the destination buffer — the plaintext and data key are each held only within their scoped
 * callbacks and zeroized (Doc 26 §17.1). No plaintext is ever cached or returned as a retainable
 * object; on any KMS or integrity failure the copy fails closed with a typed {@link KmsException}
 * (Doc 26 §21).
 *
 * <p><b>Not a per-request hot-path source (§29/AD-022/SP-D2).</b> Because {@code copyInto} performs
 * a <b>synchronous KMS call</b>, this source MUST be invoked only at the <b>control-plane /
 * snapshot- refresh boundary</b> (out of band, once per credential version) to produce an
 * already-decrypted snapshot for the DP cache. The frozen materialization lifecycle (Doc 26 §8/§29)
 * performs <b>no</b> KMS call on the request path: the per-request hot-path source is {@link
 * SnapshotCredentialMaterialSource} (already-decrypted, sub-ms buffer copy, AD-022). Wiring this
 * KMS-backed source as the per-request materialization source would incur a synchronous KMS
 * round-trip per request and is forbidden.
 *
 * <p>Stateless and thread-safe: it holds only encrypted material and stateless collaborators; each
 * {@link #copyInto} performs a fresh unwrap+decrypt (VT-safe, no locks).
 */
public final class KmsBackedCredentialMaterialSource implements CredentialMaterialSource {

  private final EncryptedCredentialSnapshot snapshot;
  private final KmsUnwrapPort kmsUnwrap;
  private final EnvelopeDecryptor decryptor;

  /**
   * Creates the source.
   *
   * @param snapshot the encrypted credential snapshot (ciphertext + wrapped key)
   * @param kmsUnwrap the KMS data-key unwrap seam (the only cloud dependency)
   * @param decryptor the AES-GCM envelope decryptor
   */
  public KmsBackedCredentialMaterialSource(
      final EncryptedCredentialSnapshot snapshot,
      final KmsUnwrapPort kmsUnwrap,
      final EnvelopeDecryptor decryptor) {
    this.snapshot = Preconditions.requireNonNull(snapshot, "snapshot");
    this.kmsUnwrap = Preconditions.requireNonNull(kmsUnwrap, "kmsUnwrap");
    this.decryptor = Preconditions.requireNonNull(decryptor, "decryptor");
  }

  @Override
  public CredentialSnapshotRef ref() {
    return snapshot.ref();
  }

  @Override
  public int length() {
    return snapshot.plaintextLength();
  }

  @Override
  public void copyInto(final char[] destination) {
    Preconditions.requireNonNull(destination, "destination");
    if (destination.length < snapshot.plaintextLength()) {
      throw new IllegalArgumentException("destination buffer too small");
    }
    // Nested scoped callbacks: the plaintext data key (inner) and the decrypted credential
    // (innermost)
    // are each zeroized when their scope exits (Doc 26 §17.1). Any failure propagates as
    // KmsException
    // and the caller (MaterializationService) fails closed.
    kmsUnwrap.unwrapInto(
        snapshot.wrappedDataKey(),
        snapshot.encryptionContext(),
        dataKey ->
            decryptor.decryptInto(
                dataKey,
                snapshot.nonce(),
                snapshot.ciphertext(),
                snapshot.encryptionContext(),
                plaintext -> {
                  if (plaintext.length != snapshot.plaintextLength()) {
                    throw new KmsException(
                        KmsException.Reason.INVALID_ENVELOPE, "decrypted length mismatch");
                  }
                  System.arraycopy(plaintext, 0, destination, 0, plaintext.length);
                }));
  }
}

package io.reliabilityai.gateway.dataplane.secrets.adapter;

import io.reliabilityai.gateway.canonical.snapshot.CredentialSnapshotRef;
import io.reliabilityai.gateway.common.Preconditions;

/**
 * The C14-published encrypted form of one credential (AD-022 / IR-7, standard KMS envelope
 * encryption). Holds only <b>encrypted</b> bytes — a KMS-wrapped data key and the AES-GCM
 * ciphertext of the credential material — plus the content-free {@link CredentialSnapshotRef} and
 * the plaintext length needed to size the lease buffer. It never holds plaintext credential
 * material. Immutable; byte arrays are defensively cloned on construction and accessed only within
 * this adapter package.
 */
public final class EncryptedCredentialSnapshot {

  private final CredentialSnapshotRef ref;
  private final byte[] wrappedDataKey;
  private final byte[] nonce;
  private final byte[] ciphertext;
  private final byte[] encryptionContext;
  private final int plaintextLength;

  /**
   * Creates an encrypted credential snapshot, defensively cloning all byte arrays.
   *
   * @param ref the content-free snapshot reference (version, tenant binding, expiry)
   * @param wrappedDataKey the KMS-wrapped (encrypted) data key
   * @param nonce the 12-byte AES-GCM nonce
   * @param ciphertext the AES-GCM ciphertext (with tag) of the credential material
   * @param encryptionContext the AAD (region + tenant binding); may be empty
   * @param plaintextLength the length of the decrypted credential material (to size the lease
   *     buffer)
   */
  public EncryptedCredentialSnapshot(
      final CredentialSnapshotRef ref,
      final byte[] wrappedDataKey,
      final byte[] nonce,
      final byte[] ciphertext,
      final byte[] encryptionContext,
      final int plaintextLength) {
    this.ref = Preconditions.requireNonNull(ref, "ref");
    this.wrappedDataKey = Preconditions.requireNonNull(wrappedDataKey, "wrappedDataKey").clone();
    this.nonce = Preconditions.requireNonNull(nonce, "nonce").clone();
    this.ciphertext = Preconditions.requireNonNull(ciphertext, "ciphertext").clone();
    this.encryptionContext =
        Preconditions.requireNonNull(encryptionContext, "encryptionContext").clone();
    if (plaintextLength <= 0) {
      throw new IllegalArgumentException("plaintextLength must be positive");
    }
    this.plaintextLength = plaintextLength;
  }

  /**
   * The content-free reference/metadata for this credential.
   *
   * @return the snapshot reference
   */
  public CredentialSnapshotRef ref() {
    return ref;
  }

  /**
   * The decrypted credential length (to size the lease buffer).
   *
   * @return the plaintext length
   */
  public int plaintextLength() {
    return plaintextLength;
  }

  // Package-private raw accessors: the encrypted bytes are consumed only by
  // KmsBackedCredentialMaterialSource within this adapter package. They are ciphertext / wrapped
  // key,
  // never plaintext. They are cloned on read so the class is genuinely immutable — no caller can
  // mutate
  // the internal arrays (as the class contract states).
  byte[] wrappedDataKey() {
    return wrappedDataKey.clone();
  }

  byte[] nonce() {
    return nonce.clone();
  }

  byte[] ciphertext() {
    return ciphertext.clone();
  }

  byte[] encryptionContext() {
    return encryptionContext.clone();
  }
}

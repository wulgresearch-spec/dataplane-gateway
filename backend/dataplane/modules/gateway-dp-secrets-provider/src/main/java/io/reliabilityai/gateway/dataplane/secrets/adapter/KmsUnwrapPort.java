package io.reliabilityai.gateway.dataplane.secrets.adapter;

/**
 * The single cloud seam of envelope encryption (Doc 26 IR-7, Doc 08 §14): unwrap (decrypt) the
 * wrapped AES data key using the customer-managed key (CMK) held in the cloud KMS. This is the
 * <b>only</b> operation that requires a KMS/network call; the subsequent local AES-GCM decrypt of
 * the credential material is done by {@link EnvelopeDecryptor}. No production implementation is
 * provided in this repository because it requires a real KMS SDK + CMK — it must not be faked. The
 * implementation lives in infrastructure (AWS KMS / GCP KMS / Azure Key Vault) and is wired at the
 * composition root.
 *
 * <p>Implementations MUST: apply the encryption context as KMS-level AAD; map every provider
 * failure to a typed {@link KmsException} (never a raw provider exception); deliver the plaintext
 * data key to the scoped consumer and never retain it; and be fail-closed on any error.
 */
public interface KmsUnwrapPort {

  /** Scoped consumer of the unwrapped plaintext data key; the array is zeroized after the call. */
  @FunctionalInterface
  interface DataKeyConsumer {
    /**
     * Applies the unwrapped plaintext data key within a bounded scope; MUST NOT be retained.
     *
     * @param dataKey the plaintext AES data key (zeroized when this returns)
     */
    void accept(byte[] dataKey);
  }

  /**
   * Unwraps the wrapped data key via the KMS CMK and applies it to the consumer, fail-closed.
   *
   * @param wrappedDataKey the KMS-wrapped (encrypted) data key
   * @param encryptionContext the KMS encryption context / AAD (region + tenant binding), or empty
   * @param consumer the scoped plaintext-data-key consumer
   * @throws KmsException fail-closed on any KMS/unwrap failure ({@code KEY_UNAVAILABLE})
   */
  void unwrapInto(byte[] wrappedDataKey, byte[] encryptionContext, DataKeyConsumer consumer);
}

package io.reliabilityai.gateway.dataplane.plugin.api;

/**
 * Verifies a vetted snapshot against the pinned trust anchor (Doc 28 §STC).
 *
 * <p>Verify-only. There is no sign method and there never will be: Doc 28 STC-7 and ROC-8 put
 * signing with the control plane, and the runtime holds only the public anchor. An implementation
 * that could sign would be a runtime that could approve its own plugins.
 *
 * <p>Doc 28 STC-6 forbids trust-on-first-use, so an implementation must refuse a snapshot whose
 * signer is unknown rather than learning it.
 */
public interface PluginSignaturePort {

  /**
   * Verifies signature, digest and provenance against the pinned anchor.
   *
   * <p>Must not throw for an untrusted snapshot — an untrusted snapshot is a normal, expected
   * answer, not an exceptional one, and making it an exception invites a caller to swallow it.
   *
   * @param snapshot the snapshot to verify
   * @return the verdict
   */
  Verdict verify(VettedPluginSnapshot snapshot);

  /** The outcome of verifying one snapshot (Doc 28 STC-5). */
  enum Verdict {
    /** Signature, digest and provenance all check out against the pinned anchor. */
    TRUSTED,
    /** The signature does not verify against the anchor. */
    SIGNATURE_INVALID,
    /** The artifact bytes do not hash to the signed digest — substitution after signing. */
    DIGEST_MISMATCH,
    /** The provenance statement is absent or malformed. */
    PROVENANCE_INVALID,
    /** The signer is not the pinned anchor. No trust-on-first-use (Doc 28 STC-6). */
    UNKNOWN_SIGNER;

    /**
     * Whether this verdict admits loading.
     *
     * @return true only for {@link #TRUSTED}
     */
    public boolean trusted() {
      return this == TRUSTED;
    }
  }
}

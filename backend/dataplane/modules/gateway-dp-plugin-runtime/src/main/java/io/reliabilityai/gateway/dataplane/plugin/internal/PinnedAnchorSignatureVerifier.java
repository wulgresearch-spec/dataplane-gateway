package io.reliabilityai.gateway.dataplane.plugin.internal;

import io.reliabilityai.gateway.common.Preconditions;
import io.reliabilityai.gateway.dataplane.plugin.api.PluginSignaturePort;
import io.reliabilityai.gateway.dataplane.plugin.api.VettedPluginSnapshot;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.PublicKey;
import java.security.Signature;
import java.util.Arrays;

/**
 * Verifies snapshots against a single pinned public key (Doc 28 §STC).
 *
 * <p><b>Verify-only, by construction.</b> The class holds a {@link PublicKey}. There is no field,
 * no constructor and no method through which a private key could arrive, so Doc 28 STC-7/ROC-8's
 * "the runtime never signs" is a property of the type rather than a rule.
 *
 * <p><b>No trust-on-first-use.</b> The anchor is supplied once at construction and never changes. A
 * snapshot signed by anything else fails, and there is no path that learns a new signer (Doc 28
 * STC-6).
 *
 * <p><b>Digest before signature.</b> The artifact is hashed and compared to the claimed digest
 * first, because the signature covers the digest, not the bytes. Verifying the signature and
 * trusting the digest without rehashing would leave exactly the substitution gap STC-3 exists to
 * close: a valid signature over a digest that no longer describes the artifact.
 */
public final class PinnedAnchorSignatureVerifier implements PluginSignaturePort {

  /** The digest algorithm the control plane signs over. */
  public static final String DIGEST_ALGORITHM = "SHA-256";

  private final PublicKey anchor;
  private final String signatureAlgorithm;

  /**
   * Creates the verifier around a pinned anchor.
   *
   * @param anchor the control plane's public signing key, distributed through the trust path
   * @param signatureAlgorithm the JCA signature algorithm, e.g. {@code SHA256withRSA}
   */
  public PinnedAnchorSignatureVerifier(final PublicKey anchor, final String signatureAlgorithm) {
    this.anchor = Preconditions.requireNonNull(anchor, "anchor");
    this.signatureAlgorithm =
        Preconditions.requireNonBlank(signatureAlgorithm, "signatureAlgorithm");
  }

  @Override
  public Verdict verify(final VettedPluginSnapshot snapshot) {
    if (snapshot == null) {
      return Verdict.SIGNATURE_INVALID;
    }
    if (snapshot.provenance().isBlank()) {
      return Verdict.PROVENANCE_INVALID;
    }

    final byte[] claimedDigest = snapshot.digest();
    final byte[] actualDigest;
    try {
      actualDigest = MessageDigest.getInstance(DIGEST_ALGORITHM).digest(snapshot.artifact());
    } catch (final GeneralSecurityException unavailable) {
      // A JVM without SHA-256 cannot verify anything. Fail closed rather than skip the check.
      return Verdict.DIGEST_MISMATCH;
    }
    // Constant-time: the digest is attacker-influenced, and a short-circuiting compare leaks how
    // much
    // of a forged digest was correct.
    if (!MessageDigest.isEqual(actualDigest, claimedDigest)) {
      return Verdict.DIGEST_MISMATCH;
    }

    try {
      final Signature verifier = Signature.getInstance(signatureAlgorithm);
      verifier.initVerify(anchor);
      verifier.update(claimedDigest);
      // The provenance is signed alongside the digest, so it cannot be swapped for another vetting
      // statement while keeping a valid signature (Doc 28 STC-4).
      verifier.update(snapshot.provenance().getBytes(java.nio.charset.StandardCharsets.UTF_8));
      return verifier.verify(snapshot.signature()) ? Verdict.TRUSTED : Verdict.SIGNATURE_INVALID;
    } catch (final GeneralSecurityException rejected) {
      // A signature that does not even parse against this anchor's algorithm was produced by a
      // different signer. That is UNKNOWN_SIGNER, not a malformed signature by our own trusted key.
      return Verdict.UNKNOWN_SIGNER;
    }
  }

  /**
   * Computes the digest the control plane would sign for an artifact.
   *
   * <p>Provided so an operator tool or a test can build a snapshot that this verifier will accept,
   * without duplicating the algorithm choice and drifting from it.
   *
   * @param artifact the plugin bytes
   * @return the SHA-256 digest
   */
  public static byte[] digestOf(final byte[] artifact) {
    Preconditions.requireNonNull(artifact, "artifact");
    try {
      return MessageDigest.getInstance(DIGEST_ALGORITHM).digest(artifact);
    } catch (final GeneralSecurityException unavailable) {
      throw new IllegalStateException("digest algorithm unavailable");
    }
  }

  /**
   * The bytes a signature must cover: the digest followed by the provenance.
   *
   * @param digest the artifact digest
   * @param provenance the provenance statement
   * @return the signing input
   */
  public static byte[] signingInput(final byte[] digest, final String provenance) {
    Preconditions.requireNonNull(digest, "digest");
    Preconditions.requireNonBlank(provenance, "provenance");
    final byte[] provenanceBytes = provenance.getBytes(java.nio.charset.StandardCharsets.UTF_8);
    final byte[] input = Arrays.copyOf(digest, digest.length + provenanceBytes.length);
    System.arraycopy(provenanceBytes, 0, input, digest.length, provenanceBytes.length);
    return input;
  }
}

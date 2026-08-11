package io.reliabilityai.gateway.canonical.snapshot;

import io.reliabilityai.gateway.common.ContentFree;
import io.reliabilityai.gateway.common.Preconditions;

/**
 * One public verification key in a {@link VerificationKeySnapshot} (Doc 37 §8). Carries the key id,
 * the bound {@link JwsAlgorithm} (a token presenting a different algorithm for this {@code kid} is
 * an algorithm-confusion attack and is rejected), and the <b>public</b> key material as the Base64
 * of its X.509 {@code SubjectPublicKeyInfo} encoding — the standard, JCA-reconstructable form.
 * Public keys are not secrets (Doc 37 IAU-D9), so this is content-free.
 *
 * @param kid the key id
 * @param algorithm the algorithm this key is bound to (prevents algorithm confusion, Doc 37 §9)
 * @param publicKeyBase64 Base64 of the X.509 SubjectPublicKeyInfo public-key encoding
 */
public record VerificationKey(String kid, JwsAlgorithm algorithm, String publicKeyBase64)
    implements ContentFree {

  /** Compact constructor validating required, content-free fields. */
  public VerificationKey {
    Preconditions.requireNonBlank(kid, "kid");
    Preconditions.requireNonNull(algorithm, "algorithm");
    Preconditions.requireNonBlank(publicKeyBase64, "publicKeyBase64");
  }
}

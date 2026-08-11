package io.reliabilityai.gateway.dataplane.authn.domain;

import io.reliabilityai.gateway.canonical.snapshot.JwsAlgorithm;
import io.reliabilityai.gateway.canonical.snapshot.VerificationKey;
import io.reliabilityai.gateway.canonical.snapshot.VerificationKeySnapshot;
import io.reliabilityai.gateway.common.Preconditions;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.PublicKey;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

/**
 * An immutable index over one pinned {@link VerificationKeySnapshot} (Doc 37 §9/§VKR). Reconstructs
 * each public key once at construction (fail-closed if any key is malformed), <b>rejects a
 * duplicate {@code kid}</b> in the snapshot as an integrity error, and resolves a token's {@code
 * (kid, alg)} to a {@link KeyResolution} — rejecting an <b>unknown</b> key (VKR-4), a
 * <b>revoked</b> key (VKR-5), and an <b>algorithm mismatch</b> against the key's bound algorithm
 * (algorithm confusion, §9). Uses only cached snapshot material — never an online IdP (VKR-3).
 * Thread-safe (fully immutable).
 */
public final class VerificationKeyRegistry {

  private final Map<String, ResolvedKey> keysByKid;
  private final Set<String> revokedKeyIds;

  /**
   * Builds the registry from a pinned snapshot.
   *
   * @param snapshot the pinned verification-key snapshot
   * @throws IllegalArgumentException if a {@code kid} is duplicated or a key cannot be
   *     reconstructed (snapshot integrity failure — fail closed)
   */
  public VerificationKeyRegistry(final VerificationKeySnapshot snapshot) {
    Preconditions.requireNonNull(snapshot, "snapshot");
    final Map<String, ResolvedKey> map = new HashMap<>();
    for (final VerificationKey key : snapshot.keys()) {
      final ResolvedKey resolved = new ResolvedKey(reconstruct(key), key.algorithm());
      if (map.putIfAbsent(key.kid(), resolved) != null) {
        throw new IllegalArgumentException(
            "duplicate kid in verification-key snapshot: " + key.kid());
      }
    }
    this.keysByKid = Map.copyOf(map);
    this.revokedKeyIds = Set.copyOf(snapshot.revokedKeyIds());
  }

  /**
   * Resolves a token's key, fail-closed (Doc 37 §9/§VKR).
   *
   * @param kid the token's key id (nullable/blank ⇒ unknown key)
   * @param presentedAlgorithm the algorithm the token presents
   * @return a {@link KeyResolution}
   */
  public KeyResolution resolve(final String kid, final JwsAlgorithm presentedAlgorithm) {
    Preconditions.requireNonNull(presentedAlgorithm, "presentedAlgorithm");
    if (kid == null || kid.isBlank()) {
      return new KeyResolution.Rejected(AuthenticationFailureReason.UNKNOWN_KEY);
    }
    if (revokedKeyIds.contains(kid)) {
      return new KeyResolution.Rejected(AuthenticationFailureReason.KEY_REVOKED);
    }
    final ResolvedKey resolved = keysByKid.get(kid);
    if (resolved == null) {
      return new KeyResolution.Rejected(AuthenticationFailureReason.UNKNOWN_KEY);
    }
    if (resolved.algorithm() != presentedAlgorithm) {
      // The token claims an algorithm different from the one this key is bound to (confusion).
      return new KeyResolution.Rejected(AuthenticationFailureReason.ALGORITHM_MISMATCH);
    }
    return new KeyResolution.Resolved(resolved.publicKey(), resolved.algorithm(), kid);
  }

  private static PublicKey reconstruct(final VerificationKey key) {
    try {
      final byte[] der = Base64.getDecoder().decode(key.publicKeyBase64());
      final KeyFactory keyFactory = KeyFactory.getInstance(key.algorithm().jcaKeyAlgorithm());
      return keyFactory.generatePublic(new X509EncodedKeySpec(der));
    } catch (final GeneralSecurityException | IllegalArgumentException e) {
      throw new IllegalArgumentException("invalid public key for kid " + key.kid(), e);
    }
  }

  private record ResolvedKey(PublicKey publicKey, JwsAlgorithm algorithm) {}
}

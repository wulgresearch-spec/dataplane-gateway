package io.reliabilityai.gateway.dataplane.authn.domain;

import io.reliabilityai.gateway.canonical.snapshot.JwsAlgorithm;
import io.reliabilityai.gateway.common.Preconditions;
import java.security.PublicKey;

/**
 * The typed outcome of resolving a token's {@code kid}/{@code alg} against the pinned verification
 * keys (Doc 37 §9/§VKR). Sealed and fail-closed: either a {@link Resolved} public key + bound
 * algorithm, or a {@link Rejected} typed reason (unknown key, revoked key, or algorithm confusion).
 */
public sealed interface KeyResolution permits KeyResolution.Resolved, KeyResolution.Rejected {

  /**
   * A resolved, non-revoked key whose bound algorithm matches the token.
   *
   * @param publicKey the reconstructed JCA public key
   * @param algorithm the bound algorithm
   * @param kid the key id (recorded for the decision)
   */
  record Resolved(PublicKey publicKey, JwsAlgorithm algorithm, String kid)
      implements KeyResolution {
    /** Compact constructor validating fields. */
    public Resolved {
      Preconditions.requireNonNull(publicKey, "publicKey");
      Preconditions.requireNonNull(algorithm, "algorithm");
      Preconditions.requireNonBlank(kid, "kid");
    }
  }

  /**
   * A fail-closed rejection.
   *
   * @param reason the typed failure reason
   */
  record Rejected(AuthenticationFailureReason reason) implements KeyResolution {
    /** Compact constructor validating the reason. */
    public Rejected {
      Preconditions.requireNonNull(reason, "reason");
    }
  }
}

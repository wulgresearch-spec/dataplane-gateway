package io.reliabilityai.gateway.dataplane.authn.api;

import io.reliabilityai.gateway.canonical.identity.PrincipalId;
import io.reliabilityai.gateway.common.Preconditions;
import io.reliabilityai.gateway.dataplane.authn.domain.AuthenticationFailureReason;
import java.util.Map;

/**
 * The typed outcome of verifying a forwarded transport identity against the pinned verification
 * keys (Doc 37 §9). Sealed and fail-closed: either a {@link Verified} principal or a {@link
 * Rejected} typed failure — a raw provider/IdP exception never escapes the verifier (Doc 37
 * IAU-A5). The verifier answers only <b>"who is the principal?"</b>; it never evaluates permissions
 * (Doc 37 §ANZ).
 */
public sealed interface VerificationOutcome
    permits VerificationOutcome.Verified, VerificationOutcome.Rejected {

  /**
   * A verified principal with read-only claims (Doc 37 ANZ-8 — claims are data, never evaluated for
   * access here) and the transport auth method used.
   *
   * @param principalId the non-identifying scoped principal id (Doc 37 §PIM)
   * @param claims read-only principal claims (attached, never evaluated for authorization)
   * @param authMethod the transport auth method (e.g. bearer, api-key, mtls)
   */
  record Verified(PrincipalId principalId, Map<String, String> claims, String authMethod)
      implements VerificationOutcome {
    /** Compact constructor validating fields and defensively copying claims. */
    public Verified {
      Preconditions.requireNonNull(principalId, "principalId");
      Preconditions.requireNonBlank(authMethod, "authMethod");
      claims = claims == null ? Map.of() : Map.copyOf(claims);
    }
  }

  /**
   * A fail-closed rejection carrying a typed reason (Doc 37 §14).
   *
   * @param reason the typed failure reason
   */
  record Rejected(AuthenticationFailureReason reason) implements VerificationOutcome {
    /** Compact constructor validating the reason. */
    public Rejected {
      Preconditions.requireNonNull(reason, "reason");
    }
  }
}

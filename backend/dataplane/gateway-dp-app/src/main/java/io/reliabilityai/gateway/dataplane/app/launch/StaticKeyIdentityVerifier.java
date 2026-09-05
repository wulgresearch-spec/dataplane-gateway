package io.reliabilityai.gateway.dataplane.app.launch;

import io.reliabilityai.gateway.canonical.identity.PrincipalId;
import io.reliabilityai.gateway.canonical.io.ForwardedTransportIdentity;
import io.reliabilityai.gateway.canonical.snapshot.VerificationKeySnapshot;
import io.reliabilityai.gateway.dataplane.authn.api.IdentityVerifierPort;
import io.reliabilityai.gateway.dataplane.authn.api.VerificationOutcome;
import io.reliabilityai.gateway.dataplane.authn.domain.AuthenticationFailureReason;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Map;

/**
 * Authenticates callers against one shared bearer secret.
 *
 * <p>This is the API-key arm of the authentication story, not the JWT arm: it answers only "who is
 * the principal?" and never evaluates permissions, which remain governance's job. A node fronting
 * more than one caller should be configured with {@code AuthenticationConfig} and real verification
 * keys instead; this verifier exists for single-tenant deployments where issuing a JWKS to
 * authenticate one operator is ceremony without benefit.
 *
 * <p>Comparison is constant-time. A shared secret compared with {@code equals} leaks its prefix
 * through timing, and the fact that this is the "simple" path is not a reason to leak it.
 */
final class StaticKeyIdentityVerifier implements IdentityVerifierPort {

  /** The credential scheme the ingress forwards, matched case-insensitively. */
  private static final String BEARER_PREFIX = "bearer ";

  private final byte[] expected;
  private final PrincipalId principalId;

  /**
   * Creates a verifier for one shared secret.
   *
   * @param clientApiKey the secret callers must present
   * @param principalId the principal every accepted caller resolves to
   */
  StaticKeyIdentityVerifier(final String clientApiKey, final PrincipalId principalId) {
    this.expected = clientApiKey.getBytes(StandardCharsets.UTF_8);
    this.principalId = principalId;
  }

  @Override
  public VerificationOutcome verify(
      final ForwardedTransportIdentity transportIdentity,
      final VerificationKeySnapshot keySnapshot) {
    final String token = token(transportIdentity);
    if (token.isEmpty()) {
      return new VerificationOutcome.Rejected(AuthenticationFailureReason.MALFORMED_TOKEN);
    }
    final byte[] presented = token.getBytes(StandardCharsets.UTF_8);
    if (!MessageDigest.isEqual(expected, presented)) {
      return new VerificationOutcome.Rejected(AuthenticationFailureReason.INVALID_SIGNATURE);
    }
    return new VerificationOutcome.Verified(principalId, Map.of(), "api-key");
  }

  /**
   * Extracts the secret from the forwarded credential.
   *
   * <p>The ingress forwards the {@code Authorization} header verbatim, so the scheme is still
   * attached; a bare token is accepted too, because rejecting one on formatting alone would report
   * a malformed token when the real answer is that the secret is wrong.
   */
  private static String token(final ForwardedTransportIdentity transportIdentity) {
    if (transportIdentity == null || transportIdentity.credentialMaterial() == null) {
      return "";
    }
    // The prefix is matched before trailing whitespace is stripped: a bare "Bearer " carries no
    // secret at all, and trimming first would turn it into the candidate secret "Bearer" and
    // report a wrong key instead of a malformed header.
    final String material = transportIdentity.credentialMaterial().stripLeading();
    if (material.regionMatches(true, 0, BEARER_PREFIX, 0, BEARER_PREFIX.length())) {
      return material.substring(BEARER_PREFIX.length()).trim();
    }
    return material.trim();
  }
}

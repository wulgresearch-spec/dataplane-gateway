package io.reliabilityai.gateway.dataplane.app.launch;

import static org.assertj.core.api.Assertions.assertThat;

import io.reliabilityai.gateway.canonical.identity.PrincipalId;
import io.reliabilityai.gateway.canonical.identity.Region;
import io.reliabilityai.gateway.canonical.identity.SnapshotVersion;
import io.reliabilityai.gateway.canonical.io.ForwardedTransportIdentity;
import io.reliabilityai.gateway.canonical.snapshot.VerificationKeySnapshot;
import io.reliabilityai.gateway.dataplane.authn.api.VerificationOutcome;
import io.reliabilityai.gateway.dataplane.authn.domain.AuthenticationFailureReason;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * The shared-secret verifier at the untrusted edge: what it accepts, and — more importantly — the
 * near-misses it must refuse. Every input here is attacker-controlled.
 */
class StaticKeyIdentityVerifierTest {

  private static final PrincipalId PRINCIPAL = new PrincipalId("principal-local");

  private final StaticKeyIdentityVerifier verifier =
      new StaticKeyIdentityVerifier("s3cret", PRINCIPAL);

  private static final VerificationKeySnapshot KEYS =
      new VerificationKeySnapshot(
          new SnapshotVersion("verification-keys", "v1"),
          new Region("us-east-1"),
          List.of(),
          List.of());

  private VerificationOutcome verify(final String credentialMaterial) {
    return verifier.verify(
        new ForwardedTransportIdentity("Bearer", credentialMaterial, Map.of()), KEYS);
  }

  private static AuthenticationFailureReason reasonOf(final VerificationOutcome outcome) {
    return ((VerificationOutcome.Rejected) outcome).reason();
  }

  @Test
  void acceptsTheSecretBehindTheBearerScheme() {
    final VerificationOutcome outcome = verify("Bearer s3cret");

    assertThat(outcome).isInstanceOf(VerificationOutcome.Verified.class);
    final VerificationOutcome.Verified verified = (VerificationOutcome.Verified) outcome;
    assertThat(verified.principalId()).isEqualTo(PRINCIPAL);
    assertThat(verified.authMethod()).isEqualTo("api-key");
    assertThat(verified.claims()).isEmpty();
  }

  @Test
  void acceptsABareSecretWithNoScheme() {
    assertThat(verify("s3cret")).isInstanceOf(VerificationOutcome.Verified.class);
  }

  @Test
  void treatsTheSchemeCaseInsensitively() {
    assertThat(verify("bearer s3cret")).isInstanceOf(VerificationOutcome.Verified.class);
    assertThat(verify("BEARER s3cret")).isInstanceOf(VerificationOutcome.Verified.class);
  }

  @Test
  void rejectsAWrongSecretAsAnInvalidSignatureRatherThanAMalformedToken() {
    // The distinction matters to an operator reading audit: the caller sent a well-formed
    // credential that was not ours, which is a different event from sending nothing.
    assertThat(reasonOf(verify("Bearer wrong")))
        .isEqualTo(AuthenticationFailureReason.INVALID_SIGNATURE);
  }

  @Test
  void rejectsAPrefixOfTheSecret() {
    assertThat(reasonOf(verify("Bearer s3cre")))
        .isEqualTo(AuthenticationFailureReason.INVALID_SIGNATURE);
  }

  @Test
  void rejectsTheSecretWithTrailingContent() {
    assertThat(reasonOf(verify("Bearer s3cretX")))
        .isEqualTo(AuthenticationFailureReason.INVALID_SIGNATURE);
  }

  @Test
  void rejectsAnAbsentCredential() {
    assertThat(reasonOf(verify(""))).isEqualTo(AuthenticationFailureReason.MALFORMED_TOKEN);
    assertThat(reasonOf(verify("   "))).isEqualTo(AuthenticationFailureReason.MALFORMED_TOKEN);
  }

  @Test
  void rejectsASchemeWithNoSecretBehindIt() {
    assertThat(reasonOf(verify("Bearer "))).isEqualTo(AuthenticationFailureReason.MALFORMED_TOKEN);
  }

  @Test
  void rejectsANullIdentityWithoutThrowing() {
    assertThat(reasonOf(verifier.verify(null, KEYS)))
        .isEqualTo(AuthenticationFailureReason.MALFORMED_TOKEN);
  }

  @Test
  void neverAdmitsOnAnEmptyConfiguredSecret() {
    // A misconfigured node whose secret is empty must not turn every caller into a principal.
    final StaticKeyIdentityVerifier misconfigured = new StaticKeyIdentityVerifier("", PRINCIPAL);

    final VerificationOutcome outcome =
        misconfigured.verify(new ForwardedTransportIdentity("Bearer", "Bearer ", Map.of()), KEYS);

    assertThat(outcome).isInstanceOf(VerificationOutcome.Rejected.class);
  }
}

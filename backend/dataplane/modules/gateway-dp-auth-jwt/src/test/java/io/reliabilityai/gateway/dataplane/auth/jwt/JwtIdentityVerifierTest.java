package io.reliabilityai.gateway.dataplane.auth.jwt;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.reliabilityai.gateway.canonical.io.ForwardedTransportIdentity;
import io.reliabilityai.gateway.canonical.snapshot.VerificationKeySnapshot;
import io.reliabilityai.gateway.dataplane.authn.api.VerificationOutcome;
import io.reliabilityai.gateway.dataplane.authn.domain.AuthenticationFailureReason;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

/** Verification behaviour: signatures, claims, and every documented rejection path. */
class JwtIdentityVerifierTest {

  private final JwtFixture.Keys rsa = JwtFixture.rsa("kid-rsa");
  private final JwtFixture.Keys ec = JwtFixture.ec("kid-ec");
  private final VerificationKeySnapshot snapshot = JwtFixture.snapshot(rsa, ec);
  private final JwtIdentityVerifier verifier =
      new JwtIdentityVerifier(JwtFixture.config(), JwtFixture.CLOCK);

  private VerificationOutcome verify(final String token) {
    return verifier.verify(JwtFixture.bearer(token), snapshot);
  }

  private static AuthenticationFailureReason reasonOf(final VerificationOutcome outcome) {
    return ((VerificationOutcome.Rejected) outcome).reason();
  }

  // ---- happy paths ---------------------------------------------------------------------------

  @Test
  void acceptsAValidRs256Token() {
    final VerificationOutcome outcome = verify(JwtFixture.sign(rsa, JwtFixture.claims()));

    assertThat(outcome).isInstanceOf(VerificationOutcome.Verified.class);
    final VerificationOutcome.Verified verified = (VerificationOutcome.Verified) outcome;
    assertThat(verified.principalId().value()).isEqualTo("principal-a");
    assertThat(verified.authMethod()).isEqualTo("jwt");
    assertThat(verified.claims()).containsEntry(JwtFixture.TENANT_CLAIM, "tenant-a");
  }

  @Test
  void acceptsAValidEs256Token() {
    final VerificationOutcome outcome = verify(JwtFixture.sign(ec, JwtFixture.claims()));

    assertThat(outcome).isInstanceOf(VerificationOutcome.Verified.class);
  }

  @Test
  void acceptsAnAudienceArrayContainingTheConfiguredAudience() {
    final Map<String, Object> claims = JwtFixture.claims();
    claims.put("aud", List.of("other-service", JwtFixture.AUDIENCE));

    assertThat(verify(JwtFixture.sign(rsa, claims)))
        .isInstanceOf(VerificationOutcome.Verified.class);
  }

  @Test
  void returnsOnlyCuratedClaimsNotTheWholePayload() {
    final Map<String, Object> claims = JwtFixture.claims();
    claims.put("email", "person@example.com");
    claims.put("ssn", "123-45-6789");

    final VerificationOutcome.Verified verified =
        (VerificationOutcome.Verified) verify(JwtFixture.sign(rsa, claims));

    // Issuer-controlled claims flow into audit records; only what the gateway reasons about is
    // kept.
    assertThat(verified.claims()).doesNotContainKeys("email", "ssn");
    assertThat(verified.claims().keySet())
        .containsExactlyInAnyOrder("iss", "aud", "alg", "tid", "kid");
  }

  // ---- temporal claims -----------------------------------------------------------------------

  @Test
  void rejectsAnExpiredToken() {
    final Map<String, Object> claims = JwtFixture.claims();
    claims.put("exp", JwtFixture.NOW.getEpochSecond() - 3600);

    assertThat(reasonOf(verify(JwtFixture.sign(rsa, claims))))
        .isEqualTo(AuthenticationFailureReason.EXPIRED);
  }

  @Test
  void rejectsATokenNotYetValid() {
    final Map<String, Object> claims = JwtFixture.claims();
    claims.put("nbf", JwtFixture.NOW.getEpochSecond() + 3600);

    assertThat(reasonOf(verify(JwtFixture.sign(rsa, claims))))
        .isEqualTo(AuthenticationFailureReason.NOT_YET_VALID);
  }

  @Test
  void rejectsATokenIssuedInTheFuture() {
    final Map<String, Object> claims = JwtFixture.claims();
    claims.put("iat", JwtFixture.NOW.getEpochSecond() + 3600);

    assertThat(reasonOf(verify(JwtFixture.sign(rsa, claims))))
        .isEqualTo(AuthenticationFailureReason.NOT_YET_VALID);
  }

  @Test
  void rejectsATokenWithoutAnExpiry() {
    final Map<String, Object> claims = JwtFixture.claims();
    claims.remove("exp");

    // A token that never expires is not an access token.
    assertThat(reasonOf(verify(JwtFixture.sign(rsa, claims))))
        .isEqualTo(AuthenticationFailureReason.MALFORMED_TOKEN);
  }

  @Test
  void clockSkewToleratesAMarginallyExpiredToken() {
    final JwtIdentityVerifier tolerant =
        new JwtIdentityVerifier(
            new JwtAuthenticationConfig(
                JwtFixture.ISSUER,
                JwtFixture.AUDIENCE,
                Set.of(JwtAuthenticationConfig.RS256),
                Duration.ofMinutes(5),
                JwtAuthenticationConfig.DEFAULT_MAX_TOKEN_BYTES,
                JwtFixture.TENANT_CLAIM,
                Optional.empty()),
            JwtFixture.CLOCK);
    final Map<String, Object> claims = JwtFixture.claims();
    claims.put("exp", JwtFixture.NOW.getEpochSecond() - 60);

    assertThat(tolerant.verify(JwtFixture.bearer(JwtFixture.sign(rsa, claims)), snapshot))
        .isInstanceOf(VerificationOutcome.Verified.class);
  }

  // ---- identity claims -----------------------------------------------------------------------

  @Test
  void rejectsAWrongIssuer() {
    final Map<String, Object> claims = JwtFixture.claims();
    claims.put("iss", "https://attacker.example");

    assertThat(reasonOf(verify(JwtFixture.sign(rsa, claims))))
        .isEqualTo(AuthenticationFailureReason.ISSUER_REJECTED);
  }

  @Test
  void rejectsAWrongAudience() {
    final Map<String, Object> claims = JwtFixture.claims();
    claims.put("aud", "some-other-service");

    // A correctly-signed token minted for another service must not open this door.
    assertThat(reasonOf(verify(JwtFixture.sign(rsa, claims))))
        .isEqualTo(AuthenticationFailureReason.AUDIENCE_REJECTED);
  }

  @Test
  void rejectsAMissingSubject() {
    final Map<String, Object> claims = JwtFixture.claims();
    claims.remove("sub");

    assertThat(reasonOf(verify(JwtFixture.sign(rsa, claims))))
        .isEqualTo(AuthenticationFailureReason.UNKNOWN_PRINCIPAL);
  }

  @Test
  void rejectsAMissingTenantClaim() {
    final Map<String, Object> claims = JwtFixture.claims();
    claims.remove(JwtFixture.TENANT_CLAIM);

    assertThat(reasonOf(verify(JwtFixture.sign(rsa, claims))))
        .isEqualTo(AuthenticationFailureReason.TENANT_UNRESOLVED);
  }

  // ---- signature and keys --------------------------------------------------------------------

  @Test
  void rejectsATamperedSignature() {
    final String token = JwtFixture.sign(rsa, JwtFixture.claims());
    final String tampered = token.substring(0, token.length() - 4) + "AAAA";

    assertThat(reasonOf(verify(tampered)))
        .isIn(
            AuthenticationFailureReason.INVALID_SIGNATURE,
            AuthenticationFailureReason.MALFORMED_TOKEN);
  }

  @Test
  void rejectsATokenSignedByAnUntrustedKey() {
    final JwtFixture.Keys attacker = JwtFixture.rsa("kid-rsa"); // same kid, different key

    assertThat(reasonOf(verify(JwtFixture.sign(attacker, JwtFixture.claims()))))
        .isEqualTo(AuthenticationFailureReason.INVALID_SIGNATURE);
  }

  @Test
  void rejectsAnUnknownKeyId() {
    final JwtFixture.Keys unpublished = JwtFixture.rsa("kid-unknown");

    assertThat(reasonOf(verify(JwtFixture.sign(unpublished, JwtFixture.claims()))))
        .isEqualTo(AuthenticationFailureReason.UNKNOWN_KEY);
  }

  @Test
  void rejectsARevokedKeyId() {
    final VerificationKeySnapshot revoked = JwtFixture.snapshot(List.of("kid-rsa"), rsa, ec);

    assertThat(
            reasonOf(
                verifier.verify(
                    JwtFixture.bearer(JwtFixture.sign(rsa, JwtFixture.claims())), revoked)))
        .isEqualTo(AuthenticationFailureReason.KEY_REVOKED);
  }

  @Test
  void rejectsAnAlgorithmThatDisagreesWithTheKey() {
    // Header claims ES256 while the kid resolves to an RSA key.
    final String token =
        JwtFixture.sign(rsa, JwtFixture.header("ES256", "kid-rsa"), JwtFixture.claims());

    assertThat(reasonOf(verify(token))).isEqualTo(AuthenticationFailureReason.ALGORITHM_MISMATCH);
  }

  // ---- structural rejections -------------------------------------------------------------------

  @Test
  void rejectsAlgNone() {
    final String token =
        JwtFixture.raw(
            JwtFixture.json(JwtFixture.header("none", "kid-rsa")),
            JwtFixture.json(JwtFixture.claims()),
            "AAAA");

    assertThat(reasonOf(verify(token))).isEqualTo(AuthenticationFailureReason.ALGORITHM_MISMATCH);
  }

  @Test
  void rejectsAnUnsignedToken() {
    final String token =
        JwtFixture.raw(
                JwtFixture.json(JwtFixture.header("none", "kid-rsa")),
                JwtFixture.json(JwtFixture.claims()),
                "")
            .trim();

    assertThat(reasonOf(verify(token)))
        .isIn(
            AuthenticationFailureReason.MALFORMED_TOKEN,
            AuthenticationFailureReason.ALGORITHM_MISMATCH);
  }

  @Test
  void rejectsAnUnsupportedAlgorithm() {
    final String token =
        JwtFixture.raw(
            JwtFixture.json(JwtFixture.header("RS512", "kid-rsa")),
            JwtFixture.json(JwtFixture.claims()),
            "AAAA");

    assertThat(reasonOf(verify(token))).isEqualTo(AuthenticationFailureReason.ALGORITHM_MISMATCH);
  }

  @Test
  void rejectsHs256WhenNotExplicitlyEnabled() {
    // Algorithm confusion: a symmetric token must never be checked against an asymmetric key.
    assertThat(reasonOf(verify(JwtFixture.signHmac(JwtFixture.claims()))))
        .isEqualTo(AuthenticationFailureReason.ALGORITHM_MISMATCH);
  }

  @Test
  void rejectsAMalformedHeader() {
    final String token = JwtFixture.raw("{not json", JwtFixture.json(JwtFixture.claims()), "AAAA");

    assertThat(reasonOf(verify(token))).isEqualTo(AuthenticationFailureReason.MALFORMED_TOKEN);
  }

  @Test
  void rejectsAMalformedPayload() {
    final String token =
        JwtFixture.raw(JwtFixture.json(JwtFixture.header("RS256", "kid-rsa")), "{oops", "AAAA");

    assertThat(reasonOf(verify(token))).isEqualTo(AuthenticationFailureReason.MALFORMED_TOKEN);
  }

  @Test
  void rejectsInvalidBase64() {
    assertThat(reasonOf(verify("!!!!.!!!!.!!!!")))
        .isEqualTo(AuthenticationFailureReason.MALFORMED_TOKEN);
  }

  @Test
  void rejectsWrongSegmentCounts() {
    assertThat(reasonOf(verify("only-one-segment")))
        .isEqualTo(AuthenticationFailureReason.MALFORMED_TOKEN);
    assertThat(reasonOf(verify("a.b"))).isEqualTo(AuthenticationFailureReason.MALFORMED_TOKEN);
    assertThat(reasonOf(verify("a.b.c.d"))).isEqualTo(AuthenticationFailureReason.MALFORMED_TOKEN);
  }

  @Test
  void rejectsDuplicateClaims() {
    // Last-one-wins parsing would let a token carry both a past and a future expiry.
    final String payload =
        "{\"iss\":\"" + JwtFixture.ISSUER + "\",\"exp\":1,\"exp\":9999999999,\"sub\":\"a\"}";
    final String token =
        JwtFixture.raw(JwtFixture.json(JwtFixture.header("RS256", "kid-rsa")), payload, "AAAA");

    assertThat(reasonOf(verify(token))).isEqualTo(AuthenticationFailureReason.MALFORMED_TOKEN);
  }

  @Test
  void rejectsAnOversizedToken() {
    final Map<String, Object> claims = JwtFixture.claims();
    claims.put("padding", "x".repeat(20_000));

    assertThat(reasonOf(verify(JwtFixture.sign(rsa, claims))))
        .isEqualTo(AuthenticationFailureReason.MALFORMED_TOKEN);
  }

  @Test
  void rejectsAMissingOrNonBearerAuthorizationHeader() {
    assertThat(
            reasonOf(
                verifier.verify(
                    new ForwardedTransportIdentity("Basic", "Basic abc", Map.of()), snapshot)))
        .isEqualTo(AuthenticationFailureReason.MALFORMED_TOKEN);
    assertThat(
            reasonOf(
                verifier.verify(new ForwardedTransportIdentity("Bearer", "", Map.of()), snapshot)))
        .isEqualTo(AuthenticationFailureReason.MALFORMED_TOKEN);
  }

  // ---- HS256 development path ------------------------------------------------------------------

  @Test
  void acceptsHs256WhenExplicitlyEnabledForDevelopment() {
    final JwtIdentityVerifier development =
        new JwtIdentityVerifier(
            new JwtAuthenticationConfig(
                JwtFixture.ISSUER,
                JwtFixture.AUDIENCE,
                Set.of(JwtAuthenticationConfig.HS256),
                Duration.ofSeconds(60),
                JwtAuthenticationConfig.DEFAULT_MAX_TOKEN_BYTES,
                JwtFixture.TENANT_CLAIM,
                Optional.of(JwtFixture.HMAC_SECRET)),
            JwtFixture.CLOCK);

    assertThat(
            development.verify(
                JwtFixture.bearer(JwtFixture.signHmac(JwtFixture.claims())), snapshot))
        .isInstanceOf(VerificationOutcome.Verified.class);
  }

  @Test
  void configurationRefusesAlgNoneAndOrphanedSecrets() {
    assertThatThrownBy(
            () ->
                new JwtAuthenticationConfig(
                    JwtFixture.ISSUER,
                    JwtFixture.AUDIENCE,
                    Set.of("none"),
                    Duration.ZERO,
                    1024,
                    "tid",
                    Optional.empty()))
        .isInstanceOf(IllegalArgumentException.class);

    assertThatThrownBy(
            () ->
                new JwtAuthenticationConfig(
                    JwtFixture.ISSUER,
                    JwtFixture.AUDIENCE,
                    Set.of(JwtAuthenticationConfig.HS256),
                    Duration.ZERO,
                    1024,
                    "tid",
                    Optional.empty()))
        .isInstanceOf(IllegalArgumentException.class);
  }

  // ---- determinism, concurrency, leakage --------------------------------------------------------

  @Test
  void verificationIsDeterministic() {
    final String token = JwtFixture.sign(rsa, JwtFixture.claims());
    final VerificationOutcome first = verify(token);

    for (int run = 0; run < 50; run++) {
      assertThat(verify(token)).isEqualTo(first);
    }
  }

  @Test
  void concurrentVerificationsAreIndependent() throws Exception {
    final int threads = 32;
    final String good = JwtFixture.sign(rsa, JwtFixture.claims());
    final Map<String, Object> expiredClaims = JwtFixture.claims();
    expiredClaims.put("exp", JwtFixture.NOW.getEpochSecond() - 100);
    final String expired = JwtFixture.sign(rsa, expiredClaims);

    final CountDownLatch go = new CountDownLatch(1);
    final CountDownLatch done = new CountDownLatch(threads);
    final List<String> wrong = new CopyOnWriteArrayList<>();

    for (int i = 0; i < threads; i++) {
      final boolean useGood = i % 2 == 0;
      Thread.ofVirtual()
          .start(
              () -> {
                try {
                  go.await();
                  final VerificationOutcome outcome = verify(useGood ? good : expired);
                  final boolean verified = outcome instanceof VerificationOutcome.Verified;
                  if (verified != useGood) {
                    wrong.add("mismatch");
                  }
                } catch (final InterruptedException e) {
                  Thread.currentThread().interrupt();
                } finally {
                  done.countDown();
                }
              });
    }

    go.countDown();
    assertThat(done.await(30, TimeUnit.SECONDS)).isTrue();
    assertThat(wrong).isEmpty();
  }

  @Test
  void rejectionsNeverCarryTokenOrKeyMaterial() {
    final Map<String, Object> claims = JwtFixture.claims();
    claims.put("secret", "super-secret-value");
    claims.put("iss", "https://attacker.example");
    final String token = JwtFixture.sign(rsa, claims);

    final VerificationOutcome outcome = verify(token);

    // The rejection is a bare enum: there is nowhere for a token, claim or signature to hide.
    final String rendered = outcome.toString();
    assertThat(rendered)
        .doesNotContain("super-secret-value")
        .doesNotContain(token)
        .doesNotContain("attacker.example");
  }
}

package io.reliabilityai.gateway.dataplane.auth.jwt;

import static java.nio.charset.StandardCharsets.UTF_8;
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

  @Test
  void aTokenWhoseClaimSetRepeatsAMemberIsRefusedAsMalformed() {
    // End-to-end proof that the parser's duplicate rejection reaches an authentication decision.
    // Decoding runs before signature verification, so an ambiguous claim set is refused on its
    // shape and never reaches the point where a signature could vouch for either reading. The
    // null-first form is included because that is the one the presence test previously let through.
    final String header = "{\"alg\":\"RS256\",\"typ\":\"JWT\",\"kid\":\"kid-rsa\"}";

    assertThat(
            reasonOf(verify(JwtFixture.raw(header, "{\"exp\":null,\"exp\":9999999999}", "AAAA"))))
        .isEqualTo(AuthenticationFailureReason.MALFORMED_TOKEN);
    assertThat(
            reasonOf(
                verify(JwtFixture.raw(header, "{\"tid\":null,\"tid\":\"other-tenant\"}", "AAAA"))))
        .isEqualTo(AuthenticationFailureReason.MALFORMED_TOKEN);
    assertThat(reasonOf(verify(JwtFixture.raw(header, "{\"sub\":\"a\",\"sub\":\"b\"}", "AAAA"))))
        .isEqualTo(AuthenticationFailureReason.MALFORMED_TOKEN);
  }

  @Test
  void anExpiryThatIsNotANumberIsTreatedAsNoExpiryAtAll() {
    // Type confusion is the cheap way to disable a temporal check: if a string expiry read as
    // "absent" and absent meant "fine", the token would never expire. Absent is refused, so the
    // wrong type has to be refused too.
    final Map<String, Object> claims = JwtFixture.claims();
    claims.put("exp", "9999999999");

    assertThat(reasonOf(verify(JwtFixture.sign(rsa, claims))))
        .isEqualTo(AuthenticationFailureReason.MALFORMED_TOKEN);
  }

  @Test
  void anExpiryTooLargeToBeAnInstantIsRefusedRatherThanTreatedAsNeverExpiring() {
    // 1e400 exceeds a double, so it arrives as infinity and saturates to Long.MAX_VALUE - a value
    // no Instant can hold. The failure has to collapse to a refusal; the alternative reading is an
    // expiry so far away the token is effectively immortal.
    final Map<String, Object> claims = JwtFixture.claims();
    claims.put("exp", new java.math.BigDecimal("1e400"));

    final VerificationOutcome outcome = verify(JwtFixture.sign(rsa, claims));

    assertThat(outcome).isInstanceOf(VerificationOutcome.Rejected.class);
    assertThat(reasonOf(outcome)).isEqualTo(AuthenticationFailureReason.INTERNAL);
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

  @Test
  void aBlankSubjectOrTenantIsNoIdentityAtAll() {
    // Present-but-empty is the gap between "the claim exists" and "the claim names someone". A
    // blank subject would become a principal with an empty id, and a blank tenant would resolve to
    // no tenant while still being treated as resolved - either one authorises a request nobody
    // owns. Whitespace is checked as well as the empty string, since neither names a principal.
    for (final String blank : List.of("", "   ")) {
      final Map<String, Object> blankSubject = JwtFixture.claims();
      blankSubject.put("sub", blank);
      assertThat(reasonOf(verify(JwtFixture.sign(rsa, blankSubject))))
          .isEqualTo(AuthenticationFailureReason.UNKNOWN_PRINCIPAL);

      final Map<String, Object> blankTenant = JwtFixture.claims();
      blankTenant.put(JwtFixture.TENANT_CLAIM, blank);
      assertThat(reasonOf(verify(JwtFixture.sign(rsa, blankTenant))))
          .isEqualTo(AuthenticationFailureReason.TENANT_UNRESOLVED);
    }
  }

  @Test
  void aSubjectOrTenantOfTheWrongTypeIsNoIdentityEither() {
    // The typed accessor returns nothing for a non-string, so a numeric or structural claim must
    // land on the same refusal as an absent one rather than being coerced into an identity.
    final Map<String, Object> numericSubject = JwtFixture.claims();
    numericSubject.put("sub", 12345);
    assertThat(reasonOf(verify(JwtFixture.sign(rsa, numericSubject))))
        .isEqualTo(AuthenticationFailureReason.UNKNOWN_PRINCIPAL);

    final Map<String, Object> numericTenant = JwtFixture.claims();
    numericTenant.put(JwtFixture.TENANT_CLAIM, 67890);
    assertThat(reasonOf(verify(JwtFixture.sign(rsa, numericTenant))))
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

  /** The development configuration, which is the only way the HMAC path becomes reachable. */
  private static JwtIdentityVerifier hmacVerifier(final Optional<byte[]> secret) {
    return new JwtIdentityVerifier(
        new JwtAuthenticationConfig(
            JwtFixture.ISSUER,
            JwtFixture.AUDIENCE,
            secret.isPresent()
                ? Set.of(JwtAuthenticationConfig.HS256)
                : Set.of(JwtAuthenticationConfig.RS256),
            Duration.ofSeconds(60),
            JwtAuthenticationConfig.DEFAULT_MAX_TOKEN_BYTES,
            JwtFixture.TENANT_CLAIM,
            secret),
        JwtFixture.CLOCK);
  }

  @Test
  void rejectsAnHs256TokenSignedWithTheWrongSecret() {
    // The whole point of the HMAC path. Every other HS256 test presents a correctly-signed token,
    // so nothing proved that a bad signature is refused — an accepted forgery here would be a
    // fully authenticated principal and tenant, admitted to governance, router and secrets.
    // The claim set is valid and the structure is well-formed; only the signature is wrong, so a
    // rejection can come from nothing but the comparison.
    final byte[] attackerSecret = "not-the-configured-development-secret".getBytes(UTF_8);
    final String forged = JwtFixture.signHmac(attackerSecret, JwtFixture.claims());

    final VerificationOutcome outcome =
        hmacVerifier(Optional.of(JwtFixture.HMAC_SECRET))
            .verify(JwtFixture.bearer(forged), snapshot);

    assertThat(outcome).isInstanceOf(VerificationOutcome.Rejected.class);
    assertThat(reasonOf(outcome)).isEqualTo(AuthenticationFailureReason.INVALID_SIGNATURE);
  }

  @Test
  void rejectsAnHs256TokenWhoseSignatureHasBeenTruncatedOrEmptied() {
    // A signature of the wrong length must fail the comparison rather than the array handling.
    final String valid = JwtFixture.signHmac(JwtFixture.claims());
    final String truncated = valid.substring(0, valid.length() - 4);
    final JwtIdentityVerifier development = hmacVerifier(Optional.of(JwtFixture.HMAC_SECRET));

    assertThat(reasonOf(development.verify(JwtFixture.bearer(truncated), snapshot)))
        .isEqualTo(AuthenticationFailureReason.INVALID_SIGNATURE);
  }

  @Test
  void rejectsAnHs256TokenWhenTheDevelopmentPathIsNotEnabled() {
    // With HS256 absent from the allow-list the token is refused before any comparison happens,
    // so a deployment that never opted into the development path cannot be handed one.
    final String token = JwtFixture.signHmac(JwtFixture.claims());

    assertThat(reasonOf(hmacVerifier(Optional.empty()).verify(JwtFixture.bearer(token), snapshot)))
        .isEqualTo(AuthenticationFailureReason.ALGORITHM_MISMATCH);
  }

  // ---- fail-closed boundaries -------------------------------------------------------------------

  @Test
  void missingArgumentsAreRefusedRatherThanDereferenced() {
    // verify() is called by the authentication service on every request; a null here must be a
    // refusal, never a thrown exception the caller has to interpret.
    final String token = JwtFixture.sign(rsa, JwtFixture.claims());

    assertThat(reasonOf(verifier.verify(null, snapshot)))
        .isEqualTo(AuthenticationFailureReason.INTERNAL);
    assertThat(reasonOf(verifier.verify(JwtFixture.bearer(token), null)))
        .isEqualTo(AuthenticationFailureReason.INTERNAL);
    assertThat(reasonOf(verifier.verify(null, null)))
        .isEqualTo(AuthenticationFailureReason.INTERNAL);
  }

  @Test
  void aClockThatThrowsBecomesARefusalNotAnEscapingException() {
    // The clock is consulted while validating expiry. Any collaborator failure has to collapse to
    // a refusal: an exception escaping verification would leave the caller with no decision at
    // all, and the one thing that must never follow is an authenticated request.
    final JwtIdentityVerifier broken =
        new JwtIdentityVerifier(
            JwtFixture.config(),
            () -> {
              throw new IllegalStateException("no clock");
            });

    final VerificationOutcome outcome =
        broken.verify(JwtFixture.bearer(JwtFixture.sign(rsa, JwtFixture.claims())), snapshot);

    assertThat(outcome).isInstanceOf(VerificationOutcome.Rejected.class);
    assertThat(reasonOf(outcome)).isEqualTo(AuthenticationFailureReason.INTERNAL);
  }

  @Test
  void anEncryptedTokenIsRefusedRatherThanTreatedAsSigned() {
    // A JWE carries no verifiable signature in the JWS sense; accepting one would mean trusting
    // claims nothing has authenticated.
    final Map<String, Object> header = JwtFixture.header("RS256", rsa.kid());
    header.put("typ", "JWE");

    assertThat(reasonOf(verify(JwtFixture.sign(rsa, header, JwtFixture.claims()))))
        .isEqualTo(AuthenticationFailureReason.MALFORMED_TOKEN);
  }

  // ---- JWKS never overrides the control plane ---------------------------------------------------

  /** A cache already holding one refreshed generation serving exactly this key. */
  private static JwksKeyCache cacheServing(final JwtFixture.Keys keys) {
    final JwksKeyCache cache =
        new JwksKeyCache(
            () -> JwtFixture.jwksDocument(keys), Duration.ofMinutes(10), JwtFixture.CLOCK);
    cache.refresh();
    return cache;
  }

  @Test
  void aKeyKnownOnlyToJwksIsMergedSoTheTokenVerifies() {
    // The reason the merge exists: a kid the control plane has not published yet, but the identity
    // provider has. Without this the pinning tests below would also pass if the merge never ran at
    // all, which would prove nothing about precedence.
    final JwtFixture.Keys jwksOnly = JwtFixture.rsa("kid-jwks-only");
    final JwtIdentityVerifier withJwks =
        new JwtIdentityVerifier(
            JwtFixture.config(), JwtFixture.CLOCK, Optional.of(cacheServing(jwksOnly)));

    assertThat(
            withJwks.verify(
                JwtFixture.bearer(JwtFixture.sign(jwksOnly, JwtFixture.claims())),
                JwtFixture.snapshot(rsa)))
        .isInstanceOf(VerificationOutcome.Verified.class);
  }

  @Test
  void aSnapshotKeyWinsOverAJwksKeyPublishedUnderTheSameKid() {
    // The operator pins keys in the snapshot; a JWKS document is a convenience, not an authority.
    // Here the JWKS serves a *different* key under the same kid, so if the merge preferred JWKS
    // the token signed by the pinned key would stop verifying — and, worse, a token signed by
    // whoever controls the JWKS endpoint would start verifying.
    final JwtFixture.Keys pinned = JwtFixture.rsa("kid-shared");
    final JwtFixture.Keys impostor = JwtFixture.rsa("kid-shared");
    final JwtIdentityVerifier withJwks =
        new JwtIdentityVerifier(
            JwtFixture.config(), JwtFixture.CLOCK, Optional.of(cacheServing(impostor)));

    assertThat(
            withJwks.verify(
                JwtFixture.bearer(JwtFixture.sign(pinned, JwtFixture.claims())),
                JwtFixture.snapshot(pinned)))
        .isInstanceOf(VerificationOutcome.Verified.class);
    // The impostor's own token is refused: its kid resolves to the pinned key, whose public half
    // does not verify that signature.
    assertThat(
            reasonOf(
                withJwks.verify(
                    JwtFixture.bearer(JwtFixture.sign(impostor, JwtFixture.claims())),
                    JwtFixture.snapshot(pinned))))
        .isEqualTo(AuthenticationFailureReason.INVALID_SIGNATURE);
  }

  @Test
  void aRevokedKidIsNeverResurrectedFromJwks() {
    // Revocation is the control plane's emergency stop. If a JWKS document could re-supply a
    // revoked kid, revoking a compromised key would not actually stop tokens signed with it.
    final JwtFixture.Keys revoked = JwtFixture.rsa("kid-revoked");
    final JwtIdentityVerifier withJwks =
        new JwtIdentityVerifier(
            JwtFixture.config(), JwtFixture.CLOCK, Optional.of(cacheServing(revoked)));

    final VerificationOutcome outcome =
        withJwks.verify(
            JwtFixture.bearer(JwtFixture.sign(revoked, JwtFixture.claims())),
            JwtFixture.snapshot(List.of("kid-revoked"), rsa));

    assertThat(outcome).isInstanceOf(VerificationOutcome.Rejected.class);
    assertThat(reasonOf(outcome)).isEqualTo(AuthenticationFailureReason.KEY_REVOKED);
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

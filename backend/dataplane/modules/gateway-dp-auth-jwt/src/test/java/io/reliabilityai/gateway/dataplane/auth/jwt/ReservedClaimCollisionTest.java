package io.reliabilityai.gateway.dataplane.auth.jwt;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.reliabilityai.gateway.dataplane.authn.api.VerificationOutcome;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * A tenant claim named like one of the claims the verifier writes itself must not stop the verifier
 * being built.
 *
 * <p>Naming the tenant claim {@code "iss"} or {@code "kid"} is unusual but legal — nothing in the
 * configuration forbids it. An earlier revision assembled the reserved-name set with {@code
 * Set.of(...)}, which throws on a duplicate element, so such a deployment failed to construct a
 * verifier that had nothing wrong with it. These tests pin the additive construction that replaced
 * it, and they check authentication still works rather than merely that no exception escaped.
 */
class ReservedClaimCollisionTest {

  private static JwtAuthenticationConfig configWithTenantClaim(final String tenantClaim) {
    return JwtAuthenticationConfig.production(JwtFixture.ISSUER, JwtFixture.AUDIENCE, tenantClaim);
  }

  @ParameterizedTest
  @ValueSource(strings = {"iss", "aud", "alg", "kid"})
  void aTenantClaimNamedLikeAReservedClaimStillBuildsAVerifier(final String tenantClaim) {
    assertThatCode(
            () ->
                new JwtIdentityVerifier(
                    configWithTenantClaim(tenantClaim),
                    JwtFixture.CLOCK,
                    Optional.empty(),
                    Set.of()))
        .doesNotThrowAnyException();
  }

  @ParameterizedTest
  @ValueSource(strings = {"iss", "aud", "alg", "kid"})
  void aTenantClaimNamedLikeAReservedClaimStillAuthenticates(final String tenantClaim) {
    final JwtFixture.Keys keys = JwtFixture.rsa("k1");
    final JwtIdentityVerifier verifier =
        new JwtIdentityVerifier(
            configWithTenantClaim(tenantClaim), JwtFixture.CLOCK, Optional.empty(), Set.of());

    // "iss"/"aud" are present in the payload; "alg"/"kid" live in the header, so a payload entry is
    // added so the tenant claim resolves for every case under test.
    final var claims = new java.util.HashMap<>(JwtFixture.claims());
    claims.putIfAbsent(tenantClaim, "tenant-from-reserved-name");

    final VerificationOutcome outcome =
        verifier.verify(
            JwtFixture.bearer(JwtFixture.sign(keys, claims)), JwtFixture.snapshot(keys));

    assertThat(outcome).isInstanceOf(VerificationOutcome.Verified.class);
  }

  @Test
  void aGovernanceClaimCollidingWithTheTenantClaimIsStillRejected() {
    // The collision defence must survive the additive rewrite: this must throw because the name is
    // reserved, not because a set builder rejected a duplicate.
    assertThatThrownBy(
            () ->
                new JwtIdentityVerifier(
                    configWithTenantClaim("tid"),
                    JwtFixture.CLOCK,
                    Optional.empty(),
                    Set.of("tid")))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("tid");
  }

  @Test
  void aGovernanceClaimCollidingWithAFixedReservedNameIsStillRejected() {
    assertThatThrownBy(
            () ->
                new JwtIdentityVerifier(
                    configWithTenantClaim("tid"),
                    JwtFixture.CLOCK,
                    Optional.empty(),
                    Set.of("kid")))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("kid");
  }

  @Test
  void aGovernanceClaimIsStillAcceptedWhenTheTenantClaimSharesAReservedName() {
    // The interesting combination: tenant claim collides with a fixed reserved name *and* a
    // governance claim is declared. Both defences must hold at once.
    final JwtFixture.Keys keys = JwtFixture.rsa("k1");
    final JwtIdentityVerifier verifier =
        new JwtIdentityVerifier(
            configWithTenantClaim("iss"), JwtFixture.CLOCK, Optional.empty(), Set.of("akid"));

    final var claims = new java.util.HashMap<>(JwtFixture.claims());
    claims.put("akid", "key-7");

    final VerificationOutcome outcome =
        verifier.verify(
            JwtFixture.bearer(JwtFixture.sign(keys, claims)), JwtFixture.snapshot(keys));

    assertThat(outcome)
        .isInstanceOfSatisfying(
            VerificationOutcome.Verified.class,
            verified -> assertThat(verified.claims()).containsEntry("akid", "key-7"));
  }
}

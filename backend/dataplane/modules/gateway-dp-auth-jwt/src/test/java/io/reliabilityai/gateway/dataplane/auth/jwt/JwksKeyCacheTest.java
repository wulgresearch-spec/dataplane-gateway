package io.reliabilityai.gateway.dataplane.auth.jwt;

import static org.assertj.core.api.Assertions.assertThat;

import io.reliabilityai.gateway.canonical.snapshot.VerificationKeySnapshot;
import io.reliabilityai.gateway.dataplane.authn.api.VerificationOutcome;
import io.reliabilityai.gateway.dataplane.authn.domain.AuthenticationFailureReason;
import io.reliabilityai.gateway.ports.ClockPort;
import java.security.interfaces.ECPublicKey;
import java.security.interfaces.RSAPublicKey;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

/** JWKS caching: refresh, rotation, TTL expiry, and cache-only lookups on the request path. */
class JwksKeyCacheTest {

  /** A clock the test advances explicitly, so TTL behaviour never depends on wall time. */
  private static final class MovableClock implements ClockPort {
    private Instant now = Instant.parse("2026-01-01T00:00:00Z");

    @Override
    public Instant now() {
      return now;
    }

    void advance(final Duration by) {
      now = now.plus(by);
    }
  }

  private final MovableClock clock = new MovableClock();
  private final AtomicInteger fetches = new AtomicInteger();
  private final AtomicReference<String> document = new AtomicReference<>();

  private JwksKeyCache cache(final Duration ttl) {
    return new JwksKeyCache(
        () -> {
          fetches.incrementAndGet();
          final String current = document.get();
          if (current == null) {
            throw new IllegalStateException("jwks unavailable");
          }
          return current;
        },
        ttl,
        clock);
  }

  /** Renders a JWK document from generated key pairs, in the shape real IdPs publish. */
  private static String jwks(final JwtFixture.Keys... keys) {
    final StringBuilder out = new StringBuilder("{\"keys\":[");
    for (int i = 0; i < keys.length; i++) {
      if (i > 0) {
        out.append(',');
      }
      out.append(jwk(keys[i]));
    }
    return out.append("]}").toString();
  }

  private static String jwk(final JwtFixture.Keys keys) {
    final Base64.Encoder url = Base64.getUrlEncoder().withoutPadding();
    if (keys.keyPair().getPublic() instanceof RSAPublicKey rsa) {
      return "{\"kty\":\"RSA\",\"use\":\"sig\",\"alg\":\"RS256\",\"kid\":\""
          + keys.kid()
          + "\",\"n\":\""
          + url.encodeToString(unsigned(rsa.getModulus().toByteArray()))
          + "\",\"e\":\""
          + url.encodeToString(unsigned(rsa.getPublicExponent().toByteArray()))
          + "\"}";
    }
    final ECPublicKey ec = (ECPublicKey) keys.keyPair().getPublic();
    return "{\"kty\":\"EC\",\"use\":\"sig\",\"alg\":\"ES256\",\"crv\":\"P-256\",\"kid\":\""
        + keys.kid()
        + "\",\"x\":\""
        + url.encodeToString(fixed(ec.getW().getAffineX().toByteArray()))
        + "\",\"y\":\""
        + url.encodeToString(fixed(ec.getW().getAffineY().toByteArray()))
        + "\"}";
  }

  private static byte[] unsigned(final byte[] value) {
    return value.length > 1 && value[0] == 0
        ? java.util.Arrays.copyOfRange(value, 1, value.length)
        : value;
  }

  private static byte[] fixed(final byte[] value) {
    final byte[] out = new byte[32];
    final byte[] trimmed = unsigned(value);
    System.arraycopy(trimmed, 0, out, 32 - trimmed.length, Math.min(32, trimmed.length));
    return out;
  }

  // ---- refresh and lookup ----------------------------------------------------------------------

  @Test
  void lookupReturnsNothingBeforeAnyRefresh() {
    final JwksKeyCache jwks = cache(Duration.ofMinutes(10));

    assertThat(jwks.lookup("kid-a")).isEmpty();
    assertThat(jwks.isStale()).isTrue();
    // A lookup must never fetch: the request path may not depend on the identity provider.
    assertThat(fetches.get()).isZero();
  }

  @Test
  void refreshPopulatesTheCacheForRsaAndEcKeys() {
    document.set(jwks(JwtFixture.rsa("kid-rsa"), JwtFixture.ec("kid-ec")));
    final JwksKeyCache jwks = cache(Duration.ofMinutes(10));

    assertThat(jwks.refresh()).isTrue();
    assertThat(jwks.cachedKeyIds()).containsExactlyInAnyOrder("kid-rsa", "kid-ec");
    assertThat(jwks.lookup("kid-rsa")).isPresent();
    assertThat(jwks.lookup("kid-ec")).isPresent();
    assertThat(jwks.isStale()).isFalse();
  }

  @Test
  void repeatedLookupsHitTheCacheWithoutRefetching() {
    document.set(jwks(JwtFixture.rsa("kid-rsa")));
    final JwksKeyCache jwks = cache(Duration.ofMinutes(10));
    jwks.refresh();
    final int afterRefresh = fetches.get();

    for (int i = 0; i < 100; i++) {
      assertThat(jwks.lookup("kid-rsa")).isPresent();
    }

    assertThat(fetches.get()).isEqualTo(afterRefresh);
  }

  @Test
  void cacheExpiresAfterTheTtlAndStopsAnsweringLookups() {
    document.set(jwks(JwtFixture.rsa("kid-rsa")));
    final JwksKeyCache jwks = cache(Duration.ofMinutes(10));
    jwks.refresh();
    assertThat(jwks.lookup("kid-rsa")).isPresent();

    clock.advance(Duration.ofMinutes(11));

    // Stale keys may since have been revoked; answering from them would be a guess.
    assertThat(jwks.isStale()).isTrue();
    assertThat(jwks.lookup("kid-rsa")).isEmpty();
  }

  @Test
  void refreshAfterExpiryRestoresLookups() {
    document.set(jwks(JwtFixture.rsa("kid-rsa")));
    final JwksKeyCache jwks = cache(Duration.ofMinutes(10));
    jwks.refresh();
    clock.advance(Duration.ofMinutes(11));

    assertThat(jwks.refresh()).isTrue();
    assertThat(jwks.lookup("kid-rsa")).isPresent();
  }

  @Test
  void rotationPublishesTheNewKeyWhileTheOldOneOverlaps() {
    final JwtFixture.Keys oldKey = JwtFixture.rsa("kid-old");
    final JwtFixture.Keys newKey = JwtFixture.rsa("kid-new");
    document.set(jwks(oldKey));
    final JwksKeyCache jwks = cache(Duration.ofMinutes(10));
    jwks.refresh();

    // Overlap window: both generations verify, so in-flight tokens are not invalidated.
    document.set(jwks(oldKey, newKey));
    assertThat(jwks.refresh()).isTrue();
    assertThat(jwks.lookup("kid-old")).isPresent();
    assertThat(jwks.lookup("kid-new")).isPresent();

    // Retirement: the old key disappears once no token can still carry it.
    document.set(jwks(newKey));
    assertThat(jwks.refresh()).isTrue();
    assertThat(jwks.lookup("kid-old")).isEmpty();
    assertThat(jwks.lookup("kid-new")).isPresent();
  }

  @Test
  void aFailedFetchLeavesTheLastKnownGoodGenerationInPlace() {
    document.set(jwks(JwtFixture.rsa("kid-rsa")));
    final JwksKeyCache jwks = cache(Duration.ofMinutes(10));
    jwks.refresh();

    document.set(null); // the identity provider is down

    assertThat(jwks.refresh()).isFalse();
    // Last-known-good beats no keys at all; the TTL still bounds how long it may be trusted.
    assertThat(jwks.lookup("kid-rsa")).isPresent();
  }

  @Test
  void anEmptyOrMalformedDocumentIsRejectedRatherThanRevokingEveryKey() {
    document.set(jwks(JwtFixture.rsa("kid-rsa")));
    final JwksKeyCache jwks = cache(Duration.ofMinutes(10));
    jwks.refresh();

    document.set("{\"keys\":[]}");
    assertThat(jwks.refresh()).isFalse();
    assertThat(jwks.lookup("kid-rsa")).isPresent();

    document.set("{not json");
    assertThat(jwks.refresh()).isFalse();
    assertThat(jwks.lookup("kid-rsa")).isPresent();
  }

  @Test
  void aJwkWhoseAlgorithmDisagreesWithItsKeyTypeIsDropped() {
    // An RSA key labelled ES256 is broken or hostile; either way it must not be usable.
    final JwtFixture.Keys rsa = JwtFixture.rsa("kid-mislabelled");
    document.set(jwks(rsa).replace("\"alg\":\"RS256\"", "\"alg\":\"ES256\""));
    final JwksKeyCache jwks = cache(Duration.ofMinutes(10));

    assertThat(jwks.refresh()).isFalse();
    assertThat(jwks.lookup("kid-mislabelled")).isEmpty();
  }

  @Test
  void encryptionOnlyKeysAreIgnored() {
    document.set(jwks(JwtFixture.rsa("kid-enc")).replace("\"use\":\"sig\"", "\"use\":\"enc\""));
    final JwksKeyCache jwks = cache(Duration.ofMinutes(10));

    assertThat(jwks.refresh()).isFalse();
  }

  // ---- integration with the verifier -----------------------------------------------------------

  @Test
  void aTokenSignedByAJwksOnlyKeyVerifies() {
    final JwtFixture.Keys jwksOnly = JwtFixture.rsa("kid-jwks");
    document.set(jwks(jwksOnly));
    final JwksKeyCache jwks = cache(Duration.ofMinutes(10));
    jwks.refresh();

    final JwtIdentityVerifier verifier =
        new JwtIdentityVerifier(JwtFixture.config(), JwtFixture.CLOCK, Optional.of(jwks));
    // The snapshot knows nothing about this kid; only the JWKS cache does.
    final VerificationKeySnapshot empty = JwtFixture.snapshot();

    assertThat(
            verifier.verify(
                JwtFixture.bearer(JwtFixture.sign(jwksOnly, JwtFixture.claims())), empty))
        .isInstanceOf(VerificationOutcome.Verified.class);
  }

  @Test
  void aSnapshotRevocationOverridesTheJwksCache() {
    final JwtFixture.Keys revokedKey = JwtFixture.rsa("kid-revoked");
    document.set(jwks(revokedKey));
    final JwksKeyCache jwks = cache(Duration.ofMinutes(10));
    jwks.refresh();

    final JwtIdentityVerifier verifier =
        new JwtIdentityVerifier(JwtFixture.config(), JwtFixture.CLOCK, Optional.of(jwks));
    final VerificationKeySnapshot revoked = JwtFixture.snapshot(List.of("kid-revoked"));

    // The control plane is authoritative: a JWKS document must never resurrect a revoked key.
    assertThat(
            ((VerificationOutcome.Rejected)
                    verifier.verify(
                        JwtFixture.bearer(JwtFixture.sign(revokedKey, JwtFixture.claims())),
                        revoked))
                .reason())
        .isEqualTo(AuthenticationFailureReason.KEY_REVOKED);
  }

  @Test
  void aStaleCacheStopsVerifyingJwksOnlyTokens() {
    final JwtFixture.Keys jwksOnly = JwtFixture.rsa("kid-jwks");
    document.set(jwks(jwksOnly));
    final JwksKeyCache jwks = cache(Duration.ofMinutes(10));
    jwks.refresh();
    clock.advance(Duration.ofMinutes(11));

    final JwtIdentityVerifier verifier =
        new JwtIdentityVerifier(JwtFixture.config(), JwtFixture.CLOCK, Optional.of(jwks));

    assertThat(
            ((VerificationOutcome.Rejected)
                    verifier.verify(
                        JwtFixture.bearer(JwtFixture.sign(jwksOnly, JwtFixture.claims())),
                        JwtFixture.snapshot()))
                .reason())
        .isEqualTo(AuthenticationFailureReason.UNKNOWN_KEY);
  }
}

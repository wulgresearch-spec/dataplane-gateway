package io.reliabilityai.gateway.dataplane.auth.jwt;

import io.reliabilityai.gateway.canonical.identity.PrincipalId;
import io.reliabilityai.gateway.canonical.io.ForwardedTransportIdentity;
import io.reliabilityai.gateway.canonical.snapshot.JwsAlgorithm;
import io.reliabilityai.gateway.canonical.snapshot.VerificationKey;
import io.reliabilityai.gateway.canonical.snapshot.VerificationKeySnapshot;
import io.reliabilityai.gateway.common.Preconditions;
import io.reliabilityai.gateway.dataplane.auth.jwt.internal.DecodedJwt;
import io.reliabilityai.gateway.dataplane.auth.jwt.internal.JwtJson;
import io.reliabilityai.gateway.dataplane.authn.api.IdentityVerifierPort;
import io.reliabilityai.gateway.dataplane.authn.api.VerificationOutcome;
import io.reliabilityai.gateway.dataplane.authn.domain.AuthenticationFailureReason;
import io.reliabilityai.gateway.dataplane.authn.domain.JdkJwsSignatureVerifier;
import io.reliabilityai.gateway.dataplane.authn.domain.KeyResolution;
import io.reliabilityai.gateway.dataplane.authn.domain.VerificationKeyRegistry;
import io.reliabilityai.gateway.ports.ClockPort;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * Verifies JWT access tokens behind the frozen {@link IdentityVerifierPort} (Doc 37, C6).
 *
 * <p><b>Order matters and is deliberate.</b> Cheap structural checks run before expensive
 * cryptography, but nothing about the token is <em>trusted</em> until the signature verifies. In
 * particular the algorithm is chosen from the configured allow-list and the resolved key's own type
 * — never from the token's {@code alg} header alone. That is what closes the algorithm-confusion
 * family of attacks, where a token claims {@code HS256} and tricks a verifier into using an RSA
 * public key as an HMAC secret.
 *
 * <p><b>Nothing about a token is logged, returned or thrown.</b> No token, claim, signature or key
 * material appears in any exception, message or return value. Every rejection collapses to a frozen
 * {@link AuthenticationFailureReason} — the audit trail records which check failed, never what the
 * attacker sent.
 *
 * <p><b>Fail closed.</b> Any unexpected failure — a parser error, a JCA error, a malformed key — is
 * a rejection, never an acceptance and never a propagated exception.
 */
public final class JwtIdentityVerifier implements IdentityVerifierPort {

  private static final String BEARER = "bearer ";
  private static final String AUTH_METHOD = "jwt";

  private final JwtAuthenticationConfig config;
  private final ClockPort clock;
  private final JdkJwsSignatureVerifier signatureVerifier;
  private final Optional<JwksKeyCache> jwks;

  /**
   * Creates the verifier using only snapshot-supplied keys.
   *
   * @param config the verification policy
   * @param clock the injected clock, used for expiry and not-before
   */
  public JwtIdentityVerifier(final JwtAuthenticationConfig config, final ClockPort clock) {
    this(config, clock, Optional.empty());
  }

  /**
   * Creates the verifier with an additional JWKS-backed key source.
   *
   * @param config the verification policy
   * @param clock the injected clock
   * @param jwks the cached JWKS keys, consulted only when the snapshot does not know the {@code
   *     kid}
   */
  public JwtIdentityVerifier(
      final JwtAuthenticationConfig config,
      final ClockPort clock,
      final Optional<JwksKeyCache> jwks) {
    this.config = Preconditions.requireNonNull(config, "config");
    this.clock = Preconditions.requireNonNull(clock, "clock");
    this.jwks = Preconditions.requireNonNull(jwks, "jwks");
    this.signatureVerifier = new JdkJwsSignatureVerifier();
  }

  @Override
  public VerificationOutcome verify(
      final ForwardedTransportIdentity transportIdentity,
      final VerificationKeySnapshot keySnapshot) {
    if (transportIdentity == null || keySnapshot == null) {
      return reject(AuthenticationFailureReason.INTERNAL);
    }
    try {
      return doVerify(transportIdentity, keySnapshot);
    } catch (final RuntimeException unexpected) {
      // A parser or provider fault says nothing about whether this caller is who they claim to be.
      return reject(AuthenticationFailureReason.INTERNAL);
    }
  }

  private VerificationOutcome doVerify(
      final ForwardedTransportIdentity transportIdentity,
      final VerificationKeySnapshot keySnapshot) {

    final String token = bearerToken(transportIdentity.credentialMaterial());
    if (token == null) {
      return reject(AuthenticationFailureReason.MALFORMED_TOKEN);
    }
    // Bound the work before doing any: an oversized token must cost us a length check, not a parse.
    if (token.getBytes(StandardCharsets.UTF_8).length > config.maxTokenBytes()) {
      return reject(AuthenticationFailureReason.MALFORMED_TOKEN);
    }

    final DecodedJwt decoded;
    try {
      decoded = DecodedJwt.decode(token);
    } catch (final DecodedJwt.MalformedTokenException malformed) {
      return reject(AuthenticationFailureReason.MALFORMED_TOKEN);
    }

    final String headerAlgorithm = JwtJson.stringAt(decoded.header(), "alg");
    if (headerAlgorithm == null || !config.allowedAlgorithms().contains(headerAlgorithm)) {
      // Covers alg=none, alg absent, and anything outside the allow-list.
      return reject(AuthenticationFailureReason.ALGORITHM_MISMATCH);
    }
    if ("JWE".equalsIgnoreCase(JwtJson.stringAt(decoded.header(), "typ"))) {
      return reject(AuthenticationFailureReason.MALFORMED_TOKEN);
    }

    final VerificationOutcome signatureFailure =
        JwtAuthenticationConfig.HS256.equals(headerAlgorithm)
            ? verifyHmac(decoded)
            : verifyAsymmetric(decoded, headerAlgorithm, keySnapshot);
    if (signatureFailure != null) {
      return signatureFailure;
    }

    return verifyClaims(decoded);
  }

  // ---- signature ------------------------------------------------------------------------------

  /**
   * Verifies an asymmetric signature.
   *
   * @return a rejection outcome, or {@code null} when the signature is good
   */
  private VerificationOutcome verifyAsymmetric(
      final DecodedJwt decoded,
      final String headerAlgorithm,
      final VerificationKeySnapshot keySnapshot) {

    final Optional<JwsAlgorithm> algorithm = JwsAlgorithm.fromHeader(headerAlgorithm);
    if (algorithm.isEmpty()) {
      return reject(AuthenticationFailureReason.ALGORITHM_MISMATCH);
    }
    final String kid = JwtJson.stringAt(decoded.header(), "kid");

    final VerificationKeySnapshot effective = withJwksKeys(keySnapshot, kid);
    final VerificationKeyRegistry registry;
    try {
      registry = new VerificationKeyRegistry(effective);
    } catch (final IllegalArgumentException unusableSnapshot) {
      return reject(AuthenticationFailureReason.KEY_SNAPSHOT_UNAVAILABLE);
    }

    final KeyResolution resolution = registry.resolve(kid, algorithm.orElseThrow());
    if (resolution instanceof KeyResolution.Rejected rejected) {
      return reject(rejected.reason());
    }
    final KeyResolution.Resolved resolved = (KeyResolution.Resolved) resolution;

    final boolean valid =
        signatureVerifier.verify(
            resolved.algorithm(),
            resolved.publicKey(),
            decoded.signingInput(),
            decoded.signature());
    return valid ? null : reject(AuthenticationFailureReason.INVALID_SIGNATURE);
  }

  /**
   * Merges a JWKS-cached key into the snapshot when the snapshot does not carry the presented kid.
   *
   * <p>Snapshot keys win: the control plane is authoritative, and a JWKS document must never be
   * able to silently override a key the operator pinned or revoked.
   */
  private VerificationKeySnapshot withJwksKeys(
      final VerificationKeySnapshot snapshot, final String kid) {
    if (jwks.isEmpty() || kid == null || kid.isBlank()) {
      return snapshot;
    }
    final boolean known = snapshot.keys().stream().anyMatch(key -> kid.equals(key.kid()));
    if (known || snapshot.revokedKeyIds().contains(kid)) {
      return snapshot;
    }
    final Optional<VerificationKey> cached = jwks.orElseThrow().lookup(kid);
    if (cached.isEmpty()) {
      return snapshot;
    }
    final List<VerificationKey> merged = new ArrayList<>(snapshot.keys());
    merged.add(cached.orElseThrow());
    return new VerificationKeySnapshot(
        snapshot.version(), snapshot.region(), List.copyOf(merged), snapshot.revokedKeyIds());
  }

  /**
   * Verifies an HS256 signature against the development shared secret.
   *
   * <p>Symmetric verification is deliberately kept on a separate path that can only ever reach the
   * configured development secret. It never consults the key snapshot, so a token claiming {@code
   * HS256} cannot be verified against an RSA public key — the classic algorithm-confusion
   * escalation. Comparison is constant-time.
   *
   * @return a rejection outcome, or {@code null} when the signature is good
   */
  private VerificationOutcome verifyHmac(final DecodedJwt decoded) {
    if (!config.hmacEnabled()) {
      return reject(AuthenticationFailureReason.ALGORITHM_MISMATCH);
    }
    final byte[] expected;
    try {
      final Mac mac = Mac.getInstance("HmacSHA256");
      mac.init(new SecretKeySpec(config.developmentHmacSecret().orElseThrow(), "HmacSHA256"));
      expected = mac.doFinal(decoded.signingInput());
    } catch (final GeneralSecurityException | RuntimeException macFailure) {
      return reject(AuthenticationFailureReason.INTERNAL);
    }
    return MessageDigest.isEqual(expected, decoded.signature())
        ? null
        : reject(AuthenticationFailureReason.INVALID_SIGNATURE);
  }

  // ---- claims ---------------------------------------------------------------------------------

  /** Validates the claim set of an already signature-verified token. */
  private VerificationOutcome verifyClaims(final DecodedJwt decoded) {
    final Map<String, Object> payload = decoded.payload();

    if (!config.issuer().equals(JwtJson.stringAt(payload, "iss"))) {
      return reject(AuthenticationFailureReason.ISSUER_REJECTED);
    }
    if (!JwtJson.audienceAt(payload, "aud").contains(config.audience())) {
      return reject(AuthenticationFailureReason.AUDIENCE_REJECTED);
    }

    final Instant now = clock.now();
    final long skewSeconds = config.clockSkew().toSeconds();

    final Long expiry = JwtJson.numberAt(payload, "exp");
    if (expiry == null) {
      // A token without an expiry never stops being valid; that is not an access token.
      return reject(AuthenticationFailureReason.MALFORMED_TOKEN);
    }
    if (!now.minusSeconds(skewSeconds).isBefore(Instant.ofEpochSecond(expiry))) {
      return reject(AuthenticationFailureReason.EXPIRED);
    }

    final Long notBefore = JwtJson.numberAt(payload, "nbf");
    if (notBefore != null
        && now.plusSeconds(skewSeconds).isBefore(Instant.ofEpochSecond(notBefore))) {
      return reject(AuthenticationFailureReason.NOT_YET_VALID);
    }

    final Long issuedAt = JwtJson.numberAt(payload, "iat");
    if (issuedAt != null
        && now.plusSeconds(skewSeconds).isBefore(Instant.ofEpochSecond(issuedAt))) {
      // Issued in the future: either a clock problem or a forged timestamp; refuse either way.
      return reject(AuthenticationFailureReason.NOT_YET_VALID);
    }

    final String subject = JwtJson.stringAt(payload, "sub");
    if (subject == null || subject.isBlank()) {
      return reject(AuthenticationFailureReason.UNKNOWN_PRINCIPAL);
    }

    final String tenant = JwtJson.stringAt(payload, config.tenantClaim());
    if (tenant == null || tenant.isBlank()) {
      return reject(AuthenticationFailureReason.TENANT_UNRESOLVED);
    }

    return new VerificationOutcome.Verified(
        new PrincipalId(subject), safeClaims(decoded, tenant), AUTH_METHOD);
  }

  /**
   * The curated claim set handed downstream.
   *
   * <p>Deliberately <b>not</b> the whole payload. Everything returned here flows into the principal
   * context and from there into audit records; forwarding arbitrary issuer-controlled claims would
   * push unknown, possibly personal data into the audit trail. Only the values the gateway actually
   * reasons about are propagated.
   */
  private Map<String, String> safeClaims(final DecodedJwt decoded, final String tenant) {
    final Map<String, String> claims = new LinkedHashMap<>();
    claims.put("iss", config.issuer());
    claims.put("aud", config.audience());
    claims.put("alg", String.valueOf(JwtJson.stringAt(decoded.header(), "alg")));
    claims.put(config.tenantClaim(), tenant);
    final String kid = JwtJson.stringAt(decoded.header(), "kid");
    if (kid != null && !kid.isBlank()) {
      claims.put("kid", kid);
    }
    return Map.copyOf(claims);
  }

  // ---- helpers --------------------------------------------------------------------------------

  /** Extracts the token from an {@code Authorization} header value, tolerating scheme casing. */
  private static String bearerToken(final String credentialMaterial) {
    if (credentialMaterial == null || credentialMaterial.isBlank()) {
      return null;
    }
    final String trimmed = credentialMaterial.trim();
    if (trimmed.length() <= BEARER.length()
        || !trimmed.regionMatches(true, 0, BEARER, 0, BEARER.length())) {
      return null;
    }
    final String token = trimmed.substring(BEARER.length()).trim();
    return token.isEmpty() ? null : token;
  }

  private static VerificationOutcome reject(final AuthenticationFailureReason reason) {
    return new VerificationOutcome.Rejected(reason);
  }
}

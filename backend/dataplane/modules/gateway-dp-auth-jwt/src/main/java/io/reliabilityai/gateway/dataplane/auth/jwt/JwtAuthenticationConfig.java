package io.reliabilityai.gateway.dataplane.auth.jwt;

import io.reliabilityai.gateway.common.Preconditions;
import java.time.Duration;
import java.util.Optional;
import java.util.Set;

/**
 * Immutable JWT verification policy (Doc 37).
 *
 * <p>Issuer and audience are <b>required</b>, not optional. A verifier that accepts any issuer will
 * happily validate a correctly-signed token minted for a different system, which is the difference
 * between "the signature is valid" and "this token was meant for us".
 *
 * @param issuer the exact {@code iss} value accepted
 * @param audience the exact {@code aud} value that must be present
 * @param allowedAlgorithms the {@code alg} values accepted; anything else is rejected
 * @param clockSkew the tolerance applied to {@code exp}, {@code nbf} and {@code iat}
 * @param maxTokenBytes the largest token accepted before parsing is even attempted
 * @param tenantClaim the claim carrying the tenant identifier
 * @param developmentHmacSecret the HS256 shared secret — development only, absent in production
 */
public record JwtAuthenticationConfig(
    String issuer,
    String audience,
    Set<String> allowedAlgorithms,
    Duration clockSkew,
    int maxTokenBytes,
    String tenantClaim,
    Optional<byte[]> developmentHmacSecret) {

  /** The RSA algorithm identifier. */
  public static final String RS256 = "RS256";

  /** The ECDSA algorithm identifier. */
  public static final String ES256 = "ES256";

  /** The HMAC algorithm identifier — development only. */
  public static final String HS256 = "HS256";

  /** The default token size ceiling. */
  public static final int DEFAULT_MAX_TOKEN_BYTES = 8192;

  /** Validates the policy, refusing configurations that cannot be enforced safely. */
  public JwtAuthenticationConfig {
    Preconditions.requireNonBlank(issuer, "issuer");
    Preconditions.requireNonBlank(audience, "audience");
    allowedAlgorithms =
        Set.copyOf(Preconditions.requireNonNull(allowedAlgorithms, "allowedAlgorithms"));
    if (allowedAlgorithms.isEmpty()) {
      throw new IllegalArgumentException("allowedAlgorithms must not be empty");
    }
    if (allowedAlgorithms.contains("none")) {
      throw new IllegalArgumentException("alg=none is never acceptable");
    }
    for (final String algorithm : allowedAlgorithms) {
      if (!RS256.equals(algorithm) && !ES256.equals(algorithm) && !HS256.equals(algorithm)) {
        throw new IllegalArgumentException("unsupported algorithm in allowedAlgorithms");
      }
    }
    Preconditions.requireNonNull(clockSkew, "clockSkew");
    if (clockSkew.isNegative()) {
      throw new IllegalArgumentException("clockSkew must not be negative");
    }
    if (maxTokenBytes < 1) {
      throw new IllegalArgumentException("maxTokenBytes must be >= 1");
    }
    Preconditions.requireNonBlank(tenantClaim, "tenantClaim");
    Preconditions.requireNonNull(developmentHmacSecret, "developmentHmacSecret");
    if (allowedAlgorithms.contains(HS256) && developmentHmacSecret.isEmpty()) {
      throw new IllegalArgumentException("HS256 allowed but no development secret supplied");
    }
    if (developmentHmacSecret.isPresent() && !allowedAlgorithms.contains(HS256)) {
      // A secret that nothing can use is a secret nobody remembers to rotate.
      throw new IllegalArgumentException("development secret supplied but HS256 not allowed");
    }
  }

  /**
   * The production policy: asymmetric algorithms only, no shared secret.
   *
   * @param issuer the accepted issuer
   * @param audience the required audience
   * @param tenantClaim the tenant claim name
   * @return the production configuration
   */
  public static JwtAuthenticationConfig production(
      final String issuer, final String audience, final String tenantClaim) {
    return new JwtAuthenticationConfig(
        issuer,
        audience,
        Set.of(RS256, ES256),
        Duration.ofSeconds(60),
        DEFAULT_MAX_TOKEN_BYTES,
        tenantClaim,
        Optional.empty());
  }

  /**
   * Whether HS256 is permitted at all.
   *
   * @return {@code true} when the development HMAC path is enabled
   */
  public boolean hmacEnabled() {
    return allowedAlgorithms.contains(HS256) && developmentHmacSecret.isPresent();
  }
}

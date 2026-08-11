package io.reliabilityai.gateway.canonical.snapshot;

import java.util.Optional;

/**
 * The <b>allow-list</b> of accepted JWS signature algorithms (Doc 37 §9). Only asymmetric
 * algorithms are representable — {@code alg=none} and symmetric {@code HS*} algorithms <b>cannot be
 * constructed</b>, which structurally prevents the {@code alg=none} and RS↔HS confusion attacks.
 * Each constant binds to a vetted JCA (JDK) primitive; no bespoke cryptography is implemented here.
 */
public enum JwsAlgorithm {
  /** RSASSA-PKCS1-v1_5 using SHA-256. */
  RS256("SHA256withRSA", "RSA"),
  /** ECDSA using P-256 and SHA-256. */
  ES256("SHA256withECDSA", "EC");

  private final String jcaSignatureAlgorithm;
  private final String jcaKeyAlgorithm;

  JwsAlgorithm(final String jcaSignatureAlgorithm, final String jcaKeyAlgorithm) {
    this.jcaSignatureAlgorithm = jcaSignatureAlgorithm;
    this.jcaKeyAlgorithm = jcaKeyAlgorithm;
  }

  /**
   * The JCA {@code Signature} algorithm name for this JWS algorithm.
   *
   * @return the JCA signature algorithm
   */
  public String jcaSignatureAlgorithm() {
    return jcaSignatureAlgorithm;
  }

  /**
   * The JCA {@code KeyFactory} algorithm name for reconstructing the public key.
   *
   * @return the JCA key algorithm
   */
  public String jcaKeyAlgorithm() {
    return jcaKeyAlgorithm;
  }

  /**
   * Maps a JWS header {@code alg} value to an accepted algorithm. Any value outside the allow-list
   * — including {@code none}, {@code HS256}, or an unknown string — yields empty and MUST be
   * rejected (Doc 37 §9: no {@code alg=none}, no algorithm confusion).
   *
   * @param headerAlg the raw {@code alg} header value (nullable)
   * @return the accepted algorithm, or empty when not on the allow-list
   */
  public static Optional<JwsAlgorithm> fromHeader(final String headerAlg) {
    if (headerAlg == null) {
      return Optional.empty();
    }
    for (final JwsAlgorithm algorithm : values()) {
      if (algorithm.name().equals(headerAlg)) {
        return Optional.of(algorithm);
      }
    }
    return Optional.empty();
  }
}

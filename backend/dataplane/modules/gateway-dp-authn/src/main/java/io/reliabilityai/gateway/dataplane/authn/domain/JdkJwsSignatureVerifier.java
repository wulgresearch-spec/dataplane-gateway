package io.reliabilityai.gateway.dataplane.authn.domain;

import io.reliabilityai.gateway.canonical.snapshot.JwsAlgorithm;
import io.reliabilityai.gateway.common.Preconditions;
import java.math.BigInteger;
import java.security.GeneralSecurityException;
import java.security.PublicKey;
import java.security.Signature;
import java.util.Arrays;

/**
 * Verifies a JWS signature using <b>vetted JDK cryptography only</b> ({@link Signature}) — no
 * bespoke crypto (Doc 37 §9). RS256 verifies the raw PKCS#1 signature directly; ES256 first
 * converts the JOSE fixed-width {@code R||S} encoding (RFC 7518 §3.4) to the ASN.1 DER that JCA
 * expects, <b>rejecting a zero {@code r} or {@code s}</b> (the CVE-2022-21449 "psychic signature"
 * guard) and any wrong-length input. Any exception or malformed input returns {@code false} (fail
 * closed) — a verification error is never a pass, and no detail leaks. Stateless and thread-safe.
 */
public final class JdkJwsSignatureVerifier {

  private static final int P256_COMPONENT_BYTES = 32;

  /**
   * Verifies a signature over the signing input.
   *
   * @param algorithm the allow-listed algorithm (RS256/ES256)
   * @param publicKey the resolved public key
   * @param signingInput the ASCII bytes of {@code base64url(header) + "." + base64url(payload)}
   * @param signature the decoded JWS signature bytes
   * @return {@code true} iff the signature verifies; {@code false} on any failure (fail closed)
   */
  public boolean verify(
      final JwsAlgorithm algorithm,
      final PublicKey publicKey,
      final byte[] signingInput,
      final byte[] signature) {
    Preconditions.requireNonNull(algorithm, "algorithm");
    Preconditions.requireNonNull(publicKey, "publicKey");
    if (signingInput == null || signature == null) {
      return false;
    }
    try {
      final Signature verifier = Signature.getInstance(algorithm.jcaSignatureAlgorithm());
      verifier.initVerify(publicKey);
      verifier.update(signingInput);
      final byte[] jcaSignature =
          algorithm == JwsAlgorithm.ES256 ? joseToDer(signature, P256_COMPONENT_BYTES) : signature;
      return verifier.verify(jcaSignature);
    } catch (final GeneralSecurityException | RuntimeException e) {
      // Fail closed on any crypto/format error; never leak the exception.
      return false;
    }
  }

  /**
   * Converts a JOSE fixed-width {@code R||S} ECDSA signature to ASN.1 DER, rejecting zero
   * components.
   */
  private static byte[] joseToDer(final byte[] jose, final int componentBytes) {
    if (jose.length != componentBytes * 2) {
      throw new IllegalArgumentException("invalid ES256 signature length");
    }
    final BigInteger r = new BigInteger(1, Arrays.copyOfRange(jose, 0, componentBytes));
    final BigInteger s =
        new BigInteger(1, Arrays.copyOfRange(jose, componentBytes, componentBytes * 2));
    if (r.signum() == 0 || s.signum() == 0) {
      // CVE-2022-21449 guard: a zero r or s is a forged "psychic" signature.
      throw new IllegalArgumentException("zero r/s in ES256 signature");
    }
    final byte[] rBytes = toDerInteger(r);
    final byte[] sBytes = toDerInteger(s);
    final int contentLength = rBytes.length + sBytes.length;
    if (contentLength > 0xFFFF) {
      throw new IllegalArgumentException("ES256 signature too large");
    }
    final byte[] out;
    int offset;
    if (contentLength < 0x80) {
      out = new byte[2 + contentLength];
      out[0] = 0x30;
      out[1] = (byte) contentLength;
      offset = 2;
    } else {
      // Long-form length (fits in one length byte for P-256, but written generally).
      out = new byte[3 + contentLength];
      out[0] = 0x30;
      out[1] = (byte) 0x81;
      out[2] = (byte) contentLength;
      offset = 3;
    }
    System.arraycopy(rBytes, 0, out, offset, rBytes.length);
    System.arraycopy(sBytes, 0, out, offset + rBytes.length, sBytes.length);
    return out;
  }

  /** DER-encodes a non-negative integer as {@code 0x02 len <minimal two's-complement bytes>}. */
  private static byte[] toDerInteger(final BigInteger value) {
    byte[] magnitude =
        value.toByteArray(); // already minimal two's-complement, may have a leading 0x00
    final byte[] encoded = new byte[2 + magnitude.length];
    encoded[0] = 0x02;
    encoded[1] = (byte) magnitude.length;
    System.arraycopy(magnitude, 0, encoded, 2, magnitude.length);
    return encoded;
  }
}

package io.reliabilityai.gateway.dataplane.auth.jwt;

import io.reliabilityai.gateway.canonical.identity.Region;
import io.reliabilityai.gateway.canonical.identity.SnapshotVersion;
import io.reliabilityai.gateway.canonical.io.ForwardedTransportIdentity;
import io.reliabilityai.gateway.canonical.snapshot.JwsAlgorithm;
import io.reliabilityai.gateway.canonical.snapshot.VerificationKey;
import io.reliabilityai.gateway.canonical.snapshot.VerificationKeySnapshot;
import io.reliabilityai.gateway.ports.ClockPort;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PrivateKey;
import java.security.Signature;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.ECGenParameterSpec;
import java.time.Instant;
import java.util.Arrays;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/** Signs real JWTs with real keys, so the tests exercise actual cryptography rather than stubs. */
final class JwtFixture {

  static final String ISSUER = "https://issuer.example";
  static final String AUDIENCE = "gateway";
  static final String TENANT_CLAIM = "tid";
  static final Instant NOW = Instant.parse("2026-01-01T00:00:00Z");
  static final ClockPort CLOCK = () -> NOW;
  static final byte[] HMAC_SECRET =
      "development-only-secret-value".getBytes(StandardCharsets.UTF_8);

  private JwtFixture() {}

  /** A generated key pair plus the kid it is published under. */
  record Keys(KeyPair keyPair, JwsAlgorithm algorithm, String kid) {

    VerificationKey verificationKey() {
      return new VerificationKey(
          kid, algorithm, Base64.getEncoder().encodeToString(keyPair.getPublic().getEncoded()));
    }
  }

  static Keys rsa(final String kid) {
    try {
      final KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
      generator.initialize(2048);
      return new Keys(generator.generateKeyPair(), JwsAlgorithm.RS256, kid);
    } catch (final Exception e) {
      throw new IllegalStateException("cannot generate RSA key", e);
    }
  }

  static Keys ec(final String kid) {
    try {
      final KeyPairGenerator generator = KeyPairGenerator.getInstance("EC");
      generator.initialize(new ECGenParameterSpec("secp256r1"));
      return new Keys(generator.generateKeyPair(), JwsAlgorithm.ES256, kid);
    } catch (final Exception e) {
      throw new IllegalStateException("cannot generate EC key", e);
    }
  }

  static VerificationKeySnapshot snapshot(final Keys... keys) {
    return snapshot(List.of(), keys);
  }

  static VerificationKeySnapshot snapshot(final List<String> revoked, final Keys... keys) {
    final List<VerificationKey> published = new java.util.ArrayList<>();
    for (final Keys key : keys) {
      published.add(key.verificationKey());
    }
    return new VerificationKeySnapshot(
        new SnapshotVersion("verification-keys", "v1"),
        new Region("us-east-1"),
        List.copyOf(published),
        revoked);
  }

  static JwtAuthenticationConfig config() {
    return JwtAuthenticationConfig.production(ISSUER, AUDIENCE, TENANT_CLAIM);
  }

  static ForwardedTransportIdentity bearer(final String token) {
    return new ForwardedTransportIdentity("Bearer", "Bearer " + token, Map.of());
  }

  /** The standard, valid claim set; individual tests override single members. */
  static Map<String, Object> claims() {
    final Map<String, Object> claims = new LinkedHashMap<>();
    claims.put("iss", ISSUER);
    claims.put("aud", AUDIENCE);
    claims.put("sub", "principal-a");
    claims.put(TENANT_CLAIM, "tenant-a");
    claims.put("iat", NOW.getEpochSecond() - 10);
    claims.put("nbf", NOW.getEpochSecond() - 10);
    claims.put("exp", NOW.getEpochSecond() + 600);
    return claims;
  }

  static String sign(final Keys keys, final Map<String, Object> claims) {
    return sign(keys, header(keys.algorithm().name(), keys.kid()), claims);
  }

  static String sign(
      final Keys keys, final Map<String, Object> header, final Map<String, Object> claims) {
    final String signingInput = segment(header) + '.' + segment(claims);
    final byte[] signature = signBytes(keys, signingInput.getBytes(StandardCharsets.US_ASCII));
    return signingInput + '.' + Base64.getUrlEncoder().withoutPadding().encodeToString(signature);
  }

  static String signHmac(final Map<String, Object> claims) {
    return signHmac(HMAC_SECRET, claims);
  }

  /** Signs with an arbitrary secret, so a test can present a signature made by the wrong key. */
  static String signHmac(final byte[] secret, final Map<String, Object> claims) {
    final String signingInput = segment(header("HS256", "dev")) + '.' + segment(claims);
    try {
      final Mac mac = Mac.getInstance("HmacSHA256");
      mac.init(new SecretKeySpec(secret, "HmacSHA256"));
      final byte[] signature = mac.doFinal(signingInput.getBytes(StandardCharsets.US_ASCII));
      return signingInput + '.' + Base64.getUrlEncoder().withoutPadding().encodeToString(signature);
    } catch (final Exception e) {
      throw new IllegalStateException("cannot sign", e);
    }
  }

  /**
   * Renders a single-key RSA JWKS document, in the shape real identity providers publish.
   *
   * <p>Used by the verifier tests to stand up a cache that actually serves a key, so the
   * snapshot-versus-JWKS precedence rules are exercised against a real document rather than a stub.
   */
  static String jwksDocument(final Keys keys) {
    final RSAPublicKey rsa = (RSAPublicKey) keys.keyPair().getPublic();
    final Base64.Encoder url = Base64.getUrlEncoder().withoutPadding();
    return "{\"keys\":[{\"kty\":\"RSA\",\"use\":\"sig\",\"alg\":\"RS256\",\"kid\":\""
        + keys.kid()
        + "\",\"n\":\""
        + url.encodeToString(unsigned(rsa.getModulus().toByteArray()))
        + "\",\"e\":\""
        + url.encodeToString(unsigned(rsa.getPublicExponent().toByteArray()))
        + "\"}]}";
  }

  /** JWK integers are unsigned; BigInteger may prepend a zero sign byte. */
  private static byte[] unsigned(final byte[] value) {
    return value.length > 1 && value[0] == 0 ? Arrays.copyOfRange(value, 1, value.length) : value;
  }

  /** Builds a token from raw segment strings, for structural-failure tests. */
  static String raw(final String header, final String payload, final String signature) {
    return encode(header) + '.' + encode(payload) + '.' + signature;
  }

  static Map<String, Object> header(final String algorithm, final String kid) {
    final Map<String, Object> header = new LinkedHashMap<>();
    header.put("alg", algorithm);
    header.put("typ", "JWT");
    if (kid != null) {
      header.put("kid", kid);
    }
    return header;
  }

  private static byte[] signBytes(final Keys keys, final byte[] signingInput) {
    try {
      final Signature signer = Signature.getInstance(keys.algorithm().jcaSignatureAlgorithm());
      signer.initSign((PrivateKey) keys.keyPair().getPrivate());
      signer.update(signingInput);
      final byte[] raw = signer.sign();
      return keys.algorithm() == JwsAlgorithm.ES256 ? derToJose(raw) : raw;
    } catch (final Exception e) {
      throw new IllegalStateException("cannot sign", e);
    }
  }

  /** ECDSA signs in DER; JWS carries the fixed-width r||s concatenation. */
  private static byte[] derToJose(final byte[] der) {
    int offset = 3;
    if (der[1] == (byte) 0x81) {
      offset = 4;
    }
    final int rLength = der[offset];
    int rStart = offset + 1;
    final int sLengthIndex = rStart + rLength + 1;
    final int sLength = der[sLengthIndex];
    final int sStart = sLengthIndex + 1;

    final BigInteger r =
        new BigInteger(java.util.Arrays.copyOfRange(der, rStart, rStart + rLength));
    final BigInteger s =
        new BigInteger(java.util.Arrays.copyOfRange(der, sStart, sStart + sLength));

    final byte[] jose = new byte[64];
    writeFixed(r, jose, 0);
    writeFixed(s, jose, 32);
    return jose;
  }

  private static void writeFixed(final BigInteger value, final byte[] out, final int offset) {
    final byte[] bytes = value.toByteArray();
    final int start = bytes.length > 32 ? bytes.length - 32 : 0;
    final int length = Math.min(bytes.length, 32);
    System.arraycopy(bytes, start, out, offset + 32 - length, length);
  }

  private static String segment(final Map<String, Object> members) {
    return encode(json(members));
  }

  private static String encode(final String text) {
    return Base64.getUrlEncoder()
        .withoutPadding()
        .encodeToString(text.getBytes(StandardCharsets.UTF_8));
  }

  /** A tiny JSON writer — the fixture must not depend on the parser it is testing. */
  static String json(final Map<String, Object> members) {
    final StringBuilder out = new StringBuilder(128).append('{');
    boolean first = true;
    for (final Map.Entry<String, Object> member : members.entrySet()) {
      if (!first) {
        out.append(',');
      }
      first = false;
      out.append('"').append(member.getKey()).append("\":");
      final Object value = member.getValue();
      if (value instanceof String text) {
        out.append('"').append(text).append('"');
      } else if (value instanceof List<?> list) {
        out.append('[');
        for (int i = 0; i < list.size(); i++) {
          if (i > 0) {
            out.append(',');
          }
          out.append('"').append(list.get(i)).append('"');
        }
        out.append(']');
      } else {
        out.append(value);
      }
    }
    return out.append('}').toString();
  }
}

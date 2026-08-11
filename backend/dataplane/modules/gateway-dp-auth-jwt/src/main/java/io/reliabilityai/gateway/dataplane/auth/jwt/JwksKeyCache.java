package io.reliabilityai.gateway.dataplane.auth.jwt;

import io.reliabilityai.gateway.canonical.snapshot.JwsAlgorithm;
import io.reliabilityai.gateway.canonical.snapshot.VerificationKey;
import io.reliabilityai.gateway.common.Preconditions;
import io.reliabilityai.gateway.dataplane.auth.jwt.internal.JwtJson;
import io.reliabilityai.gateway.ports.ClockPort;
import java.io.ByteArrayInputStream;
import java.math.BigInteger;
import java.security.AlgorithmParameters;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.PublicKey;
import java.security.cert.CertificateFactory;
import java.security.spec.ECGenParameterSpec;
import java.security.spec.ECParameterSpec;
import java.security.spec.ECPoint;
import java.security.spec.ECPublicKeySpec;
import java.security.spec.RSAPublicKeySpec;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

/**
 * A TTL-bounded cache of JWKS verification keys (Doc 37, AD-022).
 *
 * <p><b>Lookups never fetch.</b> {@link #lookup(String)} reads the cache and nothing else — no
 * HTTP, no blocking, no I/O on the request path. Fetching happens only in {@link #refresh()}, which
 * an operator or scheduler drives out of band. A gateway that fetched a JWKS document inline would
 * hand every caller's latency, and its own availability, to the identity provider (AD-022 forbids
 * synchronous control-plane calls on the hot path).
 *
 * <p><b>Rotation is additive.</b> A refresh replaces the key set atomically, so a request either
 * sees the whole old generation or the whole new one. Publishing a new key before retiring the old
 * one lets tokens signed by either verify during the overlap, which is what makes rotation
 * non-disruptive.
 *
 * <p><b>Staleness fails closed.</b> Past the TTL the cache reports stale and stops answering
 * lookups, rather than verifying against keys that may since have been revoked.
 */
public final class JwksKeyCache {

  /** The cached generation: the keys, and when they were fetched. */
  private record Generation(Map<String, VerificationKey> keysByKid, Instant fetchedAt) {}

  private final Supplier<String> documentSource;
  private final Duration cacheTtl;
  private final ClockPort clock;
  private final AtomicReference<Generation> current = new AtomicReference<>();

  /**
   * Creates the cache.
   *
   * @param documentSource supplies the raw JWKS document when {@link #refresh()} runs; this is the
   *     only place I/O may happen, and it is never invoked from a lookup
   * @param cacheTtl how long a fetched generation stays usable
   * @param clock the injected clock
   */
  public JwksKeyCache(
      final Supplier<String> documentSource, final Duration cacheTtl, final ClockPort clock) {
    this.documentSource = Preconditions.requireNonNull(documentSource, "documentSource");
    Preconditions.requireNonNull(cacheTtl, "cacheTtl");
    if (cacheTtl.isZero() || cacheTtl.isNegative()) {
      throw new IllegalArgumentException("cacheTtl must be positive");
    }
    this.cacheTtl = cacheTtl;
    this.clock = Preconditions.requireNonNull(clock, "clock");
  }

  /**
   * Fetches and replaces the cached generation.
   *
   * <p>A failed or unparseable fetch leaves the previous generation in place: last-known-good beats
   * no keys at all, and the TTL still bounds how long a stale generation may be trusted.
   *
   * @return {@code true} if a new generation was installed
   */
  public boolean refresh() {
    final String document;
    try {
      document = documentSource.get();
    } catch (final RuntimeException fetchFailure) {
      return false;
    }
    final Map<String, VerificationKey> parsed;
    try {
      parsed = parse(document);
    } catch (final RuntimeException malformed) {
      return false;
    }
    if (parsed.isEmpty()) {
      return false; // an empty document would silently revoke every key
    }
    current.set(new Generation(parsed, clock.now()));
    return true;
  }

  /**
   * Resolves a key by {@code kid} from the cache only.
   *
   * @param kid the key id from the token header
   * @return the key, or empty when unknown, uncached or stale
   */
  public Optional<VerificationKey> lookup(final String kid) {
    if (kid == null || kid.isBlank() || isStale()) {
      return Optional.empty();
    }
    final Generation generation = current.get();
    return generation == null
        ? Optional.empty()
        : Optional.ofNullable(generation.keysByKid().get(kid));
  }

  /**
   * Whether the cached generation has aged past its TTL.
   *
   * @return {@code true} when there is no generation, or it is too old to trust
   */
  public boolean isStale() {
    final Generation generation = current.get();
    return generation == null || !clock.now().isBefore(generation.fetchedAt().plus(cacheTtl));
  }

  /**
   * The kids currently cached, for operator inspection.
   *
   * @return the cached key ids
   */
  public List<String> cachedKeyIds() {
    final Generation generation = current.get();
    return generation == null ? List.of() : List.copyOf(generation.keysByKid().keySet());
  }

  /**
   * Parses a JWKS document into verification keys.
   *
   * <p>Only {@code RSA} and {@code EC} keys with a supported {@code alg} are retained; anything
   * else is dropped rather than guessed at. A key whose material will not decode is skipped, so one
   * malformed entry cannot poison an otherwise usable generation.
   *
   * @param document the JWKS JSON
   * @return the keys by kid
   */
  static Map<String, VerificationKey> parse(final String document) {
    final Map<String, VerificationKey> keys = new LinkedHashMap<>();
    for (final Object entry : JwtJson.arrayAt(JwtJson.parseObject(document), "keys")) {
      if (!(entry instanceof Map)) {
        continue;
      }
      @SuppressWarnings("unchecked")
      final Map<String, Object> jwk = (Map<String, Object>) entry;
      final String kid = JwtJson.stringAt(jwk, "kid");
      final String use = JwtJson.stringAt(jwk, "use");
      if (kid == null || kid.isBlank() || (use != null && !"sig".equals(use))) {
        continue;
      }
      final Optional<JwsAlgorithm> algorithm =
          JwsAlgorithm.fromHeader(JwtJson.stringAt(jwk, "alg"));
      if (algorithm.isEmpty()) {
        continue;
      }
      final String subjectPublicKeyInfo = encodedPublicKey(jwk, algorithm.orElseThrow());
      if (subjectPublicKeyInfo == null) {
        continue; // one unusable entry must not poison an otherwise valid generation
      }
      keys.putIfAbsent(
          kid, new VerificationKey(kid, algorithm.orElseThrow(), subjectPublicKeyInfo));
    }
    return Map.copyOf(keys);
  }

  /**
   * Reconstructs a JWK's public key and re-encodes it as the SubjectPublicKeyInfo that {@link
   * VerificationKey} carries.
   *
   * <p>Handles the two shapes real identity providers publish: the standard JWK parameters ({@code
   * n}/{@code e} for RSA, {@code x}/{@code y} for EC) and, as a fallback, an {@code x5c}
   * certificate chain. Supporting only {@code x5c} would fail against most IdPs, which publish bare
   * parameters.
   *
   * <p>The declared algorithm is checked against the key's actual type — a JWKS that labels an RSA
   * key {@code ES256} is either broken or hostile, and must not be used either way.
   *
   * @param jwk the JWK object
   * @param algorithm the algorithm the JWKS declares
   * @return the base64 SubjectPublicKeyInfo, or {@code null} if unusable
   */
  private static String encodedPublicKey(
      final Map<String, Object> jwk, final JwsAlgorithm algorithm) {
    final String keyType = JwtJson.stringAt(jwk, "kty");
    try {
      final PublicKey publicKey;
      if ("RSA".equals(keyType) && JwtJson.stringAt(jwk, "n") != null) {
        publicKey =
            KeyFactory.getInstance("RSA")
                .generatePublic(
                    new RSAPublicKeySpec(
                        unsignedBigInteger(JwtJson.stringAt(jwk, "n")),
                        unsignedBigInteger(JwtJson.stringAt(jwk, "e"))));
      } else if ("EC".equals(keyType) && JwtJson.stringAt(jwk, "x") != null) {
        if (!"P-256".equals(JwtJson.stringAt(jwk, "crv"))) {
          return null; // ES256 is defined over P-256 only
        }
        publicKey =
            KeyFactory.getInstance("EC")
                .generatePublic(
                    new ECPublicKeySpec(
                        new ECPoint(
                            unsignedBigInteger(JwtJson.stringAt(jwk, "x")),
                            unsignedBigInteger(JwtJson.stringAt(jwk, "y"))),
                        p256Parameters()));
      } else {
        publicKey = fromCertificate(jwk);
      }
      if (publicKey == null || !algorithm.jcaKeyAlgorithm().equals(publicKey.getAlgorithm())) {
        return null;
      }
      return Base64.getEncoder().encodeToString(publicKey.getEncoded());
    } catch (final GeneralSecurityException | RuntimeException unusable) {
      return null;
    }
  }

  private static PublicKey fromCertificate(final Map<String, Object> jwk)
      throws GeneralSecurityException {
    final String certificateBase64 = firstCertificate(jwk);
    if (certificateBase64 == null) {
      return null;
    }
    return CertificateFactory.getInstance("X.509")
        .generateCertificate(
            new ByteArrayInputStream(Base64.getDecoder().decode(certificateBase64)))
        .getPublicKey();
  }

  /** JWK integers are unsigned base64url; a leading high bit must not read as a negative number. */
  private static BigInteger unsignedBigInteger(final String base64Url) {
    if (base64Url == null) {
      throw new IllegalArgumentException("missing parameter");
    }
    return new BigInteger(1, Base64.getUrlDecoder().decode(base64Url));
  }

  private static ECParameterSpec p256Parameters() throws GeneralSecurityException {
    final AlgorithmParameters parameters = AlgorithmParameters.getInstance("EC");
    parameters.init(new ECGenParameterSpec("secp256r1"));
    return parameters.getParameterSpec(ECParameterSpec.class);
  }

  private static String firstCertificate(final Map<String, Object> jwk) {
    final List<Object> chain = JwtJson.arrayAt(jwk, "x5c");
    final List<String> values = new ArrayList<>();
    for (final Object element : chain) {
      if (element instanceof String text && !text.isBlank()) {
        values.add(text);
      }
    }
    return values.isEmpty() ? null : values.get(0);
  }
}

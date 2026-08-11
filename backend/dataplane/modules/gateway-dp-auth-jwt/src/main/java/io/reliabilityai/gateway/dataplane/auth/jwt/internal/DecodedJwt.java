package io.reliabilityai.gateway.dataplane.auth.jwt.internal;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A structurally decoded JWS compact serialization: three base64url segments, parsed but <b>not yet
 * trusted</b>.
 *
 * <p>Nothing in this type has been verified. Decoding is deliberately separated from verification
 * so that the code which reads {@code alg} and {@code kid} — values an attacker fully controls —
 * cannot be confused with the code that decides whether the token is genuine.
 *
 * @param header the parsed JOSE header
 * @param payload the parsed claims set
 * @param signingInput the exact bytes the signature covers ({@code header.payload})
 * @param signature the decoded signature bytes
 */
public record DecodedJwt(
    Map<String, Object> header,
    Map<String, Object> payload,
    byte[] signingInput,
    byte[] signature) {

  /**
   * Freezes the parsed segments and the raw bytes.
   *
   * <p>The byte arrays were already copied on the way out; they were not copied on the way in, so
   * whoever handed them over kept a live reference into a token that verification has not yet
   * accepted. The maps were not copied at all.
   *
   * <p>{@code Map.copyOf} is deliberately not used. {@link JwtJson} parses a JSON {@code null} to a
   * Java {@code null} and stores it, and {@code Map.copyOf} rejects null values — so a token
   * containing {@code {"kid": null}} would have started throwing NullPointerException out of the
   * decoder instead of being rejected by verification. That input is fully attacker-controlled, so
   * the unmodifiable-wrapper form is used, which tolerates nulls exactly as before.
   */
  public DecodedJwt {
    // The unmodifiable wrapper is applied here rather than inside the helper on purpose: SpotBugs
    // judges representation exposure one frame at a time and cannot see a copy made further down.
    header = Collections.unmodifiableMap(frozenMembers(header));
    payload = Collections.unmodifiableMap(frozenMembers(payload));
    signingInput = signingInput == null ? new byte[0] : signingInput.clone();
    signature = signature == null ? new byte[0] : signature.clone();
  }

  /**
   * Copies a parsed JSON object, freezing anything nested inside it and preserving null values and
   * member order. The returned map is deliberately still modifiable so the caller can wrap it.
   *
   * @param object the parsed object (may be null)
   * @return a fresh map whose nested objects and arrays are unmodifiable
   */
  private static Map<String, Object> frozenMembers(final Map<String, Object> object) {
    final Map<String, Object> frozen = new LinkedHashMap<>();
    if (object != null) {
      for (final Map.Entry<String, Object> entry : object.entrySet()) {
        frozen.put(entry.getKey(), freezeValue(entry.getValue()));
      }
    }
    return frozen;
  }

  @SuppressWarnings("unchecked")
  private static Object freezeValue(final Object value) {
    // A claim value is a nested object or array as often as it is a scalar (`realm_access.roles`,
    // `aud` as an array). Freezing only the top level would leave those writable, which is the
    // same shallow-copy gap one level down.
    if (value instanceof Map) {
      return Collections.unmodifiableMap(frozenMembers((Map<String, Object>) value));
    }
    if (value instanceof List<?> array) {
      final List<Object> frozen = new ArrayList<>(array.size());
      for (final Object element : array) {
        frozen.add(freezeValue(element));
      }
      return Collections.unmodifiableList(frozen);
    }
    return value;
  }

  /** Thrown when the token is not a well-formed JWS compact serialization. */
  public static final class MalformedTokenException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    /**
     * Creates the exception.
     *
     * @param message a content-free description of the structural problem
     */
    public MalformedTokenException(final String message) {
      super(message);
    }
  }

  /**
   * Decodes a compact JWS.
   *
   * @param token the compact serialization
   * @return the decoded token
   * @throws MalformedTokenException if the token is not three well-formed base64url segments
   */
  public static DecodedJwt decode(final String token) {
    if (token == null || token.isBlank()) {
      throw new MalformedTokenException("empty token");
    }
    final int firstDot = token.indexOf('.');
    final int secondDot = token.indexOf('.', firstDot + 1);
    if (firstDot <= 0 || secondDot <= firstDot) {
      throw new MalformedTokenException("expected three segments");
    }
    if (token.indexOf('.', secondDot + 1) >= 0) {
      throw new MalformedTokenException("too many segments"); // JWE or a corrupted token
    }

    final String headerSegment = token.substring(0, firstDot);
    final String payloadSegment = token.substring(firstDot + 1, secondDot);
    final String signatureSegment = token.substring(secondDot + 1);
    if (signatureSegment.isEmpty()) {
      // An empty signature is the unsigned-token shape; it must never reach verification.
      throw new MalformedTokenException("missing signature");
    }

    final Map<String, Object> header;
    final Map<String, Object> payload;
    try {
      header = JwtJson.parseObject(new String(base64Url(headerSegment), StandardCharsets.UTF_8));
      payload = JwtJson.parseObject(new String(base64Url(payloadSegment), StandardCharsets.UTF_8));
    } catch (final JwtJson.JsonException malformedJson) {
      throw new MalformedTokenException("malformed segment json");
    }

    return new DecodedJwt(
        header,
        payload,
        token.substring(0, secondDot).getBytes(StandardCharsets.US_ASCII),
        base64Url(signatureSegment));
  }

  private static byte[] base64Url(final String segment) {
    try {
      return Base64.getUrlDecoder().decode(segment);
    } catch (final IllegalArgumentException badBase64) {
      throw new MalformedTokenException("invalid base64url");
    }
  }

  /** Defensive copy: the signing input must not be mutable by a caller. */
  @Override
  public byte[] signingInput() {
    return signingInput.clone();
  }

  /** Defensive copy: the signature must not be mutable by a caller. */
  @Override
  public byte[] signature() {
    return signature.clone();
  }
}

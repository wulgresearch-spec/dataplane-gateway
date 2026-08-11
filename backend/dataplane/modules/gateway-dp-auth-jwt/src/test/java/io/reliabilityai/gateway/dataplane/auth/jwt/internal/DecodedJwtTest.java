package io.reliabilityai.gateway.dataplane.auth.jwt.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * What a decoded-but-unverified token may and may not let a caller do.
 *
 * <p>Every value here is attacker-controlled: the segments came off the wire and nothing has
 * checked the signature yet. Two properties follow. The token must not be editable by whoever
 * received it, because the code that reads {@code alg}/{@code kid} and the code that decides
 * whether the token is genuine must be looking at the same bytes. And decoding must not acquire new
 * ways to throw, because every new exception on this path is reachable by sending a malformed
 * token.
 */
@DisplayName("decoded jwt")
class DecodedJwtTest {

  @Test
  @DisplayName("a json null in a segment stays a null instead of becoming an exception")
  void aJsonNullInASegmentStaysANull() {
    // The regression this guards is specific. JwtJson parses JSON null to a Java null and stores
    // it, so freezing these maps with Map.copyOf -- which rejects null values -- would have turned
    // the token {"kid":null} into a NullPointerException thrown out of the decoder rather than a
    // clean rejection by verification. The input is fully attacker-controlled, so that is a denial
    // of service handed over for free, and it is why the unmodifiable-wrapper form is used instead.
    final Map<String, Object> header = new HashMap<>();
    header.put("alg", "RS256");
    header.put("kid", null);

    assertThatCode(() -> new DecodedJwt(header, Map.of(), new byte[] {1}, new byte[] {2}))
        .doesNotThrowAnyException();

    final DecodedJwt decoded = new DecodedJwt(header, Map.of(), new byte[] {1}, new byte[] {2});
    assertThat(decoded.header()).containsKey("kid");
    assertThat(decoded.header().get("kid")).isNull();
  }

  @Test
  @DisplayName("the header and payload cannot be edited through the record")
  void theHeaderAndPayloadCannotBeEditedThroughTheRecord() {
    final Map<String, Object> header = new HashMap<>(Map.of("alg", "none"));
    final Map<String, Object> payload = new HashMap<>(Map.of("sub", "alice"));
    final DecodedJwt decoded = new DecodedJwt(header, payload, new byte[0], new byte[0]);

    // Nor by editing the maps that were handed in.
    header.put("alg", "RS256");
    payload.put("sub", "mallory");

    assertThat(decoded.header()).containsExactlyEntriesOf(Map.of("alg", "none"));
    assertThat(decoded.payload()).containsExactlyEntriesOf(Map.of("sub", "alice"));
    assertThatThrownBy(() -> decoded.header().put("alg", "RS256"))
        .isInstanceOf(UnsupportedOperationException.class);
    assertThatThrownBy(() -> decoded.payload().put("sub", "mallory"))
        .isInstanceOf(UnsupportedOperationException.class);
  }

  @Test
  @DisplayName("nested claim objects and arrays are frozen too, not just the top level")
  void nestedClaimObjectsAndArraysAreFrozenToo() {
    // Claims are nested as often as they are flat -- realm_access.roles, aud as an array. Freezing
    // only the top level would leave the interesting ones writable.
    final Map<String, Object> roles =
        new HashMap<>(Map.of("roles", new ArrayList<>(List.of("user"))));
    final Map<String, Object> payload = new HashMap<>();
    payload.put("realm_access", roles);
    payload.put("aud", new ArrayList<>(List.of("gateway")));

    final DecodedJwt decoded = new DecodedJwt(Map.of(), payload, new byte[0], new byte[0]);

    @SuppressWarnings("unchecked")
    final Map<String, Object> frozenRealm =
        (Map<String, Object>) decoded.payload().get("realm_access");
    @SuppressWarnings("unchecked")
    final List<Object> frozenRoles = (List<Object>) frozenRealm.get("roles");
    @SuppressWarnings("unchecked")
    final List<Object> frozenAud = (List<Object>) decoded.payload().get("aud");

    assertThatThrownBy(() -> frozenRealm.put("roles", List.of("admin")))
        .isInstanceOf(UnsupportedOperationException.class);
    assertThatThrownBy(() -> frozenRoles.add("admin"))
        .isInstanceOf(UnsupportedOperationException.class);
    assertThatThrownBy(() -> frozenAud.add("other-audience"))
        .isInstanceOf(UnsupportedOperationException.class);
  }

  @Test
  @DisplayName("the signed bytes cannot be swapped after decoding")
  void theSignedBytesCannotBeSwappedAfterDecoding() {
    // The accessors already cloned on the way out; the constructor did not clone on the way in, so
    // whoever supplied the arrays still held a live reference to the exact bytes the signature is
    // checked over.
    final byte[] signingInput = {1, 2, 3};
    final byte[] signature = {4, 5, 6};
    final DecodedJwt decoded = new DecodedJwt(Map.of(), Map.of(), signingInput, signature);

    signingInput[0] = 9;
    signature[0] = 9;
    decoded.signingInput()[1] = 9;
    decoded.signature()[1] = 9;

    assertThat(decoded.signingInput()).containsExactly(1, 2, 3);
    assertThat(decoded.signature()).containsExactly(4, 5, 6);
  }

  @Test
  @DisplayName("a real token round-trips through decode with the same guarantees")
  void aRealTokenRoundTripsThroughDecode() {
    // header {"alg":"HS256"} . payload {"sub":"alice"} . signature bytes
    final String token = "eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiJhbGljZSJ9.AQID";
    final DecodedJwt decoded = DecodedJwt.decode(token);

    assertThat(decoded.header()).containsEntry("alg", "HS256");
    assertThat(decoded.payload()).containsEntry("sub", "alice");
    assertThatThrownBy(() -> decoded.payload().put("sub", "mallory"))
        .isInstanceOf(UnsupportedOperationException.class);
  }
}

package io.reliabilityai.gateway.dataplane.auth.jwt.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Tests the JSON reader that every JWT header and payload passes through.
 *
 * <p>This parser decides what the gateway believes a signed token says. The signature stops an
 * outsider choosing those bytes, so the risk here is not forgery but disagreement: if this reader
 * resolves a document differently from the issuer that minted it, the gateway authorises something
 * the issuer never asserted. That is why the contracts below are about refusing ambiguity rather
 * than merely surviving it.
 *
 * <p>It is a sibling of the ingress reader and deliberately stricter in two ways - repeated members
 * are refused outright instead of silently resolving last-one-wins, and nesting is bounded at half
 * the depth. Both are asserted here.
 */
class JwtJsonTest {

  /**
   * Object nesting that reaches the limit exactly. One more level must be refused.
   *
   * <p>The outermost object is read at depth 0, so N nested objects reach depth N-1.
   */
  private static final int OBJECTS_AT_LIMIT = 17;

  /**
   * Array nesting that reaches the limit exactly.
   *
   * <p>One lower than the object figure because the arrays hang off a member of the top-level
   * object, and that object consumes the first level.
   */
  private static final int ARRAYS_AT_LIMIT = 16;

  // ---- document shape --------------------------------------------------------------------------

  @Test
  void aWellFormedClaimSetParsesToItsMembers() {
    final Map<String, Object> claims =
        JwtJson.parseObject("{\"iss\":\"https://issuer\",\"exp\":1700000000}");

    assertThat(JwtJson.stringAt(claims, "iss")).isEqualTo("https://issuer");
    assertThat(JwtJson.numberAt(claims, "exp")).isEqualTo(1_700_000_000L);
  }

  @Test
  void surroundingWhitespaceIsNotContent() {
    assertThat(JwtJson.parseObject("  \t\r\n{\"a\":1}  \t\r\n")).containsKey("a");
  }

  @Test
  void whitespaceIsAcceptedAtEveryStructuralPosition() {
    assertThat(JwtJson.parseObject("{ \"a\" : 1 , \"b\" : [ 1 , 2 ] , \"c\" : { \"d\" : 3 } }"))
        .containsKeys("a", "b", "c");
  }

  @Test
  void aDocumentThatIsNotAnObjectIsRefused() {
    assertThatThrownBy(() -> JwtJson.parseObject("[1,2]"))
        .isInstanceOf(JwtJson.JsonException.class)
        .hasMessageContaining("expected object");
    assertThatThrownBy(() -> JwtJson.parseObject("\"a-string\""))
        .isInstanceOf(JwtJson.JsonException.class);
  }

  @Test
  void contentAfterTheDocumentIsRefused() {
    // Two documents in one segment is the shape a smuggling attempt takes: the reader that
    // validates sees one, a reader that stops early sees the other.
    assertThatThrownBy(() -> JwtJson.parseObject("{\"sub\":\"a\"} {\"sub\":\"b\"}"))
        .isInstanceOf(JwtJson.JsonException.class)
        .hasMessageContaining("trailing content");
  }

  @Test
  void aNullOrEmptyDocumentIsRefused() {
    assertThatThrownBy(() -> JwtJson.parseObject(null))
        .isInstanceOf(JwtJson.JsonException.class)
        .hasMessageContaining("null document");
    assertThatThrownBy(() -> JwtJson.parseObject("")).isInstanceOf(JwtJson.JsonException.class);
  }

  @Test
  void anEmptyObjectAndEmptyArrayAreLegitimateAndLeaveTheCursorCorrect() {
    assertThat(JwtJson.parseObject("{}")).isEmpty();
    assertThat(JwtJson.parseObject("{\"a\":{},\"b\":[]}"))
        .containsEntry("a", Map.of())
        .containsEntry("b", List.of());
  }

  // ---- repeated members ------------------------------------------------------------------------

  @Test
  void aRepeatedMemberIsRefusedRatherThanResolved() {
    // The security-relevant difference from the ingress reader. A token carrying two `exp` claims
    // has no single meaning, and picking either one means trusting a claim set the issuer may not
    // have intended. Refusing is the only answer that cannot be gamed.
    assertThatThrownBy(() -> JwtJson.parseObject("{\"exp\":1,\"exp\":2}"))
        .isInstanceOf(JwtJson.JsonException.class)
        .hasMessageContaining("duplicate member");
    assertThatThrownBy(() -> JwtJson.parseObject("{\"sub\":\"a\",\"sub\":\"b\"}"))
        .isInstanceOf(JwtJson.JsonException.class)
        .hasMessageContaining("duplicate member");
  }

  @Test
  void aRepeatedMemberIsRefusedWhenTheSecondValueIsJsonNull() {
    assertThatThrownBy(() -> JwtJson.parseObject("{\"exp\":1,\"exp\":null}"))
        .isInstanceOf(JwtJson.JsonException.class)
        .hasMessageContaining("duplicate member");
  }

  @Test
  void aRepeatedMemberIsRefusedWhenTheFirstValueIsJsonNull() {
    // The case the contract used to miss. Detection asks whether the name is already present, not
    // whether it maps to something: a JSON null is stored as a null value, and a presence test that
    // reads a null mapping as "absent" lets the repeat through and silently resolves last-wins.
    //
    // Every claim the verifier reads is reachable this way, which is why all four are asserted:
    // an expiry, a subject, an audience and the tenant. A token carrying two of any of them has no
    // single meaning, and choosing either reading means authorising something the issuer may never
    // have asserted.
    assertThatThrownBy(() -> JwtJson.parseObject("{\"exp\":null,\"exp\":2}"))
        .isInstanceOf(JwtJson.JsonException.class)
        .hasMessageContaining("duplicate member");
    assertThatThrownBy(() -> JwtJson.parseObject("{\"sub\":null,\"sub\":\"b\"}"))
        .isInstanceOf(JwtJson.JsonException.class)
        .hasMessageContaining("duplicate member");
    assertThatThrownBy(() -> JwtJson.parseObject("{\"aud\":null,\"aud\":[\"x\"]}"))
        .isInstanceOf(JwtJson.JsonException.class)
        .hasMessageContaining("duplicate member");
    assertThatThrownBy(() -> JwtJson.parseObject("{\"tid\":null,\"tid\":\"tenant-b\"}"))
        .isInstanceOf(JwtJson.JsonException.class)
        .hasMessageContaining("duplicate member");
  }

  @Test
  void aRepeatedMemberIsRefusedWhenBothValuesAreJsonNull() {
    // Neither occurrence carries a value, so nothing distinguishes them - the repeat is still an
    // ambiguity about what the issuer wrote, and presence is what decides it.
    assertThatThrownBy(() -> JwtJson.parseObject("{\"exp\":null,\"exp\":null}"))
        .isInstanceOf(JwtJson.JsonException.class)
        .hasMessageContaining("duplicate member");
  }

  @Test
  void aRepeatedMemberIsRefusedForTheTenantAndOtherIdentityClaims() {
    // The tenant claim decides which customer's policy, quota and credentials a request runs
    // against, so a second occurrence is the one repeat with a blast radius beyond this request.
    assertThatThrownBy(() -> JwtJson.parseObject("{\"tid\":\"tenant-a\",\"tid\":\"tenant-b\"}"))
        .isInstanceOf(JwtJson.JsonException.class)
        .hasMessageContaining("duplicate member");
    assertThatThrownBy(() -> JwtJson.parseObject("{\"iss\":\"a\",\"iss\":\"b\"}"))
        .isInstanceOf(JwtJson.JsonException.class)
        .hasMessageContaining("duplicate member");
  }

  @Test
  void aRepeatedMemberIsRefusedAtEveryNestingLevel() {
    // Detection lives in the object reader, so it has to hold for nested objects too rather than
    // only the claim set's top level.
    assertThatThrownBy(() -> JwtJson.parseObject("{\"a\":{\"b\":null,\"b\":1}}"))
        .isInstanceOf(JwtJson.JsonException.class)
        .hasMessageContaining("duplicate member");
    assertThatThrownBy(() -> JwtJson.parseObject("{\"a\":[{\"b\":1,\"b\":2}]}"))
        .isInstanceOf(JwtJson.JsonException.class)
        .hasMessageContaining("duplicate member");
  }

  @Test
  void distinctMembersAreNotMistakenForRepeatsWhenTheirValuesAreNull() {
    // The other side of the presence test: several members may legitimately be null, and each is
    // a different name. Refusing these would reject ordinary claim sets.
    final Map<String, Object> claims =
        JwtJson.parseObject("{\"exp\":null,\"sub\":null,\"tid\":null,\"iss\":\"x\"}");

    assertThat(claims).containsKeys("exp", "sub", "tid", "iss");
    assertThat(JwtJson.numberAt(claims, "exp")).isNull();
    assertThat(JwtJson.stringAt(claims, "sub")).isNull();
    assertThat(JwtJson.stringAt(claims, "iss")).isEqualTo("x");
  }

  @Test
  void aMemberNamedLikeAnInheritedMapEntryIsStillAnOrdinaryName() {
    // Presence is asked of the parsed object only. A name that collides with nothing in particular
    // still has to behave like any other name: accepted once, refused twice.
    assertThat(JwtJson.parseObject("{\"toString\":1,\"hashCode\":2}")).hasSize(2);
    assertThatThrownBy(() -> JwtJson.parseObject("{\"toString\":1,\"toString\":2}"))
        .isInstanceOf(JwtJson.JsonException.class)
        .hasMessageContaining("duplicate member");
  }

  @Test
  void aSingleJsonNullMemberIsStoredAsAnAbsentValue() {
    // Establishes the mechanism behind the gap above: null members parse and are indistinguishable
    // from absent ones through the typed accessors, which is exactly what fails closed everywhere
    // the verifier reads a claim.
    final Map<String, Object> claims = JwtJson.parseObject("{\"exp\":null,\"sub\":null}");

    assertThat(claims).containsKeys("exp", "sub");
    assertThat(JwtJson.numberAt(claims, "exp")).isNull();
    assertThat(JwtJson.stringAt(claims, "sub")).isNull();
  }

  // ---- the nesting guard -----------------------------------------------------------------------

  @Test
  void nestingUpToTheLimitIsAcceptedAndBeyondItIsRefused() {
    // A token is attacker-supplied bytes before it is anything else, and the size ceiling alone
    // does not bound recursion: a few hundred bytes of brackets is a very deep document.
    assertThat(JwtJson.parseObject(nestedObjects(OBJECTS_AT_LIMIT))).isNotEmpty();

    assertThatThrownBy(() -> JwtJson.parseObject(nestedObjects(OBJECTS_AT_LIMIT + 1)))
        .isInstanceOf(JwtJson.JsonException.class)
        .hasMessageContaining("nesting too deep");
  }

  @Test
  void arrayNestingIsBoundedByTheSameGuard() {
    assertThat(JwtJson.parseObject(nestedArrays(ARRAYS_AT_LIMIT))).isNotEmpty();

    assertThatThrownBy(() -> JwtJson.parseObject(nestedArrays(ARRAYS_AT_LIMIT + 1)))
        .isInstanceOf(JwtJson.JsonException.class)
        .hasMessageContaining("nesting too deep");
  }

  private static String nestedObjects(final int levels) {
    return "{\"a\":".repeat(levels - 1) + "{}" + "}".repeat(levels - 1);
  }

  private static String nestedArrays(final int levels) {
    return "{\"a\":" + "[".repeat(levels - 1) + "[]" + "]".repeat(levels - 1) + "}";
  }

  // ---- structural errors -----------------------------------------------------------------------

  @Test
  void malformedObjectStructureIsRefused() {
    assertThatThrownBy(() -> JwtJson.parseObject("{a:1}"))
        .isInstanceOf(JwtJson.JsonException.class)
        .hasMessageContaining("expected member name");
    assertThatThrownBy(() -> JwtJson.parseObject("{\"a\" 1}"))
        .isInstanceOf(JwtJson.JsonException.class)
        .hasMessageContaining("expected ':'");
    assertThatThrownBy(() -> JwtJson.parseObject("{\"a\":1 \"b\":2}"))
        .isInstanceOf(JwtJson.JsonException.class)
        .hasMessageContaining("expected ',' or '}'");
    assertThatThrownBy(() -> JwtJson.parseObject("{"))
        .isInstanceOf(JwtJson.JsonException.class)
        .hasMessageContaining("unexpected end");
  }

  @Test
  void malformedArrayStructureIsRefused() {
    assertThatThrownBy(() -> JwtJson.parseObject("{\"a\":[1 2]}"))
        .isInstanceOf(JwtJson.JsonException.class)
        .hasMessageContaining("expected ',' or ']'");
    assertThatThrownBy(() -> JwtJson.parseObject("{\"a\":["))
        .isInstanceOf(JwtJson.JsonException.class)
        .hasMessageContaining("unexpected end");
  }

  @Test
  void multiMemberObjectsAndArraysKeepEveryElement() {
    assertThat(JwtJson.parseObject("{\"a\":1,\"b\":2,\"c\":3}")).hasSize(3);
    assertThat(JwtJson.arrayAt(JwtJson.parseObject("{\"aud\":[\"x\",\"y\",\"z\"]}"), "aud"))
        .containsExactly("x", "y", "z");
  }

  // ---- strings and escapes ---------------------------------------------------------------------

  @Test
  void everySupportedEscapeIsDecoded() {
    final Map<String, Object> claims =
        JwtJson.parseObject("{\"a\":\"q\\\"b\\\\s\\/n\\bf\\ff\\nr\\rt\\t\"}");

    assertThat(claims).containsEntry("a", "q\"b\\s/n\bf\ff\nr\rt\t");
  }

  @Test
  void unicodeEscapesAreDecodedAndTheCursorAdvancesPastThem() {
    // Claim values reach identity comparisons - the issuer and the tenant among them - so an escape
    // that consumed the wrong number of characters would change which principal a token names.
    assertThat(JwtJson.parseObject("{\"a\":\"\\u0041Z\"}")).containsEntry("a", "AZ");
    assertThat(JwtJson.parseObject("{\"a\":\"\\u00e9\"}")).containsEntry("a", "\u00e9");
  }

  @Test
  void aTruncatedUnicodeEscapeIsRefusedAsJsonNotAsAnIndexError() {
    // Reading past the end would raise StringIndexOutOfBoundsException, which is not the parser's
    // declared failure type; it would surface as an internal error rather than a malformed token.
    assertThatThrownBy(() -> JwtJson.parseObject("{\"a\":\"\\u00"))
        .isInstanceOf(JwtJson.JsonException.class)
        .hasMessageContaining("truncated unicode escape");
    assertThatThrownBy(() -> JwtJson.parseObject("{\"a\":\"\\u"))
        .isInstanceOf(JwtJson.JsonException.class)
        .hasMessageContaining("truncated unicode escape");
  }

  @Test
  void aNonHexUnicodeEscapeIsRefused() {
    assertThatThrownBy(() -> JwtJson.parseObject("{\"a\":\"\\uZZZZ\"}"))
        .isInstanceOf(JwtJson.JsonException.class)
        .hasMessageContaining("bad unicode escape");
  }

  @Test
  void anUnknownEscapeIsRefusedRatherThanPassedThrough() {
    assertThatThrownBy(() -> JwtJson.parseObject("{\"a\":\"\\q\"}"))
        .isInstanceOf(JwtJson.JsonException.class)
        .hasMessageContaining("bad escape");
  }

  @Test
  void anUnterminatedStringOrEscapeIsRefused() {
    assertThatThrownBy(() -> JwtJson.parseObject("{\"a\":\"no-end}"))
        .isInstanceOf(JwtJson.JsonException.class)
        .hasMessageContaining("unterminated string");
    assertThatThrownBy(() -> JwtJson.parseObject("{\"a\":\"trailing\\"))
        .isInstanceOf(JwtJson.JsonException.class)
        .hasMessageContaining("unterminated escape");
  }

  @Test
  void anEmptyStringIsAValueNotAnError() {
    assertThat(JwtJson.parseObject("{\"a\":\"\"}")).containsEntry("a", "");
  }

  // ---- numbers and literals --------------------------------------------------------------------

  @Test
  void numbersIncludingNegativesAndExponentsAreRead() {
    final Map<String, Object> claims =
        JwtJson.parseObject("{\"a\":-1.5,\"b\":2e3,\"c\":0,\"d\":1700000000}");

    assertThat(claims).containsEntry("a", -1.5d).containsEntry("b", 2000.0d);
    assertThat(JwtJson.numberAt(claims, "c")).isZero();
    assertThat(JwtJson.numberAt(claims, "d")).isEqualTo(1_700_000_000L);
  }

  @Test
  void aNumberStopsAtTheFirstCharacterThatIsNotPartOfIt() {
    assertThat(JwtJson.parseObject("{\"a\":12,\"b\":34}"))
        .containsEntry("a", 12.0d)
        .containsEntry("b", 34.0d);
  }

  @Test
  void everyDecimalDigitIsPartOfANumber() {
    assertThat(JwtJson.numberAt(JwtJson.parseObject("{\"a\":1234567890}"), "a"))
        .isEqualTo(1_234_567_890L);
  }

  @Test
  void aMalformedOrTruncatedNumberIsRefused() {
    assertThatThrownBy(() -> JwtJson.parseObject("{\"a\":1.2.3}"))
        .isInstanceOf(JwtJson.JsonException.class)
        .hasMessageContaining("bad number");
    assertThatThrownBy(() -> JwtJson.parseObject("{\"a\":-}"))
        .isInstanceOf(JwtJson.JsonException.class);
    assertThatThrownBy(() -> JwtJson.parseObject("{\"a\":1"))
        .isInstanceOf(JwtJson.JsonException.class);
  }

  @Test
  void aValueThatIsNeitherStructureNorNumberIsRefused() {
    assertThatThrownBy(() -> JwtJson.parseObject("{\"a\":}"))
        .isInstanceOf(JwtJson.JsonException.class)
        .hasMessageContaining("expected value");
  }

  @Test
  void theThreeJsonLiteralsAreRecognisedAndMisspellingsAreNot() {
    final Map<String, Object> claims = JwtJson.parseObject("{\"t\":true,\"f\":false,\"n\":null}");

    assertThat(claims).containsEntry("t", Boolean.TRUE).containsEntry("f", Boolean.FALSE);
    assertThat(claims).containsKey("n");
    assertThat(claims.get("n")).isNull();

    assertThatThrownBy(() -> JwtJson.parseObject("{\"a\":tru}"))
        .isInstanceOf(JwtJson.JsonException.class)
        .hasMessageContaining("bad literal");
    assertThatThrownBy(() -> JwtJson.parseObject("{\"a\":nul}"))
        .isInstanceOf(JwtJson.JsonException.class)
        .hasMessageContaining("bad literal");
  }

  // ---- typed accessors -------------------------------------------------------------------------

  @Test
  void accessorsReturnNothingRatherThanCoercingTheWrongType() {
    // Every claim the verifier reads goes through these. A number arriving where a string was asked
    // for must read as absent, because absent is what the verifier refuses on.
    final Map<String, Object> claims =
        JwtJson.parseObject("{\"s\":\"x\",\"n\":1,\"o\":{},\"l\":[\"a\"]}");

    assertThat(JwtJson.stringAt(claims, "s")).isEqualTo("x");
    assertThat(JwtJson.stringAt(claims, "n")).isNull();
    assertThat(JwtJson.stringAt(claims, "o")).isNull();
    assertThat(JwtJson.stringAt(claims, "absent")).isNull();
    assertThat(JwtJson.stringAt(null, "s")).isNull();

    assertThat(JwtJson.numberAt(claims, "s")).isNull();
    assertThat(JwtJson.numberAt(claims, "absent")).isNull();
    assertThat(JwtJson.numberAt(null, "n")).isNull();

    assertThat(JwtJson.arrayAt(claims, "n")).isEmpty();
    assertThat(JwtJson.arrayAt(claims, "absent")).isEmpty();
    assertThat(JwtJson.arrayAt(claims, "l")).containsExactly("a");
  }

  @Test
  void anAudienceMayBeASingleStringOrAnArrayAndNothingElse() {
    // RFC 7519 allows both shapes. A shape the reader did not understand must yield no audience at
    // all, so the containment check fails closed rather than matching by accident.
    assertThat(JwtJson.audienceAt(JwtJson.parseObject("{\"aud\":\"one\"}"), "aud"))
        .containsExactly("one");
    assertThat(JwtJson.audienceAt(JwtJson.parseObject("{\"aud\":[\"a\",\"b\"]}"), "aud"))
        .containsExactly("a", "b");
    assertThat(JwtJson.audienceAt(JwtJson.parseObject("{\"aud\":1}"), "aud")).isEmpty();
    assertThat(JwtJson.audienceAt(JwtJson.parseObject("{\"x\":1}"), "aud")).isEmpty();
    assertThat(JwtJson.audienceAt(null, "aud")).isEmpty();
    // Non-string entries are dropped rather than coerced, so a nested object cannot become an
    // audience value that happens to match.
    assertThat(JwtJson.audienceAt(JwtJson.parseObject("{\"aud\":[\"a\",1,{}]}"), "aud"))
        .containsExactly("a");
  }
}

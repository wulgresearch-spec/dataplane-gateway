package io.reliabilityai.gateway.dataplane.ingress.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Tests the hand-written JSON reader that stands at the untrusted boundary.
 *
 * <p>Everything this parser accepts becomes a request the rest of the gateway reasons about, and
 * everything it rejects has to be rejected as a {@link IngressJson.JsonException} — the HTTP layer
 * catches only that type, so any other exception escaping the parser becomes a 500 rather than a
 * 400. The document-shaped tests below therefore assert the exception type as well as the outcome.
 *
 * <p>The parser was previously exercised only through end-to-end HTTP requests, which reached the
 * happy path and little else: escape handling, the nesting guard and the number scanner had no
 * coverage at all.
 */
class IngressJsonTest {

  /**
   * Object nesting that reaches the limit exactly. One more level must be refused.
   *
   * <p>The outermost object is read at depth 0, so N nested objects reach depth N-1.
   */
  private static final int OBJECTS_AT_LIMIT = 33;

  /**
   * Array nesting that reaches the limit exactly.
   *
   * <p>One lower than the object figure because the arrays must hang off a member of the top-level
   * object, and that object consumes the first level.
   */
  private static final int ARRAYS_AT_LIMIT = 32;

  // ---- document shape --------------------------------------------------------------------------

  @Test
  void aWellFormedObjectParsesToItsMembers() {
    final Map<String, Object> root = IngressJson.parseObject("{\"model\":\"m\",\"n\":2}");

    assertThat(root).containsEntry("model", "m").containsEntry("n", 2.0d);
  }

  @Test
  void surroundingWhitespaceIsNotContent() {
    // Both skipWhitespace calls in parseObject matter: one to reach the opening brace, one to
    // consume what follows the value before the trailing-content check runs.
    assertThat(IngressJson.parseObject("  \t\r\n{\"a\":1}  \t\r\n")).containsKey("a");
  }

  @Test
  void aDocumentThatIsNotAnObjectIsRefused() {
    // The pipeline indexes the result by member name, so a top-level array or scalar is not a
    // request even though it is valid JSON.
    assertThatThrownBy(() -> IngressJson.parseObject("[1,2]"))
        .isInstanceOf(IngressJson.JsonException.class)
        .hasMessageContaining("expected object");
    assertThatThrownBy(() -> IngressJson.parseObject("\"just-a-string\""))
        .isInstanceOf(IngressJson.JsonException.class)
        .hasMessageContaining("expected object");
  }

  @Test
  void contentAfterTheDocumentIsRefused() {
    // Accepting this would let a client hide a second document behind the one that was validated.
    assertThatThrownBy(() -> IngressJson.parseObject("{\"a\":1} {\"a\":2}"))
        .isInstanceOf(IngressJson.JsonException.class)
        .hasMessageContaining("trailing content");
  }

  @Test
  void aNullOrEmptyDocumentIsRefused() {
    assertThatThrownBy(() -> IngressJson.parseObject(null))
        .isInstanceOf(IngressJson.JsonException.class)
        .hasMessageContaining("null document");
    assertThatThrownBy(() -> IngressJson.parseObject(""))
        .isInstanceOf(IngressJson.JsonException.class);
  }

  @Test
  void anEmptyObjectAndEmptyArrayAreLegitimateAndLeaveTheCursorCorrect() {
    // The empty-collection branches advance past their closing bracket. If they did not, the
    // trailing-content check below would fire on a document that is in fact complete.
    assertThat(IngressJson.parseObject("{}")).isEmpty();
    assertThat(IngressJson.parseObject("{\"a\":{},\"b\":[]}"))
        .containsEntry("a", Map.of())
        .containsEntry("b", List.of());
  }

  // ---- the nesting guard -----------------------------------------------------------------------

  @Test
  void nestingUpToTheLimitIsAcceptedAndBeyondItIsRefused() {
    // The recursion guard is the parser's only defence against a small body costing an unbounded
    // stack. Asserting the accepted depth as well as the refused one keeps the limit where it is:
    // a guard that trips one level early would refuse legitimate documents, and one that never
    // trips at all would let a hostile client crash the thread.
    assertThat(IngressJson.parseObject(nestedObjects(OBJECTS_AT_LIMIT))).isNotEmpty();

    assertThatThrownBy(() -> IngressJson.parseObject(nestedObjects(OBJECTS_AT_LIMIT + 1)))
        .isInstanceOf(IngressJson.JsonException.class)
        .hasMessageContaining("nesting too deep");
  }

  @Test
  void arrayNestingIsBoundedByTheSameGuard() {
    assertThat(IngressJson.parseObject(nestedArrays(ARRAYS_AT_LIMIT))).isNotEmpty();

    assertThatThrownBy(() -> IngressJson.parseObject(nestedArrays(ARRAYS_AT_LIMIT + 1)))
        .isInstanceOf(IngressJson.JsonException.class)
        .hasMessageContaining("nesting too deep");
  }

  /** {@code {"a":{"a":...{}}}} nested {@code levels} deep. */
  private static String nestedObjects(final int levels) {
    return "{\"a\":".repeat(levels - 1) + "{}" + "}".repeat(levels - 1);
  }

  /** An object whose single member is {@code levels} nested arrays. */
  private static String nestedArrays(final int levels) {
    return "{\"a\":" + "[".repeat(levels - 1) + "[]" + "]".repeat(levels - 1) + "}";
  }

  // ---- structural errors -----------------------------------------------------------------------

  @Test
  void malformedObjectStructureIsRefused() {
    assertThatThrownBy(() -> IngressJson.parseObject("{a:1}"))
        .isInstanceOf(IngressJson.JsonException.class)
        .hasMessageContaining("expected member name");
    assertThatThrownBy(() -> IngressJson.parseObject("{\"a\" 1}"))
        .isInstanceOf(IngressJson.JsonException.class)
        .hasMessageContaining("expected ':'");
    assertThatThrownBy(() -> IngressJson.parseObject("{\"a\":1 \"b\":2}"))
        .isInstanceOf(IngressJson.JsonException.class)
        .hasMessageContaining("expected ',' or '}'");
    assertThatThrownBy(() -> IngressJson.parseObject("{"))
        .isInstanceOf(IngressJson.JsonException.class)
        .hasMessageContaining("unexpected end");
  }

  @Test
  void malformedArrayStructureIsRefused() {
    assertThatThrownBy(() -> IngressJson.parseObject("{\"a\":[1 2]}"))
        .isInstanceOf(IngressJson.JsonException.class)
        .hasMessageContaining("expected ',' or ']'");
    assertThatThrownBy(() -> IngressJson.parseObject("{\"a\":["))
        .isInstanceOf(IngressJson.JsonException.class)
        .hasMessageContaining("unexpected end");
  }

  @Test
  void multiMemberObjectsAndArraysKeepEveryElement() {
    // Separator handling advances the cursor by hand; dropping an element or stopping early would
    // silently deliver a different request than the client sent.
    assertThat(IngressJson.parseObject("{\"a\":1,\"b\":2,\"c\":3}")).hasSize(3);
    assertThat(IngressJson.arrayAt(IngressJson.parseObject("{\"a\":[1,2,3]}"), "a"))
        .containsExactly(1.0d, 2.0d, 3.0d);
  }

  @Test
  void whitespaceIsAcceptedAtEveryStructuralPosition() {
    // The reader consumes whitespace by hand at each point where JSON permits it: around member
    // names, either side of the colon, and around every separator and closing bracket. A missed
    // one would reject a pretty-printed document that is perfectly valid, so this asserts one
    // document carrying whitespace in all of those positions at once.
    final String spaced = "{ \"a\" : [ 1 , 2 ] , \"b\" : { \"c\" : 3 } , \"d\" : \"x\" }";

    final Map<String, Object> root = IngressJson.parseObject(spaced);

    assertThat(root).hasSize(3).containsEntry("d", "x");
    assertThat(IngressJson.arrayAt(root, "a")).containsExactly(1.0d, 2.0d);
    assertThat(root.get("b")).isEqualTo(Map.of("c", 3.0d));
  }

  @Test
  void aDocumentTruncatedInsideAValueIsRefusedAsJsonNotAsAnIndexError() {
    // Scanning a number walks the buffer directly. Running one position past the end would raise
    // StringIndexOutOfBoundsException, which the HTTP layer does not catch, turning a truncated
    // body into a 500 instead of a 400.
    assertThatThrownBy(() -> IngressJson.parseObject("{\"a\":1"))
        .isInstanceOf(IngressJson.JsonException.class);
    assertThatThrownBy(() -> IngressJson.parseObject("{\"a\":"))
        .isInstanceOf(IngressJson.JsonException.class);
    assertThatThrownBy(() -> IngressJson.parseObject("{\"a\""))
        .isInstanceOf(IngressJson.JsonException.class);
  }

  @Test
  void everyDecimalDigitIsPartOfANumber() {
    // The scanner classifies characters by range. A range that excluded either endpoint would cut
    // a literal short and change the value the provider is asked for, silently.
    assertThat(IngressJson.parseObject("{\"a\":1234567890}")).containsEntry("a", 1234567890.0d);
    assertThat(IngressJson.parseObject("{\"a\":9,\"b\":0}"))
        .containsEntry("a", 9.0d)
        .containsEntry("b", 0.0d);
  }

  // ---- strings and escapes ---------------------------------------------------------------------

  @Test
  void everySupportedEscapeIsDecoded() {
    final Map<String, Object> root =
        IngressJson.parseObject("{\"a\":\"q\\\"b\\\\s\\/n\\bf\\ff\\nr\\rt\\t\"}");

    assertThat(root).containsEntry("a", "q\"b\\s/n\bf\ff\nr\rt\t");
  }

  @Test
  void unicodeEscapesAreDecodedAndTheCursorAdvancesPastThem() {
    // The \\u branch consumes four hex digits by index arithmetic. If it consumed the wrong number
    // the following characters would be re-read as content, so the trailing "Z" pins the cursor.
    assertThat(IngressJson.parseObject("{\"a\":\"\\u0041Z\"}")).containsEntry("a", "AZ");
    assertThat(IngressJson.parseObject("{\"a\":\"\\u00e9\"}")).containsEntry("a", "\u00e9");
  }

  @Test
  void aTruncatedUnicodeEscapeIsRefusedAsJsonNotAsAnIndexError() {
    // Reading past the end here would raise StringIndexOutOfBoundsException, which the HTTP layer
    // does not catch — a malformed body would become a 500 instead of a 400.
    // The document ends inside the escape, so there are not four digits left to read.
    assertThatThrownBy(() -> IngressJson.parseObject("{\"a\":\"\\u00"))
        .isInstanceOf(IngressJson.JsonException.class)
        .hasMessageContaining("truncated unicode escape");
    assertThatThrownBy(() -> IngressJson.parseObject("{\"a\":\"\\u"))
        .isInstanceOf(IngressJson.JsonException.class)
        .hasMessageContaining("truncated unicode escape");

    // Four characters remain but they are not all hex, so the escape is refused on its content
    // rather than its length. Both branches must refuse; only the message differs.
    assertThatThrownBy(() -> IngressJson.parseObject("{\"a\":\"\\u00\"}"))
        .isInstanceOf(IngressJson.JsonException.class)
        .hasMessageContaining("bad unicode escape");
  }

  @Test
  void aNonHexUnicodeEscapeIsRefused() {
    assertThatThrownBy(() -> IngressJson.parseObject("{\"a\":\"\\uZZZZ\"}"))
        .isInstanceOf(IngressJson.JsonException.class)
        .hasMessageContaining("bad unicode escape");
  }

  @Test
  void anUnknownEscapeIsRefusedRatherThanPassedThrough() {
    // Silently emitting the escaped character would make the parser accept documents no other JSON
    // reader accepts, so what the gateway validates would differ from what a client meant.
    assertThatThrownBy(() -> IngressJson.parseObject("{\"a\":\"\\q\"}"))
        .isInstanceOf(IngressJson.JsonException.class)
        .hasMessageContaining("bad escape");
  }

  @Test
  void anUnterminatedStringOrEscapeIsRefused() {
    assertThatThrownBy(() -> IngressJson.parseObject("{\"a\":\"no-end}"))
        .isInstanceOf(IngressJson.JsonException.class)
        .hasMessageContaining("unterminated string");
    assertThatThrownBy(() -> IngressJson.parseObject("{\"a\":\"trailing\\"))
        .isInstanceOf(IngressJson.JsonException.class)
        .hasMessageContaining("unterminated escape");
  }

  @Test
  void anEmptyStringIsAValueNotAnError() {
    assertThat(IngressJson.parseObject("{\"a\":\"\"}")).containsEntry("a", "");
  }

  // ---- numbers and literals --------------------------------------------------------------------

  @Test
  void numbersIncludingNegativesAndExponentsAreRead() {
    final Map<String, Object> root =
        IngressJson.parseObject("{\"a\":-1.5,\"b\":2e3,\"c\":0,\"d\":-0.25E-2}");

    assertThat(root)
        .containsEntry("a", -1.5d)
        .containsEntry("b", 2000.0d)
        .containsEntry("c", 0.0d)
        .containsEntry("d", -0.0025d);
  }

  @Test
  void aNumberStopsAtTheFirstCharacterThatIsNotPartOfIt() {
    // The scanner decides where the literal ends. Over-consuming would swallow the separator and
    // under-consuming would leave it to be misread as structure.
    assertThat(IngressJson.parseObject("{\"a\":12,\"b\":34}"))
        .containsEntry("a", 12.0d)
        .containsEntry("b", 34.0d);
    assertThat(IngressJson.arrayAt(IngressJson.parseObject("{\"a\":[1,2]}"), "a")).hasSize(2);
  }

  @Test
  void aMalformedNumberIsRefused() {
    assertThatThrownBy(() -> IngressJson.parseObject("{\"a\":1.2.3}"))
        .isInstanceOf(IngressJson.JsonException.class)
        .hasMessageContaining("bad number");
    assertThatThrownBy(() -> IngressJson.parseObject("{\"a\":-}"))
        .isInstanceOf(IngressJson.JsonException.class);
  }

  @Test
  void aValueThatIsNeitherStructureNorNumberIsRefused() {
    assertThatThrownBy(() -> IngressJson.parseObject("{\"a\":}"))
        .isInstanceOf(IngressJson.JsonException.class)
        .hasMessageContaining("expected value");
  }

  @Test
  void theThreeJsonLiteralsAreRecognisedAndMisspellingsAreNot() {
    final Map<String, Object> root = IngressJson.parseObject("{\"t\":true,\"f\":false,\"n\":null}");

    assertThat(root).containsEntry("t", Boolean.TRUE).containsEntry("f", Boolean.FALSE);
    assertThat(root).containsKey("n");
    assertThat(root.get("n")).isNull();

    assertThatThrownBy(() -> IngressJson.parseObject("{\"a\":tru}"))
        .isInstanceOf(IngressJson.JsonException.class)
        .hasMessageContaining("bad literal");
    assertThatThrownBy(() -> IngressJson.parseObject("{\"a\":nul}"))
        .isInstanceOf(IngressJson.JsonException.class)
        .hasMessageContaining("bad literal");
  }

  // ---- typed accessors -------------------------------------------------------------------------

  @Test
  void accessorsReturnNothingRatherThanCoercingTheWrongType() {
    // A client controls the type of every member. The accessors are what stop a number or an
    // object arriving where the caller asked for a string.
    final Map<String, Object> root = IngressJson.parseObject("{\"s\":\"x\",\"n\":1,\"o\":{}}");

    assertThat(IngressJson.stringAt(root, "s")).isEqualTo("x");
    assertThat(IngressJson.stringAt(root, "n")).isNull();
    assertThat(IngressJson.stringAt(root, "o")).isNull();
    assertThat(IngressJson.stringAt(root, "absent")).isNull();
    assertThat(IngressJson.stringAt(null, "s")).isNull();

    assertThat(IngressJson.arrayAt(root, "n")).isEmpty();
    assertThat(IngressJson.arrayAt(root, "absent")).isEmpty();
  }

  // ---- output escaping -------------------------------------------------------------------------

  @Test
  void quotingEscapesEverythingThatCouldBreakOutOfAStringLiteral() {
    // The correlation id echoed into the response is client-supplied, so this is the boundary that
    // stops a chosen id from injecting structure into a document the client then reads back.
    assertThat(IngressJson.quote("plain")).isEqualTo("\"plain\"");
    assertThat(IngressJson.quote("a\"b")).isEqualTo("\"a\\\"b\"");
    assertThat(IngressJson.quote("a\\b")).isEqualTo("\"a\\\\b\"");
    assertThat(IngressJson.quote("\b\f\n\r\t")).isEqualTo("\"\\b\\f\\n\\r\\t\"");
    // Control characters with no short form become \\u escapes, lower-case and zero-padded.
    assertThat(IngressJson.quote("\u0001")).isEqualTo("\"\\u0001\"");
    assertThat(IngressJson.quote("\u001f")).isEqualTo("\"\\u001f\"");
    // 0x20 is the first character that needs no escaping.
    assertThat(IngressJson.quote(" ")).isEqualTo("\" \"");
    assertThat(IngressJson.quote("")).isEqualTo("\"\"");
  }

  @Test
  void aQuotedStringSurvivesAParseRoundTrip() {
    final String hostile = "id\",\"injected\":\"yes\u0000\n";

    final Map<String, Object> root =
        IngressJson.parseObject("{\"id\":" + IngressJson.quote(hostile) + "}");

    assertThat(root).hasSize(1).containsEntry("id", hostile);
  }
}

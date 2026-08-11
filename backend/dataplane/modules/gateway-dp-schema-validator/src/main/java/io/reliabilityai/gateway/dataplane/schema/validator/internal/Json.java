package io.reliabilityai.gateway.dataplane.schema.validator.internal;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A strict JSON reader producing an immutable value tree.
 *
 * <p>Numbers are kept as {@link BigDecimal}, not {@code double}. JSON Schema compares numbers
 * mathematically and distinguishes integers from reals, and binary floating point silently destroys
 * both: {@code 1.0} and {@code 1} must compare equal, {@code 10000000000000000001} must not round
 * to {@code 10000000000000000000}, and a {@code multipleOf}-style bound must not drift. That is why
 * this module carries its own reader rather than reusing the transport-level parsers elsewhere in
 * the tree.
 *
 * <p>Bounded on every axis an attacker controls — input length, nesting depth, and total node count
 * — so a hostile document fails fast instead of exhausting the heap or the stack.
 */
public final class Json {

  /** Thrown when input is not well-formed JSON or exceeds a configured bound. */
  public static final class JsonException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    /**
     * Creates the exception.
     *
     * @param message a content-free description of the structural problem
     */
    public JsonException(final String message) {
      super(message);
    }
  }

  /** An immutable JSON value. */
  public sealed interface JsonValue
      permits Json.JsonObject,
          Json.JsonArray,
          Json.JsonString,
          Json.JsonNumber,
          Json.JsonBoolean,
          Json.JsonNull {

    /**
     * The JSON type name used in violation reporting.
     *
     * @return the type name, e.g. {@code object} or {@code string}
     */
    String kind();
  }

  /**
   * A JSON object.
   *
   * @param members the members, in document order
   */
  public record JsonObject(Map<String, JsonValue> members) implements JsonValue {

    /** Defensively copies the members. */
    public JsonObject {
      members = Map.copyOf(members);
    }

    @Override
    public String kind() {
      return "object";
    }
  }

  /**
   * A JSON array.
   *
   * @param elements the elements, in order
   */
  public record JsonArray(List<JsonValue> elements) implements JsonValue {

    /** Defensively copies the elements. */
    public JsonArray {
      elements = List.copyOf(elements);
    }

    @Override
    public String kind() {
      return "array";
    }
  }

  /**
   * A JSON string.
   *
   * @param value the string value
   */
  public record JsonString(String value) implements JsonValue {
    @Override
    public String kind() {
      return "string";
    }
  }

  /**
   * A JSON number.
   *
   * @param value the exact numeric value
   */
  public record JsonNumber(BigDecimal value) implements JsonValue {

    @Override
    public String kind() {
      return isInteger() ? "integer" : "number";
    }

    /**
     * Whether this number has zero fractional part, which is what JSON Schema means by {@code type:
     * integer} — {@code 1.0} is an integer, {@code 1.5} is not.
     *
     * @return {@code true} when the value is mathematically an integer
     */
    public boolean isInteger() {
      return value.stripTrailingZeros().scale() <= 0;
    }

    /** Numeric equality is mathematical, so {@code 1.0} equals {@code 1}. */
    @Override
    public boolean equals(final Object other) {
      return other instanceof JsonNumber number && value.compareTo(number.value) == 0;
    }

    @Override
    public int hashCode() {
      return value.stripTrailingZeros().hashCode();
    }
  }

  /**
   * A JSON boolean.
   *
   * @param value the boolean value
   */
  public record JsonBoolean(boolean value) implements JsonValue {
    @Override
    public String kind() {
      return "boolean";
    }
  }

  /** JSON null. */
  public record JsonNull() implements JsonValue {
    @Override
    public String kind() {
      return "null";
    }
  }

  /** The single null instance. */
  public static final JsonNull NULL = new JsonNull();

  private final String source;
  private final int maxDepth;
  private final int maxNodes;
  private int index;
  private int nodes;

  private Json(final String source, final int maxDepth, final int maxNodes) {
    this.source = source;
    this.maxDepth = maxDepth;
    this.maxNodes = maxNodes;
  }

  /**
   * Parses a JSON document.
   *
   * @param text the document
   * @param maxDepth the deepest nesting accepted
   * @param maxNodes the largest node count accepted
   * @return the parsed value
   * @throws JsonException if the document is malformed or exceeds a bound
   */
  public static JsonValue parse(final String text, final int maxDepth, final int maxNodes) {
    if (text == null) {
      throw new JsonException("null document");
    }
    final Json parser = new Json(text, maxDepth, maxNodes);
    parser.skipWhitespace();
    final JsonValue value = parser.readValue(0);
    parser.skipWhitespace();
    if (parser.index != text.length()) {
      throw new JsonException("trailing content");
    }
    return value;
  }

  private JsonValue readValue(final int depth) {
    if (depth > maxDepth) {
      throw new JsonException("nesting too deep");
    }
    if (++nodes > maxNodes) {
      throw new JsonException("too many nodes");
    }
    if (index >= source.length()) {
      throw new JsonException("unexpected end");
    }
    final char c = source.charAt(index);
    switch (c) {
      case '{':
        return readObject(depth);
      case '[':
        return readArray(depth);
      case '"':
        return new JsonString(readString());
      case 't':
        expect("true");
        return new JsonBoolean(true);
      case 'f':
        expect("false");
        return new JsonBoolean(false);
      case 'n':
        expect("null");
        return NULL;
      default:
        return readNumber();
    }
  }

  private JsonObject readObject(final int depth) {
    final Map<String, JsonValue> members = new LinkedHashMap<>();
    index++;
    skipWhitespace();
    if (peek() == '}') {
      index++;
      return new JsonObject(members);
    }
    while (true) {
      skipWhitespace();
      if (peek() != '"') {
        throw new JsonException("expected member name");
      }
      final String name = readString();
      skipWhitespace();
      if (peek() != ':') {
        throw new JsonException("expected ':'");
      }
      index++;
      skipWhitespace();
      // Last-one-wins would let a document mean two things; refuse the ambiguity outright.
      if (members.putIfAbsent(name, readValue(depth + 1)) != null) {
        throw new JsonException("duplicate member");
      }
      skipWhitespace();
      final char next = peek();
      index++;
      if (next == '}') {
        return new JsonObject(members);
      }
      if (next != ',') {
        throw new JsonException("expected ',' or '}'");
      }
    }
  }

  private JsonArray readArray(final int depth) {
    final List<JsonValue> elements = new ArrayList<>();
    index++;
    skipWhitespace();
    if (peek() == ']') {
      index++;
      return new JsonArray(elements);
    }
    while (true) {
      skipWhitespace();
      elements.add(readValue(depth + 1));
      skipWhitespace();
      final char next = peek();
      index++;
      if (next == ']') {
        return new JsonArray(elements);
      }
      if (next != ',') {
        throw new JsonException("expected ',' or ']'");
      }
    }
  }

  private String readString() {
    index++;
    final StringBuilder out = new StringBuilder();
    while (true) {
      if (index >= source.length()) {
        throw new JsonException("unterminated string");
      }
      final char c = source.charAt(index++);
      if (c == '"') {
        return out.toString();
      }
      if (c != '\\') {
        out.append(c);
        continue;
      }
      if (index >= source.length()) {
        throw new JsonException("unterminated escape");
      }
      final char escape = source.charAt(index++);
      switch (escape) {
        case '"' -> out.append('"');
        case '\\' -> out.append('\\');
        case '/' -> out.append('/');
        case 'b' -> out.append('\b');
        case 'f' -> out.append('\f');
        case 'n' -> out.append('\n');
        case 'r' -> out.append('\r');
        case 't' -> out.append('\t');
        case 'u' -> {
          if (index + 4 > source.length()) {
            throw new JsonException("truncated unicode escape");
          }
          final String hex = source.substring(index, index + 4);
          index += 4;
          try {
            out.append((char) Integer.parseInt(hex, 16));
          } catch (final NumberFormatException e) {
            throw new JsonException("bad unicode escape");
          }
        }
        default -> throw new JsonException("bad escape");
      }
    }
  }

  private JsonNumber readNumber() {
    final int start = index;
    if (peek() == '-') {
      index++;
    }
    while (index < source.length() && isNumberChar(source.charAt(index))) {
      index++;
    }
    final String literal = source.substring(start, index);
    if (literal.isEmpty()) {
      throw new JsonException("expected value");
    }
    try {
      return new JsonNumber(new BigDecimal(literal));
    } catch (final NumberFormatException e) {
      throw new JsonException("bad number");
    }
  }

  private static boolean isNumberChar(final char c) {
    return (c >= '0' && c <= '9') || c == '.' || c == 'e' || c == 'E' || c == '+' || c == '-';
  }

  private char peek() {
    if (index >= source.length()) {
      throw new JsonException("unexpected end");
    }
    return source.charAt(index);
  }

  private void expect(final String literal) {
    if (!source.startsWith(literal, index)) {
      throw new JsonException("bad literal");
    }
    index += literal.length();
  }

  private void skipWhitespace() {
    while (index < source.length()) {
      final char c = source.charAt(index);
      if (c != ' ' && c != '\t' && c != '\n' && c != '\r') {
        return;
      }
      index++;
    }
  }
}

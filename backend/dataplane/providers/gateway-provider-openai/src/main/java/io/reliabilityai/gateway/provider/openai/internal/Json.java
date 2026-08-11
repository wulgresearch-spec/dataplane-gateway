package io.reliabilityai.gateway.provider.openai.internal;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A minimal, strict JSON reader and writer for the OpenAI wire format.
 *
 * <p>Hand-rolled on purpose. Every other module in this gateway is pure JDK, and an adapter is
 * exactly the wrong place to introduce a reflective object-mapper: the provider's payload is
 * untrusted input, and a mapper that binds it to classes turns a wire format into an attack
 * surface. This reader produces only {@code Map}, {@code List}, {@code String}, {@code Double},
 * {@code Boolean} and {@code null} — never an instance of any application type — so a malformed or
 * hostile response can cause a parse failure and nothing more.
 *
 * <p>Strict by design: trailing content, unterminated strings, bad escapes and malformed numbers
 * all throw {@link JsonException} rather than being silently repaired. A provider that sends us
 * nonsense must produce a mapping failure, not a plausible-looking wrong answer.
 */
public final class Json {

  /** Thrown when input is not well-formed JSON. Carries no payload content. */
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

  private final String source;
  private int index;

  private Json(final String source) {
    this.source = source;
  }

  /**
   * Parses a JSON document.
   *
   * @param text the document
   * @return the parsed value ({@code Map}, {@code List}, {@code String}, {@code Double}, {@code
   *     Boolean} or {@code null})
   * @throws JsonException if the document is not well-formed
   */
  public static Object parse(final String text) {
    if (text == null) {
      throw new JsonException("null document");
    }
    final Json parser = new Json(text);
    parser.skipWhitespace();
    final Object value = parser.readValue(0);
    parser.skipWhitespace();
    if (parser.index != text.length()) {
      throw new JsonException("trailing content");
    }
    return value;
  }

  /**
   * Parses a JSON document expected to be an object.
   *
   * @param text the document
   * @return the parsed object
   * @throws JsonException if the document is not a well-formed object
   */
  public static Map<String, Object> parseObject(final String text) {
    final Object value = parse(text);
    if (!(value instanceof Map)) {
      throw new JsonException("expected object");
    }
    @SuppressWarnings("unchecked")
    final Map<String, Object> object = (Map<String, Object>) value;
    return object;
  }

  // ---- reading -----------------------------------------------------------------------------

  private static final int MAX_DEPTH = 64;

  private Object readValue(final int depth) {
    if (depth > MAX_DEPTH) {
      throw new JsonException(
          "nesting too deep"); // bounded: a hostile payload cannot blow the stack
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
        return readString();
      case 't':
        expect("true");
        return Boolean.TRUE;
      case 'f':
        expect("false");
        return Boolean.FALSE;
      case 'n':
        expect("null");
        return null;
      default:
        return readNumber();
    }
  }

  private Map<String, Object> readObject(final int depth) {
    final Map<String, Object> object = new LinkedHashMap<>();
    index++; // consume '{'
    skipWhitespace();
    if (peek() == '}') {
      index++;
      return object;
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
      object.put(name, readValue(depth + 1));
      skipWhitespace();
      final char next = peek();
      index++;
      if (next == '}') {
        return object;
      }
      if (next != ',') {
        throw new JsonException("expected ',' or '}'");
      }
    }
  }

  private List<Object> readArray(final int depth) {
    final List<Object> array = new ArrayList<>();
    index++; // consume '['
    skipWhitespace();
    if (peek() == ']') {
      index++;
      return array;
    }
    while (true) {
      skipWhitespace();
      array.add(readValue(depth + 1));
      skipWhitespace();
      final char next = peek();
      index++;
      if (next == ']') {
        return array;
      }
      if (next != ',') {
        throw new JsonException("expected ',' or ']'");
      }
    }
  }

  private String readString() {
    index++; // consume opening quote
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

  private Double readNumber() {
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
      return Double.valueOf(literal);
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

  // ---- typed accessors ---------------------------------------------------------------------

  /**
   * Reads a nested object member.
   *
   * @param object the containing object
   * @param name the member name
   * @return the member object, or {@code null} if absent or not an object
   */
  public static Map<String, Object> objectAt(final Map<String, Object> object, final String name) {
    final Object value = object == null ? null : object.get(name);
    if (!(value instanceof Map)) {
      return null;
    }
    @SuppressWarnings("unchecked")
    final Map<String, Object> nested = (Map<String, Object>) value;
    return nested;
  }

  /**
   * Reads an array member.
   *
   * @param object the containing object
   * @param name the member name
   * @return the member list, or an empty list if absent or not an array
   */
  public static List<Object> arrayAt(final Map<String, Object> object, final String name) {
    final Object value = object == null ? null : object.get(name);
    if (!(value instanceof List)) {
      return List.of();
    }
    @SuppressWarnings("unchecked")
    final List<Object> array = (List<Object>) value;
    return array;
  }

  /**
   * Reads a string member.
   *
   * @param object the containing object
   * @param name the member name
   * @return the member string, or {@code null} if absent or not a string
   */
  public static String stringAt(final Map<String, Object> object, final String name) {
    final Object value = object == null ? null : object.get(name);
    return value instanceof String text ? text : null;
  }

  /**
   * Reads a numeric member as a long.
   *
   * @param object the containing object
   * @param name the member name
   * @param fallback the value returned when the member is absent or not numeric
   * @return the member value, or {@code fallback}
   */
  public static long longAt(
      final Map<String, Object> object, final String name, final long fallback) {
    final Object value = object == null ? null : object.get(name);
    return value instanceof Double number ? number.longValue() : fallback;
  }

  // ---- writing -----------------------------------------------------------------------------

  /**
   * Escapes and quotes a string for inclusion in a JSON document.
   *
   * @param value the raw string
   * @return the quoted, escaped literal
   */
  public static String quote(final String value) {
    final StringBuilder out = new StringBuilder(value.length() + 2).append('"');
    for (int i = 0; i < value.length(); i++) {
      final char c = value.charAt(i);
      switch (c) {
        case '"' -> out.append("\\\"");
        case '\\' -> out.append("\\\\");
        case '\b' -> out.append("\\b");
        case '\f' -> out.append("\\f");
        case '\n' -> out.append("\\n");
        case '\r' -> out.append("\\r");
        case '\t' -> out.append("\\t");
        default -> {
          if (c < 0x20) {
            out.append(String.format("\\u%04x", (int) c));
          } else {
            out.append(c);
          }
        }
      }
    }
    return out.append('"').toString();
  }
}

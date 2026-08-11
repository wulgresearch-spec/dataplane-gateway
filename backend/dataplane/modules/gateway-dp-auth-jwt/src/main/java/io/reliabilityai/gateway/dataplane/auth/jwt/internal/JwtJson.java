package io.reliabilityai.gateway.dataplane.auth.jwt.internal;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A strict JSON reader for JWT headers and payloads.
 *
 * <p>Hand-rolled for one reason that matters: it <b>rejects duplicate member names</b>. Most JSON
 * parsers silently keep the last occurrence, which is a documented JWT attack — a token carrying
 * {@code {"exp":<past>,"exp":<future>}} can be read one way by the gateway and another by a
 * downstream system. Here a repeated claim is a parse failure, so the ambiguity can never be
 * resolved in an attacker's favour.
 *
 * <p>Produces only {@code Map}, {@code List}, {@code String}, {@code Double}, {@code Boolean} and
 * {@code null} — never an application type — and bounds nesting depth. Error messages are
 * structural and never echo token content.
 */
public final class JwtJson {

  /** Thrown when input is not well-formed JSON, or contains a duplicate member name. */
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

  private static final int MAX_DEPTH = 16;

  private final String source;
  private int index;

  private JwtJson(final String source) {
    this.source = source;
  }

  /**
   * Parses a JWT header or payload.
   *
   * @param text the JSON document
   * @return the parsed object
   * @throws JsonException if the document is malformed or repeats a member name
   */
  public static Map<String, Object> parseObject(final String text) {
    if (text == null) {
      throw new JsonException("null document");
    }
    final JwtJson parser = new JwtJson(text);
    parser.skipWhitespace();
    final Object value = parser.readValue(0);
    parser.skipWhitespace();
    if (parser.index != text.length()) {
      throw new JsonException("trailing content");
    }
    if (!(value instanceof Map)) {
      throw new JsonException("expected object");
    }
    @SuppressWarnings("unchecked")
    final Map<String, Object> object = (Map<String, Object>) value;
    return object;
  }

  private Object readValue(final int depth) {
    if (depth > MAX_DEPTH) {
      throw new JsonException("nesting too deep");
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
    index++;
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
      final Object value = readValue(depth + 1);
      if (object.putIfAbsent(name, value) != null) {
        // See the class note: a repeated claim is an ambiguity, never a last-one-wins merge.
        throw new JsonException("duplicate member");
      }
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
    index++;
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
   * Reads a numeric member as epoch seconds.
   *
   * @param object the containing object
   * @param name the member name
   * @return the value, or {@code null} if absent or not numeric
   */
  public static Long numberAt(final Map<String, Object> object, final String name) {
    final Object value = object == null ? null : object.get(name);
    return value instanceof Double number ? Long.valueOf(number.longValue()) : null;
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
   * Reads an audience member, which JWT allows to be a string or an array of strings.
   *
   * @param object the containing object
   * @param name the member name
   * @return the audience values, possibly empty
   */
  public static List<String> audienceAt(final Map<String, Object> object, final String name) {
    final Object value = object == null ? null : object.get(name);
    if (value instanceof String single) {
      return List.of(single);
    }
    if (!(value instanceof List)) {
      return List.of();
    }
    final List<String> values = new ArrayList<>();
    for (final Object entry : (List<?>) value) {
      if (entry instanceof String text) {
        values.add(text);
      }
    }
    return List.copyOf(values);
  }
}

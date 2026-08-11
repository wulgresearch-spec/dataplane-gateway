package io.reliabilityai.gateway.dataplane.schema.validator.domain;

import io.reliabilityai.gateway.dataplane.schema.validator.internal.Json;
import java.util.Optional;

/** The JSON Schema type keywords this engine understands. */
public enum JsonType {

  /** A JSON object. */
  OBJECT("object"),

  /** A JSON array. */
  ARRAY("array"),

  /** A JSON string. */
  STRING("string"),

  /** Any JSON number. */
  NUMBER("number"),

  /** A JSON number with no fractional part — {@code 1.0} qualifies, {@code 1.5} does not. */
  INTEGER("integer"),

  /** A JSON boolean. */
  BOOLEAN("boolean"),

  /** JSON null. */
  NULL("null");

  private final String keyword;

  JsonType(final String keyword) {
    this.keyword = keyword;
  }

  /**
   * The schema keyword for this type.
   *
   * @return the keyword
   */
  public String keyword() {
    return keyword;
  }

  /**
   * Resolves a {@code type} keyword value.
   *
   * @param keyword the schema keyword
   * @return the type, or empty when unrecognised
   */
  public static Optional<JsonType> fromKeyword(final String keyword) {
    for (final JsonType type : values()) {
      if (type.keyword.equals(keyword)) {
        return Optional.of(type);
      }
    }
    return Optional.empty();
  }

  /**
   * Whether an instance satisfies this type.
   *
   * @param value the instance value
   * @return {@code true} when the value is of this type
   */
  public boolean matches(final Json.JsonValue value) {
    return switch (this) {
      case OBJECT -> value instanceof Json.JsonObject;
      case ARRAY -> value instanceof Json.JsonArray;
      case STRING -> value instanceof Json.JsonString;
      case BOOLEAN -> value instanceof Json.JsonBoolean;
      case NULL -> value instanceof Json.JsonNull;
      case NUMBER -> value instanceof Json.JsonNumber;
      case INTEGER -> value instanceof Json.JsonNumber number && number.isInteger();
    };
  }
}

package io.reliabilityai.gateway.dataplane.schema.validator.domain;

import io.reliabilityai.gateway.dataplane.schema.validator.api.SchemaValidatorConfig;
import io.reliabilityai.gateway.dataplane.schema.validator.internal.Json;
import io.reliabilityai.gateway.dataplane.schemalock.domain.SchemaViolation;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * Walks a compiled schema against an instance and collects violations.
 *
 * <p>Pure and allocation-light: it mutates neither the schema nor the instance, holds no state
 * between calls, and is therefore safe to use concurrently from any number of threads.
 *
 * <p><b>Deterministic by construction.</b> Object properties are visited in sorted order and array
 * elements in index order, so the same schema and instance always produce the same violations in
 * the same sequence — which is what makes a validation failure reproducible in an audit months
 * later.
 *
 * <p><b>Violations name kinds, not values.</b> {@link SchemaViolation} records the <em>type</em>
 * that was found, never the value. Violations flow into telemetry and correctness sinks, and
 * echoing the model's actual output there would turn a validation record into a content leak.
 */
public final class ValidationEngine {

  private final SchemaValidatorConfig config;

  /**
   * Creates the engine.
   *
   * @param config the validation bounds
   */
  public ValidationEngine(final SchemaValidatorConfig config) {
    this.config = config;
  }

  /**
   * Validates an instance against a compiled schema.
   *
   * @param graph the compiled schema
   * @param instance the instance value
   * @return the violations, capped at the configured maximum and empty when the instance conforms
   */
  public List<SchemaViolation> validate(
      final CompiledSchemaGraph graph, final Json.JsonValue instance) {
    final List<SchemaViolation> violations = new ArrayList<>();
    apply(graph, graph.root(), instance, "", violations, 0);
    return List.copyOf(violations);
  }

  private void apply(
      final CompiledSchemaGraph graph,
      final SchemaNode rawNode,
      final Json.JsonValue value,
      final String pointer,
      final List<SchemaViolation> violations,
      final int depth) {

    if (violations.size() >= config.maxViolations() || depth > config.maxDepth()) {
      return;
    }
    final SchemaNode node = graph.resolve(rawNode);
    if (node == null || node.isUnconstrained()) {
      return;
    }

    checkType(node, value, pointer, violations);
    checkConstAndEnum(node, value, pointer, violations);
    checkCombinators(graph, node, value, pointer, violations, depth);

    if (value instanceof Json.JsonString text) {
      checkString(node, text, pointer, violations);
    } else if (value instanceof Json.JsonNumber number) {
      checkNumber(node, number, pointer, violations);
    } else if (value instanceof Json.JsonObject object) {
      checkObject(graph, node, object, pointer, violations, depth);
    } else if (value instanceof Json.JsonArray array) {
      checkArray(graph, node, array, pointer, violations, depth);
    }
  }

  // ---- keyword groups ---------------------------------------------------------------------------

  private void checkType(
      final SchemaNode node,
      final Json.JsonValue value,
      final String pointer,
      final List<SchemaViolation> violations) {
    if (node.types().isEmpty()) {
      return;
    }
    for (final JsonType type : node.types()) {
      if (type.matches(value)) {
        return;
      }
    }
    add(violations, pointer, "type", expectedTypes(node.types()), value.kind());
  }

  private void checkConstAndEnum(
      final SchemaNode node,
      final Json.JsonValue value,
      final String pointer,
      final List<SchemaViolation> violations) {
    if (node.constValue() != null && !node.constValue().equals(value)) {
      add(violations, pointer, "const", node.constValue().kind(), value.kind());
    }
    if (!node.enumValues().isEmpty() && !node.enumValues().contains(value)) {
      add(
          violations,
          pointer,
          "enum",
          node.enumValues().size() + " permitted values",
          value.kind());
    }
  }

  private void checkString(
      final SchemaNode node,
      final Json.JsonString text,
      final String pointer,
      final List<SchemaViolation> violations) {
    // Count code points, not chars: a surrogate pair is one character to a user and to the schema.
    final int length = text.value().codePointCount(0, text.value().length());
    if (node.minLength() != null && length < node.minLength()) {
      add(violations, pointer, "minLength", ">= " + node.minLength(), "length " + length);
    }
    if (node.maxLength() != null && length > node.maxLength()) {
      add(violations, pointer, "maxLength", "<= " + node.maxLength(), "length " + length);
    }
    if (node.pattern() != null && !node.pattern().matcher(text.value()).find()) {
      add(violations, pointer, "pattern", "matching pattern", "string");
    }
  }

  private void checkNumber(
      final SchemaNode node,
      final Json.JsonNumber number,
      final String pointer,
      final List<SchemaViolation> violations) {
    final BigDecimal value = number.value();
    if (node.minimum() != null && value.compareTo(node.minimum()) < 0) {
      add(violations, pointer, "minimum", ">= " + node.minimum().toPlainString(), "number");
    }
    if (node.maximum() != null && value.compareTo(node.maximum()) > 0) {
      add(violations, pointer, "maximum", "<= " + node.maximum().toPlainString(), "number");
    }
    if (node.exclusiveMinimum() != null && value.compareTo(node.exclusiveMinimum()) <= 0) {
      add(
          violations,
          pointer,
          "exclusiveMinimum",
          "> " + node.exclusiveMinimum().toPlainString(),
          "number");
    }
    if (node.exclusiveMaximum() != null && value.compareTo(node.exclusiveMaximum()) >= 0) {
      add(
          violations,
          pointer,
          "exclusiveMaximum",
          "< " + node.exclusiveMaximum().toPlainString(),
          "number");
    }
  }

  private void checkObject(
      final CompiledSchemaGraph graph,
      final SchemaNode node,
      final Json.JsonObject object,
      final String pointer,
      final List<SchemaViolation> violations,
      final int depth) {

    for (final String name : node.required()) {
      if (!object.members().containsKey(name)) {
        add(violations, pointer, "required", name, "absent");
      }
    }

    // Sorted traversal keeps violation order stable regardless of the instance's member order.
    for (final String name : new TreeSet<>(object.members().keySet())) {
      if (violations.size() >= config.maxViolations()) {
        return;
      }
      final Json.JsonValue member = object.members().get(name);
      final SchemaNode propertySchema = node.properties().get(name);
      if (propertySchema != null) {
        apply(graph, propertySchema, member, pointer + '/' + escape(name), violations, depth + 1);
        continue;
      }
      if (!node.additionalPropertiesAllowed()) {
        add(
            violations,
            pointer + '/' + escape(name),
            "additionalProperties",
            "not permitted",
            member.kind());
      } else if (node.additionalProperties() != null) {
        apply(
            graph,
            node.additionalProperties(),
            member,
            pointer + '/' + escape(name),
            violations,
            depth + 1);
      }
    }
  }

  private void checkArray(
      final CompiledSchemaGraph graph,
      final SchemaNode node,
      final Json.JsonArray array,
      final String pointer,
      final List<SchemaViolation> violations,
      final int depth) {

    final int size = array.elements().size();
    if (node.minItems() != null && size < node.minItems()) {
      add(violations, pointer, "minItems", ">= " + node.minItems(), "length " + size);
    }
    if (node.maxItems() != null && size > node.maxItems()) {
      add(violations, pointer, "maxItems", "<= " + node.maxItems(), "length " + size);
    }
    if (node.uniqueItems()) {
      final Set<Json.JsonValue> seen = new LinkedHashSet<>();
      for (final Json.JsonValue element : array.elements()) {
        if (!seen.add(element)) {
          add(violations, pointer, "uniqueItems", "distinct elements", "duplicate");
          break;
        }
      }
    }

    for (int i = 0; i < size; i++) {
      if (violations.size() >= config.maxViolations()) {
        return;
      }
      final String elementPointer = pointer + '/' + i;
      if (i < node.prefixItems().size()) {
        apply(
            graph,
            node.prefixItems().get(i),
            array.elements().get(i),
            elementPointer,
            violations,
            depth + 1);
      } else if (node.items() != null) {
        apply(graph, node.items(), array.elements().get(i), elementPointer, violations, depth + 1);
      }
    }
  }

  private void checkCombinators(
      final CompiledSchemaGraph graph,
      final SchemaNode node,
      final Json.JsonValue value,
      final String pointer,
      final List<SchemaViolation> violations,
      final int depth) {

    for (final SchemaNode branch : node.allOf()) {
      apply(graph, branch, value, pointer, violations, depth + 1);
    }

    if (!node.anyOf().isEmpty() && matchCount(graph, node.anyOf(), value, depth) == 0) {
      // Report the failure at this node rather than every branch's violations: a caller needs to
      // know
      // the union failed, not read N contradictory explanations of why each alternative did not
      // fit.
      add(violations, pointer, "anyOf", "at least one of " + node.anyOf().size(), value.kind());
    }

    if (!node.oneOf().isEmpty()) {
      final int matches = matchCount(graph, node.oneOf(), value, depth);
      if (matches != 1) {
        add(
            violations,
            pointer,
            "oneOf",
            "exactly one of " + node.oneOf().size(),
            matches + " matched");
      }
    }

    if (node.not() != null && matches(graph, node.not(), value, depth)) {
      add(violations, pointer, "not", "must not match", value.kind());
    }
  }

  // ---- helpers ----------------------------------------------------------------------------------

  /** Whether a subschema matches, evaluated without recording violations. */
  private boolean matches(
      final CompiledSchemaGraph graph,
      final SchemaNode node,
      final Json.JsonValue value,
      final int depth) {
    final List<SchemaViolation> probe = new ArrayList<>();
    apply(graph, node, value, "", probe, depth + 1);
    return probe.isEmpty();
  }

  private int matchCount(
      final CompiledSchemaGraph graph,
      final List<SchemaNode> branches,
      final Json.JsonValue value,
      final int depth) {
    int matched = 0;
    for (final SchemaNode branch : branches) {
      if (matches(graph, branch, value, depth)) {
        matched++;
      }
    }
    return matched;
  }

  private void add(
      final List<SchemaViolation> violations,
      final String pointer,
      final String keyword,
      final String expected,
      final String actualKind) {
    if (violations.size() >= config.maxViolations()) {
      return;
    }
    violations.add(
        new SchemaViolation(pointer.isEmpty() ? "/" : pointer, keyword, expected, actualKind));
  }

  private static String expectedTypes(final Set<JsonType> types) {
    final StringBuilder out = new StringBuilder();
    for (final JsonType type : new TreeSet<>(types)) {
      if (out.length() > 0) {
        out.append('|');
      }
      out.append(type.keyword());
    }
    return out.toString();
  }

  /** RFC 6901 escaping: {@code ~} becomes {@code ~0} and {@code /} becomes {@code ~1}. */
  private static String escape(final String name) {
    return name.replace("~", "~0").replace("/", "~1");
  }

  /**
   * Reads the definitions table without exposing it, so callers cannot reach in and mutate a
   * compiled schema through the engine.
   *
   * @param graph the compiled schema
   * @return the definition names
   */
  public Set<String> definitionNames(final CompiledSchemaGraph graph) {
    return Map.copyOf(graph.definitions()).keySet();
  }
}

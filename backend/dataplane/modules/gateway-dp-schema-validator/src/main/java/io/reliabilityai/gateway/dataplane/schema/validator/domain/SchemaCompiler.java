package io.reliabilityai.gateway.dataplane.schema.validator.domain;

import io.reliabilityai.gateway.dataplane.schema.validator.api.SchemaValidatorConfig;
import io.reliabilityai.gateway.dataplane.schema.validator.internal.Json;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * Compiles JSON Schema 2020-12 text into an immutable {@link CompiledSchemaGraph}.
 *
 * <p>Compilation is where every hostile-schema defence lives, because it runs once per schema while
 * validation runs once per response. Node count, nesting depth, reference resolution and reference
 * acyclicity are all settled here, so the validator can walk the result without re-checking
 * anything.
 *
 * <p><b>Unknown keywords are ignored, not rejected.</b> That is what the specification requires,
 * and it is also what keeps a tenant's schema working when it carries annotations this subset does
 * not implement. Keywords the engine deliberately does not support are listed in the module's
 * limitations — silently ignoring a constraint would be worse than refusing it, so anything that
 * would *weaken* validation if ignored is rejected instead.
 */
public final class SchemaCompiler {

  /** Thrown when a schema cannot be compiled. Carries a content-free reason. */
  public static final class CompilationFailure extends RuntimeException {
    private static final long serialVersionUID = 1L;

    /**
     * Creates the failure.
     *
     * @param reason a content-free description
     */
    public CompilationFailure(final String reason) {
      super(reason);
    }
  }

  /**
   * Keywords that constrain validation and are not implemented here. Ignoring one would silently
   * make validation more permissive than the schema author asked for, so a schema using one is
   * refused.
   */
  private static final Set<String> REJECTED_KEYWORDS =
      Set.of(
          "unevaluatedProperties",
          "unevaluatedItems",
          "dependentSchemas",
          "dependentRequired",
          "if",
          "then",
          "else",
          "patternProperties",
          "propertyNames",
          "contains",
          "minContains",
          "maxContains",
          "multipleOf",
          "$dynamicRef",
          "$dynamicAnchor",
          "$anchor",
          "$recursiveRef");

  private static final String DEFS_PREFIX = "#/$defs/";

  private final SchemaValidatorConfig config;
  private int nodeCount;

  /**
   * Creates the compiler.
   *
   * @param config the compilation bounds
   */
  public SchemaCompiler(final SchemaValidatorConfig config) {
    this.config = config;
  }

  /**
   * Compiles schema text.
   *
   * @param schemaText the schema document
   * @return the compiled graph
   * @throws CompilationFailure if the schema is malformed, unsupported, cyclic or over-large
   */
  public CompiledSchemaGraph compile(final String schemaText) {
    if (schemaText == null || schemaText.length() > config.maxSchemaBytes()) {
      throw new CompilationFailure("schema-too-large");
    }
    final Json.JsonValue parsed;
    try {
      parsed = Json.parse(schemaText, config.maxDepth(), config.maxSchemaNodes());
    } catch (final Json.JsonException malformed) {
      // A schema bomb trips the parser's bounds before the compiler's. Report which limit was hit —
      // "not json" would send an operator hunting for a syntax error that does not exist.
      throw new CompilationFailure(
          switch (malformed.getMessage()) {
            case "too many nodes" -> "schema-too-complex";
            case "nesting too deep" -> "schema-too-deep";
            default -> "schema-not-json";
          });
    }
    if (!(parsed instanceof Json.JsonObject root)) {
      throw new CompilationFailure("schema-not-object");
    }

    nodeCount = 0;
    final Map<String, SchemaNode> definitions = new LinkedHashMap<>();
    final Json.JsonValue defs = root.members().get("$defs");
    if (defs instanceof Json.JsonObject defsObject) {
      for (final Map.Entry<String, Json.JsonValue> entry : defsObject.members().entrySet()) {
        definitions.put(entry.getKey(), compileNode(entry.getValue(), 0));
      }
    }

    final SchemaNode compiledRoot = compileNode(root, 0);
    verifyReferences(compiledRoot, definitions);
    return new CompiledSchemaGraph(compiledRoot, definitions, nodeCount);
  }

  private SchemaNode compileNode(final Json.JsonValue value, final int depth) {
    if (depth > config.maxDepth()) {
      throw new CompilationFailure("schema-too-deep");
    }
    if (++nodeCount > config.maxSchemaNodes()) {
      throw new CompilationFailure("schema-too-complex");
    }

    // The boolean schemas: `true` accepts everything, `false` accepts nothing.
    if (value instanceof Json.JsonBoolean flag) {
      return flag.value() ? unconstrained() : impossible();
    }
    if (!(value instanceof Json.JsonObject object)) {
      throw new CompilationFailure("subschema-not-object");
    }
    final Map<String, Json.JsonValue> members = object.members();

    for (final String keyword : members.keySet()) {
      if (REJECTED_KEYWORDS.contains(keyword)) {
        throw new CompilationFailure("unsupported-keyword");
      }
    }

    final String ref = string(members.get("$ref"));
    if (ref != null) {
      if (!ref.startsWith(DEFS_PREFIX)) {
        // Remote and network references are out of scope by design: resolving one would turn
        // validation into an outbound call on the response path.
        throw new CompilationFailure("unsupported-ref");
      }
      return reference(ref.substring(DEFS_PREFIX.length()));
    }

    return new SchemaNode(
        types(members.get("type")),
        properties(members.get("properties"), depth),
        requiredNames(members.get("required")),
        additionalAllowed(members.get("additionalProperties")),
        additionalSchema(members.get("additionalProperties"), depth),
        enumValues(members.get("enum")),
        members.get("const"),
        subschema(members.get("items"), depth),
        subschemas(members.get("prefixItems"), depth),
        number(members.get("minimum")),
        number(members.get("maximum")),
        number(members.get("exclusiveMinimum")),
        number(members.get("exclusiveMaximum")),
        nonNegativeInteger(members.get("minLength"), "minLength"),
        nonNegativeInteger(members.get("maxLength"), "maxLength"),
        compilePattern(string(members.get("pattern"))),
        nonNegativeInteger(members.get("minItems"), "minItems"),
        nonNegativeInteger(members.get("maxItems"), "maxItems"),
        booleanValue(members.get("uniqueItems")),
        subschemas(members.get("allOf"), depth),
        subschemas(members.get("anyOf"), depth),
        subschemas(members.get("oneOf"), depth),
        subschema(members.get("not"), depth),
        null);
  }

  /**
   * Proves every reference resolves and that no cycle exists.
   *
   * <p>A cyclic {@code $ref} would make validation recurse forever on a sufficiently nested
   * instance. Detecting it here — once, at compile time — means the validator never needs a cycle
   * guard on the hot path.
   */
  private void verifyReferences(final SchemaNode root, final Map<String, SchemaNode> definitions) {
    for (final Map.Entry<String, SchemaNode> entry : definitions.entrySet()) {
      walkForCycles(entry.getValue(), definitions, new LinkedHashSet<>(Set.of(entry.getKey())));
    }
    walkForCycles(root, definitions, new LinkedHashSet<>());
  }

  private void walkForCycles(
      final SchemaNode node,
      final Map<String, SchemaNode> definitions,
      final Set<String> visiting) {
    if (node == null) {
      return;
    }
    if (node.ref() != null) {
      final SchemaNode target = definitions.get(node.ref());
      if (target == null) {
        throw new CompilationFailure("unresolved-ref");
      }
      if (!visiting.add(node.ref())) {
        throw new CompilationFailure("cyclic-ref");
      }
      walkForCycles(target, definitions, visiting);
      visiting.remove(node.ref());
      return;
    }
    for (final SchemaNode child : node.properties().values()) {
      walkForCycles(child, definitions, visiting);
    }
    walkForCycles(node.additionalProperties(), definitions, visiting);
    walkForCycles(node.items(), definitions, visiting);
    for (final SchemaNode child : node.prefixItems()) {
      walkForCycles(child, definitions, visiting);
    }
    for (final SchemaNode child : node.allOf()) {
      walkForCycles(child, definitions, visiting);
    }
    for (final SchemaNode child : node.anyOf()) {
      walkForCycles(child, definitions, visiting);
    }
    for (final SchemaNode child : node.oneOf()) {
      walkForCycles(child, definitions, visiting);
    }
    walkForCycles(node.not(), definitions, visiting);
  }

  // ---- keyword compilation --------------------------------------------------------------------

  private Set<JsonType> types(final Json.JsonValue value) {
    if (value == null) {
      return Set.of();
    }
    final Set<JsonType> types = new LinkedHashSet<>();
    if (value instanceof Json.JsonString single) {
      types.add(
          JsonType.fromKeyword(single.value())
              .orElseThrow(() -> new CompilationFailure("unknown-type")));
      return types;
    }
    if (value instanceof Json.JsonArray array) {
      for (final Json.JsonValue element : array.elements()) {
        final String keyword = string(element);
        if (keyword == null) {
          throw new CompilationFailure("bad-type");
        }
        types.add(
            JsonType.fromKeyword(keyword)
                .orElseThrow(() -> new CompilationFailure("unknown-type")));
      }
      return types;
    }
    throw new CompilationFailure("bad-type");
  }

  private Map<String, SchemaNode> properties(final Json.JsonValue value, final int depth) {
    if (value == null) {
      return Map.of();
    }
    if (!(value instanceof Json.JsonObject object)) {
      throw new CompilationFailure("bad-properties");
    }
    final Map<String, SchemaNode> properties = new LinkedHashMap<>();
    for (final Map.Entry<String, Json.JsonValue> entry : object.members().entrySet()) {
      properties.put(entry.getKey(), compileNode(entry.getValue(), depth + 1));
    }
    return properties;
  }

  /** Sorted, so two compilations of the same schema report missing properties in the same order. */
  private static List<String> requiredNames(final Json.JsonValue value) {
    if (value == null) {
      return List.of();
    }
    if (!(value instanceof Json.JsonArray array)) {
      throw new CompilationFailure("bad-required");
    }
    final Set<String> names = new TreeSet<>();
    for (final Json.JsonValue element : array.elements()) {
      final String name = string(element);
      if (name == null) {
        throw new CompilationFailure("bad-required");
      }
      names.add(name);
    }
    return List.copyOf(names);
  }

  private static boolean additionalAllowed(final Json.JsonValue value) {
    return !(value instanceof Json.JsonBoolean flag) || flag.value();
  }

  private SchemaNode additionalSchema(final Json.JsonValue value, final int depth) {
    return value instanceof Json.JsonObject ? compileNode(value, depth + 1) : null;
  }

  private static List<Json.JsonValue> enumValues(final Json.JsonValue value) {
    if (value == null) {
      return List.of();
    }
    if (!(value instanceof Json.JsonArray array) || array.elements().isEmpty()) {
      throw new CompilationFailure("bad-enum");
    }
    return array.elements();
  }

  private SchemaNode subschema(final Json.JsonValue value, final int depth) {
    return value == null ? null : compileNode(value, depth + 1);
  }

  private List<SchemaNode> subschemas(final Json.JsonValue value, final int depth) {
    if (value == null) {
      return List.of();
    }
    if (!(value instanceof Json.JsonArray array)) {
      throw new CompilationFailure("bad-subschema-array");
    }
    final List<SchemaNode> nodes = new ArrayList<>(array.elements().size());
    for (final Json.JsonValue element : array.elements()) {
      nodes.add(compileNode(element, depth + 1));
    }
    return nodes;
  }

  private static BigDecimal number(final Json.JsonValue value) {
    if (value == null) {
      return null;
    }
    if (!(value instanceof Json.JsonNumber number)) {
      throw new CompilationFailure("bad-numeric-bound");
    }
    return number.value();
  }

  private static Integer nonNegativeInteger(final Json.JsonValue value, final String keyword) {
    if (value == null) {
      return null;
    }
    if (!(value instanceof Json.JsonNumber number) || !number.isInteger()) {
      throw new CompilationFailure("bad-" + keyword);
    }
    final int bound = number.value().intValue();
    if (bound < 0) {
      throw new CompilationFailure("bad-" + keyword);
    }
    return bound;
  }

  private static boolean booleanValue(final Json.JsonValue value) {
    return value instanceof Json.JsonBoolean flag && flag.value();
  }

  /**
   * Compiles a pattern.
   *
   * <p>Patterns come from tenant schemas and are applied to model output, so a catastrophically
   * backtracking expression would be a denial of service with a very small payload. An invalid
   * pattern is refused at compile time rather than at validation time.
   */
  private static Pattern compilePattern(final String pattern) {
    if (pattern == null) {
      return null;
    }
    try {
      return Pattern.compile(pattern);
    } catch (final PatternSyntaxException invalid) {
      throw new CompilationFailure("bad-pattern");
    }
  }

  private static String string(final Json.JsonValue value) {
    return value instanceof Json.JsonString text ? text.value() : null;
  }

  private static SchemaNode reference(final String definition) {
    return node(null, definition);
  }

  private static SchemaNode unconstrained() {
    return blank();
  }

  /**
   * The {@code false} schema, expressed as {@code not: {}} — "must not match the schema that
   * matches everything", which nothing can satisfy.
   */
  private static SchemaNode impossible() {
    return node(blank(), null);
  }

  private static SchemaNode blank() {
    return node(null, null);
  }

  /**
   * Builds an otherwise-unconstrained node, optionally carrying a {@code not} or a {@code $ref}.
   */
  private static SchemaNode node(final SchemaNode not, final String ref) {
    return new SchemaNode(
        Set.of(), Map.of(), List.of(), true, null, List.of(), null, null, List.of(), null, null,
        null, null, null, null, null, null, null, false, List.of(), List.of(), List.of(), not, ref);
  }
}

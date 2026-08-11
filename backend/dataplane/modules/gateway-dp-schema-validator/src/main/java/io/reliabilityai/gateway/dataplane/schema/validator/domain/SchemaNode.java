package io.reliabilityai.gateway.dataplane.schema.validator.domain;

import io.reliabilityai.gateway.dataplane.schema.validator.internal.Json;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * One compiled schema node: immutable, fully resolved, and ready to validate against.
 *
 * <p>Compilation happens once per schema; validation walks this structure and allocates nothing but
 * violations. Keeping the node immutable is what makes a compiled schema safe to share across
 * threads without a lock, and what guarantees validation cannot mutate the schema it was given.
 *
 * <p>{@code $ref} is stored as an unresolved key rather than an inlined subtree. Inlining would let
 * a schema that references the same definition many times expand exponentially — the classic schema
 * bomb — so references stay indirections and are looked up in the definitions table at validation
 * time. Acyclicity is proven at compile time, so that lookup can never loop.
 *
 * @param types the accepted JSON types, empty when unconstrained
 * @param properties the per-property subschemas
 * @param required the required property names, in sorted order for deterministic reporting
 * @param additionalPropertiesAllowed whether unlisted properties are permitted
 * @param additionalProperties the subschema applied to unlisted properties, or {@code null}
 * @param enumValues the permitted values, empty when unconstrained
 * @param constValue the single permitted value, or {@code null}
 * @param items the subschema for elements beyond {@code prefixItems}, or {@code null}
 * @param prefixItems the positional element subschemas
 * @param minimum the inclusive lower bound, or {@code null}
 * @param maximum the inclusive upper bound, or {@code null}
 * @param exclusiveMinimum the exclusive lower bound, or {@code null}
 * @param exclusiveMaximum the exclusive upper bound, or {@code null}
 * @param minLength the minimum string length, or {@code null}
 * @param maxLength the maximum string length, or {@code null}
 * @param pattern the string pattern, or {@code null}
 * @param minItems the minimum array length, or {@code null}
 * @param maxItems the maximum array length, or {@code null}
 * @param uniqueItems whether array elements must be distinct
 * @param allOf subschemas that must all match
 * @param anyOf subschemas of which at least one must match
 * @param oneOf subschemas of which exactly one must match
 * @param not a subschema that must not match, or {@code null}
 * @param ref the {@code $defs} key this node defers to, or {@code null}
 */
public record SchemaNode(
    Set<JsonType> types,
    Map<String, SchemaNode> properties,
    List<String> required,
    boolean additionalPropertiesAllowed,
    SchemaNode additionalProperties,
    List<Json.JsonValue> enumValues,
    Json.JsonValue constValue,
    SchemaNode items,
    List<SchemaNode> prefixItems,
    BigDecimal minimum,
    BigDecimal maximum,
    BigDecimal exclusiveMinimum,
    BigDecimal exclusiveMaximum,
    Integer minLength,
    Integer maxLength,
    Pattern pattern,
    Integer minItems,
    Integer maxItems,
    boolean uniqueItems,
    List<SchemaNode> allOf,
    List<SchemaNode> anyOf,
    List<SchemaNode> oneOf,
    SchemaNode not,
    String ref) {

  /** Defensively copies every collection so a compiled node is genuinely immutable. */
  public SchemaNode {
    types = Set.copyOf(types);
    properties = Map.copyOf(properties);
    required = List.copyOf(required);
    enumValues = List.copyOf(enumValues);
    prefixItems = List.copyOf(prefixItems);
    allOf = List.copyOf(allOf);
    anyOf = List.copyOf(anyOf);
    oneOf = List.copyOf(oneOf);
  }

  /**
   * Whether this node imposes no constraint at all — the {@code true} schema.
   *
   * @return {@code true} when nothing is constrained
   */
  public boolean isUnconstrained() {
    return types.isEmpty()
        && properties.isEmpty()
        && required.isEmpty()
        && additionalPropertiesAllowed
        && additionalProperties == null
        && enumValues.isEmpty()
        && constValue == null
        && items == null
        && prefixItems.isEmpty()
        && minimum == null
        && maximum == null
        && exclusiveMinimum == null
        && exclusiveMaximum == null
        && minLength == null
        && maxLength == null
        && pattern == null
        && minItems == null
        && maxItems == null
        && !uniqueItems
        && allOf.isEmpty()
        && anyOf.isEmpty()
        && oneOf.isEmpty()
        && not == null
        && ref == null;
  }
}

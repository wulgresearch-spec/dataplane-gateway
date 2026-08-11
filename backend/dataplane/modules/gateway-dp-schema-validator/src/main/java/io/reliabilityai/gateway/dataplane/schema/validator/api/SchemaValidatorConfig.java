package io.reliabilityai.gateway.dataplane.schema.validator.api;

/**
 * Bounds on schema compilation and validation (Doc 17 §resource-limits).
 *
 * <p>Every field here exists to stop a hostile or careless schema turning validation into a denial
 * of service. Schemas arrive from tenants, and output arrives from a model — neither is trusted
 * input, so depth, breadth and total work are all capped rather than assumed reasonable.
 *
 * @param maxDepth the deepest nesting accepted in a schema or an instance
 * @param maxSchemaNodes the largest schema node count accepted at compile time
 * @param maxInstanceNodes the largest instance node count accepted at validation time
 * @param maxSchemaBytes the largest schema text accepted
 * @param maxInstanceBytes the largest instance document accepted
 * @param maxViolations the number of violations collected before reporting stops
 * @param maxCachedSchemas the compiled-schema cache ceiling
 */
public record SchemaValidatorConfig(
    int maxDepth,
    int maxSchemaNodes,
    int maxInstanceNodes,
    int maxSchemaBytes,
    int maxInstanceBytes,
    int maxViolations,
    int maxCachedSchemas) {

  /** The violation cap required by Doc 17. */
  public static final int DEFAULT_MAX_VIOLATIONS = 100;

  /** Validates the bounds. */
  public SchemaValidatorConfig {
    requirePositive(maxDepth, "maxDepth");
    requirePositive(maxSchemaNodes, "maxSchemaNodes");
    requirePositive(maxInstanceNodes, "maxInstanceNodes");
    requirePositive(maxSchemaBytes, "maxSchemaBytes");
    requirePositive(maxInstanceBytes, "maxInstanceBytes");
    requirePositive(maxViolations, "maxViolations");
    requirePositive(maxCachedSchemas, "maxCachedSchemas");
  }

  private static void requirePositive(final int value, final String field) {
    if (value < 1) {
      throw new IllegalArgumentException(field + " must be >= 1");
    }
  }

  /**
   * Defaults sized for tool-call and structured-output schemas.
   *
   * @return the default bounds
   */
  public static SchemaValidatorConfig defaults() {
    return new SchemaValidatorConfig(
        32, 10_000, 100_000, 256 * 1024, 4 * 1024 * 1024, DEFAULT_MAX_VIOLATIONS, 512);
  }
}

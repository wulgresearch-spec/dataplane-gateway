package io.reliabilityai.gateway.dataplane.schemalock.api;

import io.reliabilityai.gateway.common.Preconditions;
import io.reliabilityai.gateway.dataplane.schemalock.domain.SchemaId;

/**
 * A caller-declared output schema (Doc 17 §5) — JSON Schema Draft 2020-12 text plus its
 * content-hash {@link SchemaId} and version. It is <b>tenant-governed configuration metadata</b>
 * (Doc 17 §10.1): never logged, never in telemetry, never cross-tenant-exposed. Immutable.
 *
 * @param schemaText the caller's JSON Schema document text
 * @param schemaId the content-hash id (Doc 17 §10)
 * @param version the schema version
 */
public record OutputSchema(String schemaText, SchemaId schemaId, String version) {

  /**
   * Compact constructor validating fields and enforcing the content-hash invariant (Doc 17
   * §10/§10.1).
   */
  public OutputSchema {
    Preconditions.requireNonBlank(schemaText, "schemaText");
    Preconditions.requireNonNull(schemaId, "schemaId");
    Preconditions.requireNonBlank(version, "version");
    // The compiled-schema cache is keyed on schemaId; a schemaId that is NOT the content hash of
    // schemaText would let two different schemas collide on one cache key (wrong CompiledSchema
    // served,
    // SL-D5). Enforce the invariant even when a caller bypasses the of(...) factory.
    if (!schemaId.equals(SchemaId.of(schemaText))) {
      throw new IllegalArgumentException("schemaId must be the content hash of schemaText");
    }
  }

  /**
   * Creates an output schema, deriving the content-hash id from the schema text (Doc 17 §10).
   *
   * @param schemaText the JSON Schema document text
   * @param version the schema version
   * @return the output schema
   */
  public static OutputSchema of(final String schemaText, final String version) {
    return new OutputSchema(schemaText, SchemaId.of(schemaText), version);
  }
}

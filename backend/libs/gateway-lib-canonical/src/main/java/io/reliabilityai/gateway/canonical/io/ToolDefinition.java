package io.reliabilityai.gateway.canonical.io;

import io.reliabilityai.gateway.common.Preconditions;

/**
 * A provider-neutral tool definition within a {@link CanonicalRequest} (Doc 25 §6, Doc 17). The
 * schema is validated by SchemaLock (Doc 17); this model carries it verbatim.
 *
 * @param name the tool name
 * @param schemaJson the tool argument schema, as JSON text
 */
public record ToolDefinition(String name, String schemaJson) {

  /** Compact constructor validating name and schema presence. */
  public ToolDefinition {
    Preconditions.requireNonBlank(name, "name");
    Preconditions.requireNonBlank(schemaJson, "schemaJson");
  }
}

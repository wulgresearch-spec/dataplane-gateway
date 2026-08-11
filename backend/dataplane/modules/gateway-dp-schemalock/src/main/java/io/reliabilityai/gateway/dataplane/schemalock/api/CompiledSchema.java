package io.reliabilityai.gateway.dataplane.schemalock.api;

import io.reliabilityai.gateway.common.Preconditions;
import io.reliabilityai.gateway.dataplane.schemalock.domain.SchemaId;

/**
 * An immutable, shareable handle to a compiled validation plan (Doc 17 §5/§9, SL-D6). The actual
 * validation plan lives inside the replaceable {@code SchemaValidatorPort} adapter (keyed on {@link
 * #schemaId()}); this handle is the neutral, thread-safe, cacheable reference the engine passes
 * back to the validator. It carries the schema's complexity score (computed once at compile) used
 * for deterministic strategy selection (Doc 17 §14). Immutable and safely shared across virtual
 * threads (Doc 17 §32).
 *
 * @param schemaId the content-hash id
 * @param version the schema version
 * @param complexity the schema complexity score (Doc 17 §14; drives native/tool downgrade)
 */
public record CompiledSchema(SchemaId schemaId, String version, int complexity) {

  /** Compact constructor validating fields. */
  public CompiledSchema {
    Preconditions.requireNonNull(schemaId, "schemaId");
    Preconditions.requireNonBlank(version, "version");
    if (complexity < 0) {
      throw new IllegalArgumentException("complexity must be non-negative");
    }
  }
}

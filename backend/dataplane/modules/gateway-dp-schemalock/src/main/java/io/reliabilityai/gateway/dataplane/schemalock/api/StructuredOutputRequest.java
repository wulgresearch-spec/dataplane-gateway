package io.reliabilityai.gateway.dataplane.schemalock.api;

import io.reliabilityai.gateway.canonical.identity.CorrelationId;
import io.reliabilityai.gateway.common.Preconditions;
import io.reliabilityai.gateway.dataplane.schemalock.domain.CapabilityDescriptor;
import io.reliabilityai.gateway.dataplane.schemalock.domain.SchemaId;

/**
 * The neutral structured-output request context (Doc 17 §5). Carries the content-hash {@link
 * SchemaId}, the correlation id (envelope, Doc 07 §6), the neutral provider {@link
 * CapabilityDescriptor} (Doc 17 §13), and the {@link Mode}. Provider-agnostic — no provider-native
 * field (AD-007). Immutable.
 *
 * @param schemaId the content-hash schema id
 * @param correlationId the request correlation id
 * @param capability the neutral provider capability descriptor
 * @param mode batch or stream
 */
public record StructuredOutputRequest(
    SchemaId schemaId, CorrelationId correlationId, CapabilityDescriptor capability, Mode mode) {

  /** Compact constructor validating fields. */
  public StructuredOutputRequest {
    Preconditions.requireNonNull(schemaId, "schemaId");
    Preconditions.requireNonNull(correlationId, "correlationId");
    Preconditions.requireNonNull(capability, "capability");
    Preconditions.requireNonNull(mode, "mode");
  }
}

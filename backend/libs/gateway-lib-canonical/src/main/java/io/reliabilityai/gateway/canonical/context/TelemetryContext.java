package io.reliabilityai.gateway.canonical.context;

import io.reliabilityai.gateway.canonical.identity.CorrelationId;
import io.reliabilityai.gateway.canonical.identity.Region;
import io.reliabilityai.gateway.common.ContentFree;
import io.reliabilityai.gateway.common.Preconditions;

/**
 * Telemetry context (C9, Doc 33 §10.1, Doc 27). Content-free by construction (Doc 27 §15.1);
 * carries only correlation and region for correlation of content-free signals. Immutable.
 *
 * @param correlationId the correlation id
 * @param region the residency region
 */
public record TelemetryContext(CorrelationId correlationId, Region region) implements ContentFree {

  /** Compact constructor validating required fields. */
  public TelemetryContext {
    Preconditions.requireNonNull(correlationId, "correlationId");
    Preconditions.requireNonNull(region, "region");
  }
}

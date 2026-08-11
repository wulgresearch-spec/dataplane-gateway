package io.reliabilityai.gateway.canonical.context;

import io.reliabilityai.gateway.canonical.identity.CausationId;
import io.reliabilityai.gateway.canonical.identity.CorrelationId;
import io.reliabilityai.gateway.canonical.identity.TenantScope;
import io.reliabilityai.gateway.common.Preconditions;

/**
 * Correlation context (C9, Doc 33 §10.1, Doc 07 §6). Baggage carries the correlation id and tenant
 * scope only — never PII/content (Doc 14 §6). Immutable; content-free. {@code tenantScope} may be
 * null before C6 authentication (Doc 32 §SPT); {@code tracestate} is optional.
 *
 * @param correlationId the correlation id
 * @param causationId the causation id
 * @param tenantScope the tenant scope (nullable pre-authentication)
 * @param traceparent the W3C traceparent
 * @param tracestate the W3C tracestate (nullable)
 */
public record CorrelationContext(
    CorrelationId correlationId,
    CausationId causationId,
    TenantScope tenantScope,
    String traceparent,
    String tracestate) {

  /** Compact constructor validating the required ids. */
  public CorrelationContext {
    Preconditions.requireNonNull(correlationId, "correlationId");
    Preconditions.requireNonNull(causationId, "causationId");
  }
}

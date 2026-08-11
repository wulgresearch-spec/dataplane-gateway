package io.reliabilityai.gateway.ports;

import io.reliabilityai.gateway.canonical.decision.RouteTarget;
import io.reliabilityai.gateway.canonical.identity.CorrelationId;
import io.reliabilityai.gateway.canonical.identity.TenantScope;
import io.reliabilityai.gateway.common.Preconditions;

/**
 * The Provider Adapter's request to the Secrets Provider to materialize a credential (Doc 26 §6).
 * Carries no credential — only the tenant/route scope for which a credential is needed. Immutable.
 *
 * @param tenantScope the tenant scope
 * @param routeTarget the route target (canonical; provider identity internal to the adapter)
 * @param correlationId the request correlation id
 */
public record CredentialRequest(
    TenantScope tenantScope, RouteTarget routeTarget, CorrelationId correlationId) {

  /** Compact constructor validating required fields. */
  public CredentialRequest {
    Preconditions.requireNonNull(tenantScope, "tenantScope");
    Preconditions.requireNonNull(routeTarget, "routeTarget");
    Preconditions.requireNonNull(correlationId, "correlationId");
  }
}

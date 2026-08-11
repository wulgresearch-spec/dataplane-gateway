package io.reliabilityai.gateway.canonical.context;

import io.reliabilityai.gateway.canonical.identity.CausationId;
import io.reliabilityai.gateway.canonical.identity.CorrelationId;
import io.reliabilityai.gateway.canonical.identity.IdempotencyKey;
import io.reliabilityai.gateway.canonical.identity.Region;
import io.reliabilityai.gateway.common.Preconditions;

/**
 * The provider-neutral request context established at Ingress (Doc 30, Doc 33 §10.1). Carries the
 * threaded request identity (Doc 33 §TIC). Immutable; transient (not persisted). Tenant scope is
 * NOT part of this context — it is resolved only after C6 authentication (Doc 30 §IAB-5, Doc 32
 * §SPT).
 *
 * @param correlationId the request correlation id (Doc 30 §9.1)
 * @param idempotencyKey the client-supplied idempotency key (Doc 12 §14)
 * @param causationId the causation id
 * @param traceparent the W3C traceparent (Doc 12 §24)
 * @param region the residency region (AD-014)
 */
public record RequestContext(
    CorrelationId correlationId,
    IdempotencyKey idempotencyKey,
    CausationId causationId,
    String traceparent,
    Region region) {

  /** Compact constructor validating the required identity fields. */
  public RequestContext {
    Preconditions.requireNonNull(correlationId, "correlationId");
    Preconditions.requireNonNull(idempotencyKey, "idempotencyKey");
    Preconditions.requireNonNull(causationId, "causationId");
    Preconditions.requireNonNull(region, "region");
  }
}

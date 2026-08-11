package io.reliabilityai.gateway.dataplane.cost.api;

import io.reliabilityai.gateway.canonical.identity.AttemptId;
import io.reliabilityai.gateway.canonical.identity.CanonicalModelId;
import io.reliabilityai.gateway.canonical.identity.CorrelationId;
import io.reliabilityai.gateway.canonical.identity.IdempotencyKey;
import io.reliabilityai.gateway.canonical.identity.Region;
import io.reliabilityai.gateway.canonical.identity.TenantScope;
import io.reliabilityai.gateway.common.Preconditions;
import io.reliabilityai.gateway.dataplane.cost.domain.Phase;
import java.util.OptionalLong;

/**
 * The neutral cost request context (Doc 22 §6). Provider-agnostic (AD-007). For a projection it
 * carries the known input tokens and the authoritative declared max-output bound (Doc 22 §23.1);
 * for the actual phase the usage is supplied separately to {@code compute}. Immutable.
 *
 * @param tenantScope the tenant scope (isolation + contract resolution, AD-021)
 * @param canonicalModelId the canonical model id (Doc 19 §9.2)
 * @param region the resolved residency region (Doc 22 §CE-D11)
 * @param correlationId the request correlation id
 * @param idempotencyKey the request idempotency key (delivered-vs-attempt, Doc 22 §CA-10)
 * @param attemptId the attempt id (provider-spend attribution, Doc 22 §CA-1)
 * @param phase projection or actual (Doc 22 §6)
 * @param inputTokens the known input (prompt) tokens for a projection bound
 * @param declaredMaxOutputTokens the authoritative declared max-output bound (empty ⇒ projection
 *     fails closed {@code USAGE_UNBOUNDED}, Doc 22 §23.1)
 * @param delivered whether this attempt is the delivered outcome (Reliability's, Doc 22 §CA-11)
 * @param allowEstimatedCharge whether upstream policy permits a flagged estimated charge (Doc 22
 *     §24)
 */
public record CostRequest(
    TenantScope tenantScope,
    CanonicalModelId canonicalModelId,
    Region region,
    CorrelationId correlationId,
    IdempotencyKey idempotencyKey,
    AttemptId attemptId,
    Phase phase,
    long inputTokens,
    OptionalLong declaredMaxOutputTokens,
    boolean delivered,
    boolean allowEstimatedCharge) {

  /** Compact constructor validating fields. */
  public CostRequest {
    Preconditions.requireNonNull(tenantScope, "tenantScope");
    Preconditions.requireNonNull(canonicalModelId, "canonicalModelId");
    Preconditions.requireNonNull(region, "region");
    Preconditions.requireNonNull(correlationId, "correlationId");
    Preconditions.requireNonNull(idempotencyKey, "idempotencyKey");
    Preconditions.requireNonNull(attemptId, "attemptId");
    Preconditions.requireNonNull(phase, "phase");
    Preconditions.requireNonNegative(inputTokens, "inputTokens");
    Preconditions.requireNonNull(declaredMaxOutputTokens, "declaredMaxOutputTokens");
  }
}

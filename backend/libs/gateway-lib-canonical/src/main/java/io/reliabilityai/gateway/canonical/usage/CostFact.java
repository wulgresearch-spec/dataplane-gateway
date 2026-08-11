package io.reliabilityai.gateway.canonical.usage;

import io.reliabilityai.gateway.canonical.identity.CanonicalModelId;
import io.reliabilityai.gateway.canonical.identity.ExecutionIdentity;
import io.reliabilityai.gateway.canonical.identity.TenantScope;
import io.reliabilityai.gateway.common.ContentFree;
import io.reliabilityai.gateway.common.Preconditions;
import java.time.Instant;

/**
 * The priced cost fact (Doc 33 §10.5, Doc 22). Priced by the Cost Engine from a recorded usage fact
 * (Doc 22 §20); the Cost Engine never re-derives usage (Doc 22 §CE-D10). Immutable; content-free.
 * Amount is in integer micro-units to avoid floating-point drift.
 *
 * @param executionIdentity the execution identity of the priced usage
 * @param tenantScope the tenant scope
 * @param canonicalModelId the canonical model id
 * @param amountMicros the cost amount in micro-units (non-negative)
 * @param currency the ISO currency code
 * @param timestamp the fact timestamp
 */
public record CostFact(
    ExecutionIdentity executionIdentity,
    TenantScope tenantScope,
    CanonicalModelId canonicalModelId,
    long amountMicros,
    String currency,
    Instant timestamp)
    implements ContentFree {

  /** Compact constructor validating required fields and amount. */
  public CostFact {
    Preconditions.requireNonNull(executionIdentity, "executionIdentity");
    Preconditions.requireNonNull(tenantScope, "tenantScope");
    Preconditions.requireNonNull(canonicalModelId, "canonicalModelId");
    Preconditions.requireNonNegative(amountMicros, "amountMicros");
    Preconditions.requireNonBlank(currency, "currency");
    Preconditions.requireNonNull(timestamp, "timestamp");
  }
}

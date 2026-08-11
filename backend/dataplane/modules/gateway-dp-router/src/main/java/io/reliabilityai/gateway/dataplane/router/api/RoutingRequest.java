package io.reliabilityai.gateway.dataplane.router.api;

import io.reliabilityai.gateway.canonical.identity.CanonicalModelId;
import io.reliabilityai.gateway.canonical.identity.CorrelationId;
import io.reliabilityai.gateway.canonical.identity.Region;
import io.reliabilityai.gateway.canonical.identity.TenantScope;
import io.reliabilityai.gateway.common.Preconditions;
import java.util.Set;

/**
 * The neutral, provider-agnostic routing request (Doc 19 §6). Carries the canonical model, the
 * stable correlation id used for deterministic tiebreaking (Doc 19 §13.1), the tenant/residency
 * scope, and the normalized hard requirements (capabilities, context, compliance, cost ceiling)
 * plus soft preferences. Immutable; sets defensively copied.
 *
 * @param canonicalModelId the requested canonical model
 * @param correlationId the stable correlation id (deterministic tiebreak seed, Doc 19 §13.1)
 * @param tenantScope the tenant scope (policy resolution + isolation, AD-021)
 * @param region the residency region (hard tier 3, AD-014)
 * @param requiredCapabilities canonical required capabilities (hard tier 4, Doc 19 §9.2)
 * @param minContextLength the minimum required context length (0 = no requirement)
 * @param requiredCompliance canonical required compliance regimes (hard tier 2)
 * @param costCeilingMicros the cost ceiling in micro-units (0 = no ceiling; over ⇒ eliminated)
 * @param preferredCandidates soft-preferred candidate route ids (tier 9 bonus)
 */
public record RoutingRequest(
    CanonicalModelId canonicalModelId,
    CorrelationId correlationId,
    TenantScope tenantScope,
    Region region,
    Set<String> requiredCapabilities,
    int minContextLength,
    Set<String> requiredCompliance,
    long costCeilingMicros,
    Set<String> preferredCandidates) {

  /** Compact constructor validating required fields and defensively copying sets. */
  public RoutingRequest {
    Preconditions.requireNonNull(canonicalModelId, "canonicalModelId");
    Preconditions.requireNonNull(correlationId, "correlationId");
    Preconditions.requireNonNull(tenantScope, "tenantScope");
    Preconditions.requireNonNull(region, "region");
    requiredCapabilities =
        Set.copyOf(Preconditions.requireNonNull(requiredCapabilities, "requiredCapabilities"));
    requiredCompliance =
        Set.copyOf(Preconditions.requireNonNull(requiredCompliance, "requiredCompliance"));
    preferredCandidates =
        Set.copyOf(Preconditions.requireNonNull(preferredCandidates, "preferredCandidates"));
    Preconditions.requireNonNegative(minContextLength, "minContextLength");
    Preconditions.requireNonNegative(costCeilingMicros, "costCeilingMicros");
  }
}

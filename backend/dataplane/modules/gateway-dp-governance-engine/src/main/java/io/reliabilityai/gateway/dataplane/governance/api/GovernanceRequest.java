package io.reliabilityai.gateway.dataplane.governance.api;

import io.reliabilityai.gateway.canonical.context.PrincipalContext;
import io.reliabilityai.gateway.canonical.context.RequestContext;
import io.reliabilityai.gateway.canonical.context.TenantContext;
import io.reliabilityai.gateway.canonical.identity.CanonicalModelId;
import io.reliabilityai.gateway.canonical.identity.Region;
import io.reliabilityai.gateway.common.Preconditions;
import java.util.Set;

/**
 * The neutral admission question put to the engine (Doc 21 §6): who is asking, on whose behalf, for
 * what capability, in which region.
 *
 * <p>Provider-neutral by construction (GV-D11) — there is no field here in which a provider name
 * could be carried, so the engine cannot branch on one even by accident. Capabilities and tools are
 * canonical names, not provider features.
 *
 * @param requestContext correlation, idempotency and causation ids
 * @param principal the authenticated principal and its claims (from C6)
 * @param tenant the resolved tenant scope (from C6)
 * @param requestedModel the canonical model the caller asked for
 * @param requestedRegion the region the request would execute in
 * @param requestedCapabilities the canonical capabilities the request needs
 * @param requestedTools the canonical tools the request may invoke
 * @param requiredComplianceRegimes the compliance regimes this request must satisfy
 * @param requestedFeatures the flag-gated features this request depends on
 * @param projectedSpendMicros the spend this request would add, normalized upstream (never computed
 *     here)
 */
public record GovernanceRequest(
    RequestContext requestContext,
    PrincipalContext principal,
    TenantContext tenant,
    CanonicalModelId requestedModel,
    Region requestedRegion,
    Set<String> requestedCapabilities,
    Set<String> requestedTools,
    Set<String> requiredComplianceRegimes,
    Set<String> requestedFeatures,
    long projectedSpendMicros) {

  /** Validates the admission question. */
  public GovernanceRequest {
    Preconditions.requireNonNull(requestContext, "requestContext");
    Preconditions.requireNonNull(principal, "principal");
    Preconditions.requireNonNull(tenant, "tenant");
    Preconditions.requireNonNull(requestedModel, "requestedModel");
    Preconditions.requireNonNull(requestedRegion, "requestedRegion");
    requestedCapabilities =
        Set.copyOf(Preconditions.requireNonNull(requestedCapabilities, "requestedCapabilities"));
    requestedTools = Set.copyOf(Preconditions.requireNonNull(requestedTools, "requestedTools"));
    requiredComplianceRegimes =
        Set.copyOf(
            Preconditions.requireNonNull(requiredComplianceRegimes, "requiredComplianceRegimes"));
    requestedFeatures =
        Set.copyOf(Preconditions.requireNonNull(requestedFeatures, "requestedFeatures"));
    Preconditions.requireNonNegative(projectedSpendMicros, "projectedSpendMicros");
  }
}

package io.reliabilityai.gateway.dataplane.governance.api;

import io.reliabilityai.gateway.canonical.identity.CorrelationId;
import io.reliabilityai.gateway.canonical.identity.TenantScope;
import io.reliabilityai.gateway.common.ContentFree;
import io.reliabilityai.gateway.common.Preconditions;
import java.time.Instant;
import java.util.Map;

/**
 * The audit fact emitted for every governance decision — permits included (Doc 21 §33).
 *
 * <p>Auditing only denials would leave no evidence that the gate ran at all, which is exactly what
 * a compliance reviewer needs to see. {@link ContentFree} by construction: it records the outcome,
 * the binding domain and the snapshot versions decided against — never the prompt, the claims, or
 * which specific rule matched.
 *
 * @param decisionId the deterministic per-decision identifier
 * @param correlationId the request correlation id
 * @param tenantScope the tenant the decision was scoped to
 * @param outcome the outcome name (PERMIT, REQUIRE_APPROVAL, or the denial reason code)
 * @param binding the binding policy domain, or {@code "none"} on a permit
 * @param policyVersions the snapshot versions decided against — what makes the decision replayable
 * @param decidedAt the injected-clock instant the decision was made
 */
public record GovernanceDecisionRecord(
    String decisionId,
    CorrelationId correlationId,
    TenantScope tenantScope,
    String outcome,
    String binding,
    Map<String, String> policyVersions,
    Instant decidedAt)
    implements ContentFree {

  /** Validates the audit fact. */
  public GovernanceDecisionRecord {
    Preconditions.requireNonBlank(decisionId, "decisionId");
    Preconditions.requireNonNull(correlationId, "correlationId");
    Preconditions.requireNonNull(tenantScope, "tenantScope");
    Preconditions.requireNonBlank(outcome, "outcome");
    Preconditions.requireNonBlank(binding, "binding");
    policyVersions = policyVersions == null ? Map.of() : Map.copyOf(policyVersions);
    Preconditions.requireNonNull(decidedAt, "decidedAt");
  }
}

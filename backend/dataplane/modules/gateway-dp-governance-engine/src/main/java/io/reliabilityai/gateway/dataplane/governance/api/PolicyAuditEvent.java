package io.reliabilityai.gateway.dataplane.governance.api;

import io.reliabilityai.gateway.canonical.identity.CorrelationId;
import io.reliabilityai.gateway.canonical.identity.TenantScope;
import io.reliabilityai.gateway.common.ContentFree;
import io.reliabilityai.gateway.common.Preconditions;
import java.time.Instant;
import java.util.List;

/**
 * The immutable audit fact emitted for <b>every</b> governance decision (Doc 21 §33/§40).
 *
 * <p>Every decision, not every denial. An audit trail containing only refusals cannot answer the
 * question a compliance reviewer actually asks, which is not "what did you block" but "prove the
 * gate ran on everything". Permits are the evidence; denials are the exceptions.
 *
 * <p>{@link ContentFree}: ids, codes, versions and rule identifiers only. The matched rules travel
 * as their control-plane ids, never their contents, so a complete audit record can be written to a
 * long-lived WORM store without that store becoming a mirror of the tenant's policy (Doc 21 §25).
 *
 * @param decisionId the deterministic identifier of this decision
 * @param correlationId the request correlation id
 * @param tenantScope the org/tenant/workspace/project the decision was scoped to
 * @param apiKeyId the API key node, or empty when the request was not key-scoped
 * @param principalId the acting principal
 * @param verdict what was decided
 * @param reasonCode the neutral binding reason
 * @param matchedRuleIds the ids of every violated statement, in precedence order
 * @param policyVersion the generation evaluated against
 * @param latencyNanos the measured evaluation cost
 * @param simulated whether this decision was a simulation and therefore never enforced
 * @param decidedAt the injected-clock instant of the decision
 */
public record PolicyAuditEvent(
    String decisionId,
    CorrelationId correlationId,
    TenantScope tenantScope,
    String apiKeyId,
    String principalId,
    Verdict verdict,
    String reasonCode,
    List<String> matchedRuleIds,
    PolicyVersion policyVersion,
    long latencyNanos,
    boolean simulated,
    Instant decidedAt)
    implements ContentFree {

  /** The value recorded when a request carries no node at a given scope. */
  public static final String ABSENT = "-";

  /** Validates the audit fact. */
  public PolicyAuditEvent {
    Preconditions.requireNonBlank(decisionId, "decisionId");
    Preconditions.requireNonNull(correlationId, "correlationId");
    Preconditions.requireNonNull(tenantScope, "tenantScope");
    Preconditions.requireNonBlank(apiKeyId, "apiKeyId");
    Preconditions.requireNonBlank(principalId, "principalId");
    Preconditions.requireNonNull(verdict, "verdict");
    Preconditions.requireNonBlank(reasonCode, "reasonCode");
    matchedRuleIds = matchedRuleIds == null ? List.of() : List.copyOf(matchedRuleIds);
    Preconditions.requireNonNull(policyVersion, "policyVersion");
    Preconditions.requireNonNegative(latencyNanos, "latencyNanos");
    Preconditions.requireNonNull(decidedAt, "decidedAt");
  }
}

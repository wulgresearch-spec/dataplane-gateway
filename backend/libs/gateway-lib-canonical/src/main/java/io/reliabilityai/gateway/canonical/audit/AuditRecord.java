package io.reliabilityai.gateway.canonical.audit;

import io.reliabilityai.gateway.canonical.identity.CorrelationId;
import io.reliabilityai.gateway.canonical.identity.PrincipalId;
import io.reliabilityai.gateway.canonical.identity.TenantScope;
import io.reliabilityai.gateway.common.ContentFree;
import io.reliabilityai.gateway.common.Preconditions;
import java.time.Instant;

/**
 * A content-free, tamper-evident audit record (C10, Doc 33 §10.5, Doc 08 §10, Doc 13 §19). Carries
 * actor/action/decision/outcome/scope/correlation/timestamp — never prompt/completion content,
 * secrets, or PII (Doc 14 §7.1). Sealed WORM downstream by C10 (Doc 08 §10). Immutable.
 *
 * @param correlationId the correlation id
 * @param actor the scoped principal id (nullable for pre-auth/system actions)
 * @param action the audited action (content-free)
 * @param decision the decision (e.g. permit/deny/completed/isolated)
 * @param outcome the outcome (content-free)
 * @param tenantScope the tenant scope (nullable for pre-auth)
 * @param timestamp the record timestamp
 */
public record AuditRecord(
    CorrelationId correlationId,
    PrincipalId actor,
    String action,
    String decision,
    String outcome,
    TenantScope tenantScope,
    Instant timestamp)
    implements ContentFree {

  /** Compact constructor validating the content-free required fields. */
  public AuditRecord {
    Preconditions.requireNonNull(correlationId, "correlationId");
    Preconditions.requireNonBlank(action, "action");
    Preconditions.requireNonBlank(decision, "decision");
    Preconditions.requireNonBlank(outcome, "outcome");
    Preconditions.requireNonNull(timestamp, "timestamp");
  }
}

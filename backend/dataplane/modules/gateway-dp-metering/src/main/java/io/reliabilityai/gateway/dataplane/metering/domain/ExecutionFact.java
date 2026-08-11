package io.reliabilityai.gateway.dataplane.metering.domain;

import io.reliabilityai.gateway.canonical.decision.AttemptClass;
import io.reliabilityai.gateway.canonical.identity.AttemptId;
import io.reliabilityai.gateway.canonical.identity.CanonicalModelId;
import io.reliabilityai.gateway.canonical.identity.IdempotencyKey;
import io.reliabilityai.gateway.canonical.identity.Region;
import io.reliabilityai.gateway.canonical.identity.RequestId;
import io.reliabilityai.gateway.canonical.identity.TenantScope;
import io.reliabilityai.gateway.canonical.io.CanonicalUsage;
import io.reliabilityai.gateway.canonical.io.UsageClass;
import io.reliabilityai.gateway.common.Preconditions;
import java.util.Optional;

/**
 * A runtime execution fact consumed from an execution stage (Doc 23 §6) — an attempt outcome
 * (Reliability, Doc 20), a stream's final usage (StreamGuard, Doc 18 CV-5), or a guided retry
 * (SchemaLock, Doc 17). It is the <b>raw input</b> the engine normalizes into an immutable
 * canonical {@code UsageFact}; the engine records it and drives none of it.
 *
 * <p>The reported usage may be <b>absent</b> ({@code reportedUsage == null}) — the engine then
 * fails closed (never fabricates/estimates, Doc 23 §D5). Provider-neutral: keyed by canonical model
 * id, never a provider name (AD-007). {@code delivered} is Reliability's outcome, recorded not
 * decided (Doc 23 §28.1 AT-3). Immutable.
 *
 * @param requestId the request id (for completeness manifest, Doc 23 §17.1)
 * @param idempotencyKey the request idempotency key (dedup anchor, Doc 23 §14)
 * @param attemptId the stable per-attempt id (assigned by Reliability, Doc 23 §14.1)
 * @param tenantScope the tenant scope (isolation, AD-021)
 * @param canonicalModelId the canonical model id (Doc 19 §9.2)
 * @param region the residency region (Doc 23 §48)
 * @param attemptClass the attempt class (Doc 23 §18)
 * @param delivered whether Reliability marked this the delivered attempt (Doc 20 §25; recorded,
 *     AT-3)
 * @param reportedUsage the authoritative/estimated usage reported by execution (null ⇒ missing)
 * @param reportedUsageClass the class of the reported usage (null ⇒ missing)
 */
public record ExecutionFact(
    RequestId requestId,
    IdempotencyKey idempotencyKey,
    AttemptId attemptId,
    TenantScope tenantScope,
    CanonicalModelId canonicalModelId,
    Region region,
    AttemptClass attemptClass,
    boolean delivered,
    CanonicalUsage reportedUsage,
    UsageClass reportedUsageClass) {

  /** Compact constructor validating the identity/scope fields (usage may be absent). */
  public ExecutionFact {
    Preconditions.requireNonNull(requestId, "requestId");
    Preconditions.requireNonNull(idempotencyKey, "idempotencyKey");
    Preconditions.requireNonNull(attemptId, "attemptId");
    Preconditions.requireNonNull(tenantScope, "tenantScope");
    Preconditions.requireNonNull(canonicalModelId, "canonicalModelId");
    Preconditions.requireNonNull(region, "region");
    Preconditions.requireNonNull(attemptClass, "attemptClass");
    // reportedUsage / reportedUsageClass may be null (missing usage ⇒ fail closed downstream).
  }

  /**
   * The reported usage if present (Doc 23 §D5 — absent ⇒ fail closed).
   *
   * @return the optional reported usage
   */
  public Optional<CanonicalUsage> reportedUsageOpt() {
    return Optional.ofNullable(reportedUsage);
  }
}

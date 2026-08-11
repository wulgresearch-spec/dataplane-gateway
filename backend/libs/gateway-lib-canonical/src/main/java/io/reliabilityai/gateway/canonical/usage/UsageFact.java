package io.reliabilityai.gateway.canonical.usage;

import io.reliabilityai.gateway.canonical.decision.AttemptClass;
import io.reliabilityai.gateway.canonical.identity.AttemptId;
import io.reliabilityai.gateway.canonical.identity.CanonicalModelId;
import io.reliabilityai.gateway.canonical.identity.IdempotencyKey;
import io.reliabilityai.gateway.canonical.identity.Region;
import io.reliabilityai.gateway.canonical.identity.TenantScope;
import io.reliabilityai.gateway.canonical.io.CanonicalUsage;
import io.reliabilityai.gateway.canonical.io.UsageClass;
import io.reliabilityai.gateway.common.ContentFree;
import io.reliabilityai.gateway.common.Preconditions;
import java.time.Instant;
import java.util.Map;

/**
 * The canonical, immutable, append-only usage fact (Doc 33 §10.5, Doc 23 §6). Identity is {@code
 * (idempotencyKey, attemptId)}; provider usage = every attempt, customer usage = delivered (Doc 23
 * §26–28). Facts, never fabricated (Doc 23 §D5). Content-free — counts only, no prompt or
 * completion text (Doc 23 §8, Doc 14 §7.1). Immutable; source versions defensively copied.
 *
 * @param idempotencyKey the request idempotency key (dedup anchor)
 * @param attemptId the per-attempt id
 * @param tenantScope the tenant scope
 * @param canonicalModelId the canonical model id
 * @param region the residency region
 * @param usageClass authoritative or estimated
 * @param units the canonical usage units
 * @param attemptClass the attempt class
 * @param delivered whether this is the delivered attempt (Doc 20 §25)
 * @param timestamp the fact timestamp
 * @param sourceVersions the source/descriptor versions for reproducibility (Doc 23 §36)
 */
public record UsageFact(
    IdempotencyKey idempotencyKey,
    AttemptId attemptId,
    TenantScope tenantScope,
    CanonicalModelId canonicalModelId,
    Region region,
    UsageClass usageClass,
    CanonicalUsage units,
    AttemptClass attemptClass,
    boolean delivered,
    Instant timestamp,
    Map<String, String> sourceVersions)
    implements ContentFree {

  /** Compact constructor validating required fields and defensively copying source versions. */
  public UsageFact {
    Preconditions.requireNonNull(idempotencyKey, "idempotencyKey");
    Preconditions.requireNonNull(attemptId, "attemptId");
    Preconditions.requireNonNull(tenantScope, "tenantScope");
    Preconditions.requireNonNull(canonicalModelId, "canonicalModelId");
    Preconditions.requireNonNull(region, "region");
    Preconditions.requireNonNull(usageClass, "usageClass");
    Preconditions.requireNonNull(units, "units");
    Preconditions.requireNonNull(attemptClass, "attemptClass");
    Preconditions.requireNonNull(timestamp, "timestamp");
    // Inlined copy, not Preconditions.immutableMap — see that method's javadoc (EI_EXPOSE_REP).
    sourceVersions = sourceVersions == null ? Map.of() : Map.copyOf(sourceVersions);
  }
}

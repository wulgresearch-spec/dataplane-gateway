package io.reliabilityai.gateway.canonical.usage;

import io.reliabilityai.gateway.canonical.identity.ExecutionIdentity;
import io.reliabilityai.gateway.canonical.identity.TenantScope;
import io.reliabilityai.gateway.canonical.io.CanonicalUsage;
import io.reliabilityai.gateway.common.ContentFree;
import io.reliabilityai.gateway.common.Preconditions;
import java.time.Instant;

/**
 * The authoritative accounting fact — the C5 control-plane ledger system-of-record (Doc 33 §10.5,
 * Doc 06 §307). Distinct from {@link UsageFact} (what was used, Doc 23) and {@link CostFact} (its
 * price, Doc 22): this is the ledger SoR (RPO=0). Immutable; content-free.
 *
 * @param executionIdentity the execution identity
 * @param tenantScope the tenant scope
 * @param units the recorded usage units
 * @param sealed whether the fact is sealed in the ledger (WORM, Doc 08 §10)
 * @param timestamp the ledger timestamp
 */
public record AccountingFact(
    ExecutionIdentity executionIdentity,
    TenantScope tenantScope,
    CanonicalUsage units,
    boolean sealed,
    Instant timestamp)
    implements ContentFree {

  /** Compact constructor validating required fields. */
  public AccountingFact {
    Preconditions.requireNonNull(executionIdentity, "executionIdentity");
    Preconditions.requireNonNull(tenantScope, "tenantScope");
    Preconditions.requireNonNull(units, "units");
    Preconditions.requireNonNull(timestamp, "timestamp");
  }
}

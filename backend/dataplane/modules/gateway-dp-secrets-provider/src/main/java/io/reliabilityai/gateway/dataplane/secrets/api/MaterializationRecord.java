package io.reliabilityai.gateway.dataplane.secrets.api;

import io.reliabilityai.gateway.canonical.identity.SnapshotVersion;
import io.reliabilityai.gateway.canonical.identity.TenantScope;
import io.reliabilityai.gateway.common.ContentFree;
import io.reliabilityai.gateway.common.Preconditions;
import java.time.Instant;

/**
 * The content-free record of one materialization attempt (Doc 26 §6, SP-D12, RED-6). Carries only
 * the fact, scope, and version — <b>never the credential value</b> (Doc 26 §24, Doc 14 §7.1). Rides
 * the frozen audit mechanism (Doc 07/Doc 08 §10) via {@link SecretsAuditPort}; audit-sink failure
 * never blocks sanitization (Doc 26 SP-D12).
 *
 * @param leaseId the lease id, or {@code "none"} for a fail-closed attempt
 * @param tenantScope the tenant scope (scoped ids, not content/PII)
 * @param routeRef the opaque canonical route reference (never a provider name, AD-007)
 * @param outcome the content-free outcome label ({@code leased} or {@code unavailable:<reason>})
 * @param snapshotVersion the credential-snapshot version, or {@code null} when none was resolved
 * @param timestamp the deterministic-clock timestamp (Doc 26 §19)
 */
public record MaterializationRecord(
    String leaseId,
    TenantScope tenantScope,
    String routeRef,
    String outcome,
    SnapshotVersion snapshotVersion,
    Instant timestamp)
    implements ContentFree {

  /** Compact constructor validating required content-free fields. */
  public MaterializationRecord {
    Preconditions.requireNonBlank(leaseId, "leaseId");
    Preconditions.requireNonNull(tenantScope, "tenantScope");
    Preconditions.requireNonBlank(routeRef, "routeRef");
    Preconditions.requireNonBlank(outcome, "outcome");
    Preconditions.requireNonNull(timestamp, "timestamp");
    // snapshotVersion is nullable: a missing snapshot has no version.
  }
}

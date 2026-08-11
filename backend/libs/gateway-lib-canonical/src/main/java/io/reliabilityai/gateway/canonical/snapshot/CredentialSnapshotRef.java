package io.reliabilityai.gateway.canonical.snapshot;

import io.reliabilityai.gateway.canonical.identity.SnapshotVersion;
import io.reliabilityai.gateway.canonical.identity.TenantScope;
import io.reliabilityai.gateway.common.ContentFree;
import io.reliabilityai.gateway.common.Preconditions;
import java.time.Instant;

/**
 * A content-free <b>reference/handle</b> to a cached, short-TTL C14 credential snapshot (Doc 26 §6,
 * AD-022). The IR-6 credential-snapshot descriptor contract (Doc 38 §IR-6): frozen-in-code before
 * the Secrets Provider consumes it. It carries only <b>metadata</b> — version, tenant binding, and
 * the C14-authored expiry — and <b>never the credential value</b> (Doc 26 §6, RED-1). The material
 * itself lives behind the {@code SecretSnapshotPort} and is applied only within a bounded lease
 * scope (Doc 26 §17.1); it never appears in this record, in audit, or in telemetry (Doc 26 §20.1,
 * RED-6).
 *
 * @param version the pinned credential-snapshot version (recorded content-free in audit, Doc 26 §6)
 * @param tenantScope the tenant scope this credential is bound to (Doc 26 §D7, AD-021)
 * @param notAfter the C14-authored expiry after which material must not be used (Doc 26 §D3/§14)
 */
public record CredentialSnapshotRef(
    SnapshotVersion version, TenantScope tenantScope, Instant notAfter) implements ContentFree {

  /** Compact constructor validating required, content-free metadata. */
  public CredentialSnapshotRef {
    Preconditions.requireNonNull(version, "version");
    Preconditions.requireNonNull(tenantScope, "tenantScope");
    Preconditions.requireNonNull(notAfter, "notAfter");
  }
}

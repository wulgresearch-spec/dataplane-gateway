package io.reliabilityai.gateway.dataplane.authn.api;

import io.reliabilityai.gateway.canonical.identity.PrincipalId;
import io.reliabilityai.gateway.canonical.identity.SnapshotVersion;
import io.reliabilityai.gateway.canonical.identity.TenantScope;
import io.reliabilityai.gateway.common.ContentFree;
import io.reliabilityai.gateway.common.Preconditions;
import java.time.Instant;

/**
 * The content-free recorded authentication decision (Doc 37 §4/§15/§RTS) — the replay anchor.
 * Carries only the decision: id, outcome, scoped principal id, reason code, pinned key-snapshot
 * version, and resolved tenant scope. It NEVER carries token content, claims-as-content, or a
 * secret (Doc 37 IAU-A11, Doc 14 §7.1). Replay reproduces this recorded decision; it never
 * re-verifies (Doc 37 IAU-A6).
 *
 * @param decisionId the deterministic decision id (recorded for replay correlation, Doc 37 §RTS)
 * @param authenticated whether authentication succeeded
 * @param principalId the scoped principal id on success (nullable on failure)
 * @param reason the content-free reason code on failure (nullable on success)
 * @param keySnapshotVersion the pinned key-snapshot version (nullable when none was resident)
 * @param tenantScope the resolved tenant scope on success (nullable otherwise)
 * @param timestamp the deterministic-clock decision timestamp (Doc 37 §13)
 */
public record AuthenticationDecisionRecord(
    String decisionId,
    boolean authenticated,
    PrincipalId principalId,
    String reason,
    SnapshotVersion keySnapshotVersion,
    TenantScope tenantScope,
    Instant timestamp)
    implements ContentFree {

  /** Compact constructor validating the required, content-free anchor fields. */
  public AuthenticationDecisionRecord {
    Preconditions.requireNonBlank(decisionId, "decisionId");
    Preconditions.requireNonNull(timestamp, "timestamp");
  }
}

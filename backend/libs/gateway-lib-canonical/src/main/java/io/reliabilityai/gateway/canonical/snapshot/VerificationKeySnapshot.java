package io.reliabilityai.gateway.canonical.snapshot;

import io.reliabilityai.gateway.canonical.identity.Region;
import io.reliabilityai.gateway.canonical.identity.SnapshotVersion;
import io.reliabilityai.gateway.common.ContentFree;
import io.reliabilityai.gateway.common.Preconditions;
import java.util.List;

/**
 * A cached, versioned C6 verification-key snapshot (Doc 37 §8/§VKR, AD-022). The IR-6 snapshot
 * contract for identity verification (Doc 38 §IR-6): frozen-in-code before the AuthN node consumes
 * it. It carries the active <b>public</b> verification keys ({@link VerificationKey}) and the
 * cached revocation state — <b>never a secret</b> (verification keys are public; provider
 * credentials are C14/Doc 26, IAU-D9). Authored/distributed by the Identity &amp; Tenancy Service
 * (Doc 06 §9.5); consumed read-only, with no online IdP call on the hot path (Doc 37 VKR-1/VKR-3).
 * Region-confined (Doc 37 §MRI, AD-014). Content-free.
 *
 * @param version the pinned snapshot version, recorded per decision (Doc 37 VKR-6)
 * @param region the residency region (Doc 37 §MRI, AD-014)
 * @param keys the active public verification keys (defensively copied)
 * @param revokedKeyIds the cached revoked/rotated-out key ids; a token signed by one fails closed
 *     (Doc 37 VKR-4/VKR-5); defensively copied
 */
public record VerificationKeySnapshot(
    SnapshotVersion version, Region region, List<VerificationKey> keys, List<String> revokedKeyIds)
    implements ContentFree {

  /** Compact constructor validating identity and defensively copying key lists. */
  public VerificationKeySnapshot {
    Preconditions.requireNonNull(version, "version");
    Preconditions.requireNonNull(region, "region");
    // Inlined copies, not Preconditions.immutableList — see that method's javadoc (EI_EXPOSE_REP).
    keys = keys == null ? List.of() : List.copyOf(keys);
    revokedKeyIds = revokedKeyIds == null ? List.of() : List.copyOf(revokedKeyIds);
  }
}

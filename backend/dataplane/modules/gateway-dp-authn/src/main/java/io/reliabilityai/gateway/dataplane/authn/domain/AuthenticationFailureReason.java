package io.reliabilityai.gateway.dataplane.authn.domain;

/**
 * The typed, content-free authentication failure taxonomy (Doc 37 §14). Every failure or
 * uncertainty maps to one of these fail-closed reasons — no raw provider/IdP exception ever escapes
 * (Doc 37 IAU-A5). These are <b>authentication</b> outcomes only ("who"), never authorization
 * ("what") — the AuthN node never evaluates permissions (Doc 37 §ANZ).
 *
 * <p>Lives in the domain layer: it is produced by the pure domain verification/resolution logic and
 * exposed outward through the api ports (api → domain, the correct inward dependency direction,
 * R-017).
 */
public enum AuthenticationFailureReason {
  /** No verification-key snapshot resident; cannot verify (Doc 37 §14, VKR-4). */
  KEY_SNAPSHOT_UNAVAILABLE("key-snapshot-unavailable"),
  /** The pinned key snapshot is for another region (Doc 37 §MRI, AD-014, IAU-A13). */
  KEY_REGION_MISMATCH("key-region-mismatch"),
  /** The transport identity maps to no known principal (Doc 37 §14). */
  UNKNOWN_PRINCIPAL("unknown-principal"),
  /** The credential signature did not verify against the pinned keys (Doc 37 §9). */
  INVALID_SIGNATURE("invalid-signature"),
  /** The credential is expired (Doc 37 §14). */
  EXPIRED("expired"),
  /** The credential is not yet valid (Doc 37 §14). */
  NOT_YET_VALID("not-yet-valid"),
  /** The issuer was rejected (Doc 37 §14). */
  ISSUER_REJECTED("issuer-rejected"),
  /** The audience was rejected (Doc 37 §14). */
  AUDIENCE_REJECTED("audience-rejected"),
  /** The signing key is revoked/rotated-out per the cached revocation state (Doc 37 VKR-5). */
  KEY_REVOKED("key-revoked"),
  /** The token's {@code kid} is not present in the pinned key snapshot (Doc 37 VKR-4). */
  UNKNOWN_KEY("unknown-key"),
  /**
   * The token's algorithm does not match the algorithm bound to its key — algorithm confusion (§9).
   */
  ALGORITHM_MISMATCH("algorithm-mismatch"),
  /**
   * The token is malformed / not a valid JWS on the allow-list (Doc 37 §14, no {@code alg=none}).
   */
  MALFORMED_TOKEN("malformed-token"),
  /** Multiple/ambiguous credentials; the node never guesses (Doc 37 §TIM TIM-2). */
  AMBIGUOUS_IDENTITY("ambiguous-identity"),
  /** No tenant-scope snapshot resident to resolve scope (Doc 37 §TRF TRF-4). */
  TENANT_SNAPSHOT_UNAVAILABLE("tenant-snapshot-unavailable"),
  /** Authenticated principal but no resolvable tenant scope — distinct terminal (Doc 37 TRF-3). */
  TENANT_UNRESOLVED("tenant-unresolved"),
  /** Any unknown/internal error — fail closed (Doc 37 IAU-A5/AD-012). */
  INTERNAL("internal-error");

  private final String code;

  AuthenticationFailureReason(final String code) {
    this.code = code;
  }

  /**
   * Returns the stable, content-free reason code (safe for the {@code Unauthenticated} reason and
   * telemetry labels).
   *
   * @return the reason code
   */
  public String code() {
    return code;
  }
}

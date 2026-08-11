package io.reliabilityai.gateway.dataplane.secrets.api;

import io.reliabilityai.gateway.canonical.decision.RouteTarget;
import io.reliabilityai.gateway.canonical.identity.TenantScope;
import java.util.Optional;

/**
 * The outbound seam to the cached, short-TTL C14 credential snapshot (Doc 26 §7, AD-022).
 * Resolution is keyed by {@code (tenant scope, canonical route)} (Doc 26 §3.2) and is
 * <b>read-only</b>: the Secrets Provider makes <b>no synchronous C14 control-plane call on the hot
 * path</b> (Doc 26 SP-A2) — it consumes only the cached snapshot, which C14 refreshes out-of-band
 * ahead of expiry (AD-022). A cache miss / unavailable / stale snapshot yields empty, and the
 * caller fails closed (Doc 26 §21).
 */
public interface SecretSnapshotPort {

  /**
   * Resolves the credential material source for a tenant/route from the cached snapshot (Doc 26
   * §7).
   *
   * @param tenantScope the tenant scope
   * @param routeTarget the canonical route target (provider identity stays adapter-internal,
   *     AD-007)
   * @return the material source, or empty when no valid cached credential is resident (fail closed)
   */
  Optional<CredentialMaterialSource> resolve(TenantScope tenantScope, RouteTarget routeTarget);
}

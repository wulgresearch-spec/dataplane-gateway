package io.reliabilityai.gateway.dataplane.governance.api;

import io.reliabilityai.gateway.canonical.identity.TenantScope;
import io.reliabilityai.gateway.dataplane.governance.domain.UsageState;
import java.util.Optional;

/**
 * Reads bounded-staleness quota and spend counters owned by C5 (Doc 21 §7, §17/§28, GV-D5/GV-D6).
 *
 * <p>The engine owns no usage ledger — it consumes one. Because these counters are bounded-stale,
 * quota is enforced to a tolerance rather than exactly to the request; the returned {@code asOf}
 * timestamp is what lets the engine decide whether the reading is still fresh enough to enforce
 * against, and deny when it is not.
 */
public interface UsageStatePort {

  /**
   * Resolves the current usage counters for a tenant.
   *
   * @param tenantScope the tenant to read counters for
   * @return the tenant's usage state, or empty when unavailable
   */
  Optional<UsageState> usageFor(TenantScope tenantScope);
}

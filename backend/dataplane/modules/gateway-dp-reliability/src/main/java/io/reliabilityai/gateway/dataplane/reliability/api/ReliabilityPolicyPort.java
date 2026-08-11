package io.reliabilityai.gateway.dataplane.reliability.api;

import java.util.Optional;

/**
 * Outbound seam to the resolved reliability policy (Doc 20 §7/§16, C4/Config, AD-022). Read-only,
 * cached; an absent policy fails closed (Doc 20 §16 — fail-safe conservative).
 */
public interface ReliabilityPolicyPort {

  /**
   * Returns the current resolved reliability policy (AD-022).
   *
   * @return the policy, or empty when none is resident (caller fails closed)
   */
  Optional<ReliabilityPolicy> current();
}

package io.reliabilityai.gateway.dataplane.governance.domain;

import io.reliabilityai.gateway.canonical.identity.SnapshotVersion;
import io.reliabilityai.gateway.common.Preconditions;

/**
 * The tenant's contractual ceilings, consumed from the C8/C5 entitlement snapshot (Doc 21 §6,
 * GV-D6).
 *
 * <p>The engine reads these; it never computes them. Budget is expressed in normalized micros
 * because pricing and currency conversion belong to the Cost Engine — governance compares two
 * already-normalized numbers and nothing more.
 *
 * @param version the entitlement snapshot version
 * @param quotaLimit the maximum requests admitted per quota window
 * @param budgetLimitMicros the maximum spend admitted per budget period, normalized
 */
public record Entitlement(SnapshotVersion version, long quotaLimit, long budgetLimitMicros) {

  /** Validates the entitlement. */
  public Entitlement {
    Preconditions.requireNonNull(version, "version");
    Preconditions.requireNonNegative(quotaLimit, "quotaLimit");
    Preconditions.requireNonNegative(budgetLimitMicros, "budgetLimitMicros");
  }
}

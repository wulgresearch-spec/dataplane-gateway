package io.reliabilityai.gateway.dataplane.governance.domain;

import io.reliabilityai.gateway.common.Preconditions;
import java.time.Instant;

/**
 * A bounded-staleness reading of the tenant's current usage, owned by C5 (Doc 21 §6, §28).
 *
 * <p>{@code asOf} is the honest part of this type. These counters are replicated, not
 * transactional, so a reading is always slightly behind. Carrying the reading's age lets the engine
 * enforce quota to a declared tolerance and deny once the reading is too old to be meaningful —
 * rather than pretending the count is exact, or silently letting spend run unbounded when the
 * counter feed stalls.
 *
 * @param requestsUsed requests consumed in the current quota window
 * @param spentMicros spend consumed in the current budget period, normalized
 * @param asOf the instant this reading was taken
 */
public record UsageState(long requestsUsed, long spentMicros, Instant asOf) {

  /** Validates the usage reading. */
  public UsageState {
    Preconditions.requireNonNegative(requestsUsed, "requestsUsed");
    Preconditions.requireNonNegative(spentMicros, "spentMicros");
    Preconditions.requireNonNull(asOf, "asOf");
  }
}

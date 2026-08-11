package io.reliabilityai.gateway.ports;

import io.reliabilityai.gateway.common.Preconditions;
import java.time.Duration;

/**
 * The per-attempt transport budget handed to the Provider Adapter by Reliability (Doc 25 §7/§17.1).
 * Reliability owns the execution deadline (Doc 20); the adapter enforces only this handed budget
 * and defines no policy of its own. Immutable.
 *
 * @param transportTimeout the per-attempt transport timeout
 */
public record AttemptBudget(Duration transportTimeout) {

  /** Compact constructor validating the timeout is positive. */
  public AttemptBudget {
    Preconditions.requireNonNull(transportTimeout, "transportTimeout");
    if (transportTimeout.isNegative() || transportTimeout.isZero()) {
      throw new IllegalArgumentException("transportTimeout must be positive");
    }
  }
}

package io.reliabilityai.gateway.dataplane.plugin.api;

import io.reliabilityai.gateway.common.Preconditions;
import java.time.Duration;

/**
 * The accounted per-invocation resource quota (Doc 28 §REC).
 *
 * <p>Every field is required and every field is positive: Doc 28 REC-5 says an unenforceable quota
 * means the plugin does not run, so there is no "unlimited" sentinel to configure. All four values
 * come from the vetted manifest and the operational baseline, never from a constant in this class
 * (Doc 28 REC-7, PRT-A17).
 *
 * @param wallClock the deadline for one invocation
 * @param cpuMillis the CPU time the invocation may consume
 * @param memoryBytes the memory the invocation may use
 * @param ioBytes the bytes the invocation may read or write through the mediated host API
 */
public record ResourceBudget(Duration wallClock, long cpuMillis, long memoryBytes, long ioBytes) {

  /** Compact constructor rejecting any unbounded or non-positive quota. */
  public ResourceBudget {
    Preconditions.requireNonNull(wallClock, "wallClock");
    if (wallClock.isZero() || wallClock.isNegative()) {
      throw new IllegalArgumentException("wallClock must be positive");
    }
    if (cpuMillis <= 0) {
      throw new IllegalArgumentException("cpuMillis must be positive");
    }
    if (memoryBytes <= 0) {
      throw new IllegalArgumentException("memoryBytes must be positive");
    }
    if (ioBytes <= 0) {
      throw new IllegalArgumentException("ioBytes must be positive");
    }
  }

  /**
   * Narrows this budget so it can never outlast the caller's remaining time.
   *
   * <p>Doc 28 REC-3 splits deadline ownership: the runtime owns the plugin's deadline, the
   * Reliability Engine owns the request's, and the plugin's can never exceed it. This is where that
   * is enforced — a manifest asking for 30 seconds inside a request with 2 seconds left gets 2
   * seconds.
   *
   * @param remaining the time left in the enclosing request
   * @return this budget, or a copy clamped to the remaining time
   */
  public ResourceBudget clampedTo(final Duration remaining) {
    Preconditions.requireNonNull(remaining, "remaining");
    if (remaining.isZero() || remaining.isNegative()) {
      throw new IllegalArgumentException("remaining must be positive");
    }
    if (remaining.compareTo(wallClock) >= 0) {
      return this;
    }
    return new ResourceBudget(remaining, cpuMillis, memoryBytes, ioBytes);
  }
}

package io.reliabilityai.gateway.dataplane.schemalock.api;

import io.reliabilityai.gateway.common.Preconditions;
import java.time.Duration;

/**
 * Injected structured-output budgets/limits (Doc 17 §5/§24.1/§31), sourced from the operational
 * baseline (Doc 16 §I.1, §31.1) — SchemaLock enforces, never authors. Every bound fails closed on
 * breach ({@code RESOURCE_EXCEEDED}, SL-A7). The attempt cap hard-bounds the prompt-constrained
 * retry envelope (Doc 17 §24.1). Immutable.
 *
 * @param maxAttempts the hard cap on total generation attempts for one request (Doc 17 §24.1)
 * @param maxOutputBytes the hard cap on a single output's byte length (Doc 17 §31)
 * @param maxViolations the hard cap on collected violations (Doc 17 §22)
 * @param perAttemptTimeout the per-attempt timeout budget (Doc 17 §24.1)
 */
public record SchemaLockPolicy(
    int maxAttempts, int maxOutputBytes, int maxViolations, Duration perAttemptTimeout) {

  /** Compact constructor validating the bounds. */
  public SchemaLockPolicy {
    if (maxAttempts < 1) {
      throw new IllegalArgumentException("maxAttempts must be >= 1");
    }
    if (maxOutputBytes < 1) {
      throw new IllegalArgumentException("maxOutputBytes must be >= 1");
    }
    if (maxViolations < 1) {
      throw new IllegalArgumentException("maxViolations must be >= 1");
    }
    Preconditions.requireNonNull(perAttemptTimeout, "perAttemptTimeout");
    if (perAttemptTimeout.isNegative() || perAttemptTimeout.isZero()) {
      throw new IllegalArgumentException("perAttemptTimeout must be positive");
    }
  }
}

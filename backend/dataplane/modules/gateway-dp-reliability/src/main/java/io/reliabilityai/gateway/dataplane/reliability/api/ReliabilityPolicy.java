package io.reliabilityai.gateway.dataplane.reliability.api;

import io.reliabilityai.gateway.common.Preconditions;
import java.time.Duration;

/**
 * The resolved reliability policy (Doc 20 §6/§14/§15), authored by C4/Config and consumed read-only
 * (AD-019/022). Bounds retries and backoff — the Engine enforces, never authors. Immutable.
 *
 * @param maxAttempts the per-request attempt ceiling across all candidates (&gt;= 1, Doc 20 §14)
 * @param maxRetriesPerCandidate retries of the same candidate before failover (&gt;= 0)
 * @param backoffBaseMillis the exponential-backoff base in millis (Doc 20 §15)
 * @param backoffCapMillis the exponential-backoff cap in millis (&gt;= base)
 * @param perAttemptTimeout the per-attempt transport timeout handed to the adapter
 */
public record ReliabilityPolicy(
    int maxAttempts,
    int maxRetriesPerCandidate,
    long backoffBaseMillis,
    long backoffCapMillis,
    Duration perAttemptTimeout) {

  /** Compact constructor validating the bounds. */
  public ReliabilityPolicy {
    if (maxAttempts < 1) {
      throw new IllegalArgumentException("maxAttempts must be >= 1");
    }
    if (maxRetriesPerCandidate < 0) {
      throw new IllegalArgumentException("maxRetriesPerCandidate must be >= 0");
    }
    Preconditions.requireNonNegative(backoffBaseMillis, "backoffBaseMillis");
    if (backoffCapMillis < backoffBaseMillis) {
      throw new IllegalArgumentException("backoffCapMillis must be >= backoffBaseMillis");
    }
    Preconditions.requireNonNull(perAttemptTimeout, "perAttemptTimeout");
    if (perAttemptTimeout.isNegative() || perAttemptTimeout.isZero()) {
      throw new IllegalArgumentException("perAttemptTimeout must be positive");
    }
  }
}

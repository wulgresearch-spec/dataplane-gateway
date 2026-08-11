package io.reliabilityai.gateway.dataplane.streamguard.api;

import io.reliabilityai.gateway.common.Preconditions;
import java.time.Duration;

/**
 * Injected transport budgets/limits (Doc 18 §6/§32), sourced from the operational baseline (Doc 16
 * §I.1) — StreamGuard enforces, never authors. Every buffer/window is hard-bounded and every breach
 * fails closed (Doc 18 §31/§32, SG-A7). Both an inactivity and a total-duration timeout are
 * mandatory (Doc 18 §24, SG-D9 slow-loris defense). Immutable.
 *
 * @param maxSessionBytes the hard cap on total bytes ingested for one stream (Doc 18 §32)
 * @param maxFramingBufferBytes the hard cap on a framing decoder's buffered incomplete unit (Doc 18
 *     §32)
 * @param dedupWindow the bounded duplicate-suppression window size (Doc 18 §17/§32)
 * @param inactivityTimeout the max gap between bytes/heartbeats (Doc 18 §24)
 * @param totalTimeout the max wall-clock for the whole stream (Doc 18 §24)
 */
public record StreamGuardPolicy(
    long maxSessionBytes,
    int maxFramingBufferBytes,
    int dedupWindow,
    Duration inactivityTimeout,
    Duration totalTimeout) {

  /** Compact constructor validating all bounds are positive (Doc 18 §32). */
  public StreamGuardPolicy {
    if (maxSessionBytes < 1) {
      throw new IllegalArgumentException("maxSessionBytes must be >= 1");
    }
    if (maxFramingBufferBytes < 1) {
      throw new IllegalArgumentException("maxFramingBufferBytes must be >= 1");
    }
    if (dedupWindow < 1) {
      throw new IllegalArgumentException("dedupWindow must be >= 1");
    }
    Preconditions.requireNonNull(inactivityTimeout, "inactivityTimeout");
    Preconditions.requireNonNull(totalTimeout, "totalTimeout");
    if (inactivityTimeout.isNegative() || inactivityTimeout.isZero()) {
      throw new IllegalArgumentException("inactivityTimeout must be positive");
    }
    if (totalTimeout.isNegative() || totalTimeout.isZero()) {
      throw new IllegalArgumentException("totalTimeout must be positive");
    }
  }
}

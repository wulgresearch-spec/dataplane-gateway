package io.reliabilityai.gateway.dataplane.reliability.api;

import java.time.Duration;

/**
 * The backoff-delay seam (Doc 20 §15). Applies the deterministically-computed backoff between
 * retries. The production adapter parks the (virtual) thread; on JDK 21 virtual threads this
 * unmounts the carrier (no pinning). Kept as a port so the decision core stays deterministic and
 * the delay is controllable in tests.
 */
public interface Sleeper {

  /**
   * Waits for the given backoff duration (interruptible).
   *
   * @param duration the backoff duration (never negative)
   * @throws InterruptedException if interrupted while waiting (cancellation propagation, Doc 20
   *     §12)
   */
  void sleep(Duration duration) throws InterruptedException;
}

package io.reliabilityai.gateway.dataplane.app.binding;

import io.reliabilityai.gateway.dataplane.reliability.api.Sleeper;
import java.time.Duration;

/**
 * The real backoff sleeper for the VPS: parks the calling thread and stays interruptible so
 * shutdown can cancel a request that is mid-backoff rather than waiting the full cap (Doc 20).
 */
public final class InterruptibleSleeper implements Sleeper {

  @Override
  public void sleep(final Duration duration) throws InterruptedException {
    if (duration == null || duration.isZero() || duration.isNegative()) {
      return;
    }
    Thread.sleep(duration.toMillis());
  }
}

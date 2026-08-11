package io.reliabilityai.gateway.dataplane.app.binding;

import io.reliabilityai.gateway.common.Preconditions;
import io.reliabilityai.gateway.dataplane.reliability.api.RetryBudgetPort;
import io.reliabilityai.gateway.ports.ClockPort;
import java.time.Duration;
import java.time.Instant;

/**
 * A node-local retry budget: at most {@code permitsPerWindow} retries may be consumed in any single
 * fixed window (Doc 20 §retry-budget). This is the node's protection against a retry storm
 * amplifying a provider brownout into an outage — when the budget is exhausted the engine surfaces
 * the error instead of retrying.
 *
 * <p>Fail-closed and thread-safe: the whole check-and-consume is done under the instance monitor,
 * so concurrent attempts can never over-draw the window. Window boundaries come from the injected
 * {@link ClockPort}, never {@code System.currentTimeMillis}, so the behaviour is deterministic in
 * tests.
 */
public final class FixedWindowRetryBudget implements RetryBudgetPort {

  private final int permitsPerWindow;
  private final Duration window;
  private final ClockPort clock;

  private Instant windowStart;
  private int consumed;

  /**
   * Creates the budget.
   *
   * @param permitsPerWindow the maximum retries allowed per window (must be non-negative)
   * @param window the window length (must be positive)
   * @param clock the injected clock defining window boundaries
   */
  public FixedWindowRetryBudget(
      final int permitsPerWindow, final Duration window, final ClockPort clock) {
    if (permitsPerWindow < 0) {
      throw new IllegalArgumentException("permitsPerWindow must be >= 0");
    }
    Preconditions.requireNonNull(window, "window");
    if (window.isZero() || window.isNegative()) {
      throw new IllegalArgumentException("window must be positive");
    }
    this.permitsPerWindow = permitsPerWindow;
    this.window = window;
    this.clock = Preconditions.requireNonNull(clock, "clock");
    this.windowStart = clock.now();
  }

  @Override
  public synchronized boolean tryConsumeRetry() {
    final Instant now = clock.now();
    if (!now.isBefore(windowStart.plus(window))) {
      windowStart = now;
      consumed = 0;
    }
    if (consumed >= permitsPerWindow) {
      return false;
    }
    consumed++;
    return true;
  }

  /**
   * The retries consumed in the current window (operational visibility; not a decision input).
   *
   * @return the consumed count
   */
  public synchronized int consumedInWindow() {
    return consumed;
  }
}

package io.reliabilityai.gateway.dataplane.secrets.adapter;

import io.reliabilityai.gateway.common.Preconditions;
import io.reliabilityai.gateway.ports.ClockPort;
import java.time.Clock;
import java.time.Instant;

/**
 * The production {@link ClockPort} adapter (Doc 26 §19). Wraps an injected {@link java.time.Clock}
 * — created once at the composition root (the {@code Clock.systemUTC()} factory call, the single
 * forbidden-apis-exempt clock construction, lives in the app wiring, not here). This adapter only
 * reads {@link Clock#instant()}, which is not a forbidden ambient clock source (Doc 11 R-063).
 */
public final class SystemClock implements ClockPort {

  private final Clock clock;

  /**
   * Creates the adapter over an injected clock.
   *
   * @param clock the injected clock (UTC at the composition root)
   */
  public SystemClock(final Clock clock) {
    this.clock = Preconditions.requireNonNull(clock, "clock");
  }

  @Override
  public Instant now() {
    return clock.instant();
  }
}

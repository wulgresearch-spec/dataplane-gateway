package io.reliabilityai.gateway.ports;

import java.time.Instant;

/**
 * The deterministic time seam (AD-002). Wall-clock access is forbidden inside decision cores (Doc
 * 11 R-063, forbidden-apis bans {@code Instant.now()}/{@code Clock.systemUTC()}); time is read only
 * through this port so it can be injected and controlled. Used for credential TTL/expiry
 * enforcement (Doc 26 §19), telemetry timestamps (Doc 27 §20), and clock-skew handling (Doc 37
 * §13).
 *
 * <p>Time reads are explicitly <b>excluded from deterministic replay</b> (Doc 32 §CRS CRS-6):
 * replay reproduces decisions and ordering, never wall-clock values. Concrete adapters wrap an
 * injected {@link java.time.Clock} created once at the composition root.
 */
public interface ClockPort {

  /**
   * Returns the current instant from the injected clock.
   *
   * @return the current instant (never reproduced by replay, Doc 32 §CRS)
   */
  Instant now();
}

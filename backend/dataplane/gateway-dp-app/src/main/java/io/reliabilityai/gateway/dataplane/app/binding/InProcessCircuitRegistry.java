package io.reliabilityai.gateway.dataplane.app.binding;

import io.reliabilityai.gateway.common.Preconditions;
import io.reliabilityai.gateway.dataplane.reliability.api.CircuitPort;
import io.reliabilityai.gateway.ports.ClockPort;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Node-local circuit state per provider route (Doc 20). A route is tripped for a bounded cool-off
 * and closes again automatically once the cool-off elapses — there is no background timer and no
 * shared state, so a VPS restart simply starts every circuit closed.
 *
 * <p>Deliberately explicit: nothing trips a circuit implicitly. The owner of the failure signal
 * calls {@link #trip(String)}, which keeps the reliability engine the only component that
 * interprets outcomes. Reads are lock-free and safe from the request path.
 */
public final class InProcessCircuitRegistry implements CircuitPort {

  private final Map<String, Instant> openUntil = new ConcurrentHashMap<>();
  private final Duration coolOff;
  private final ClockPort clock;

  /**
   * Creates the registry.
   *
   * @param coolOff how long a tripped route stays open (must be positive)
   * @param clock the injected clock
   */
  public InProcessCircuitRegistry(final Duration coolOff, final ClockPort clock) {
    Preconditions.requireNonNull(coolOff, "coolOff");
    if (coolOff.isZero() || coolOff.isNegative()) {
      throw new IllegalArgumentException("coolOff must be positive");
    }
    this.coolOff = coolOff;
    this.clock = Preconditions.requireNonNull(clock, "clock");
  }

  @Override
  public boolean isOpen(final String providerRouteRef) {
    if (providerRouteRef == null) {
      return false;
    }
    final Instant until = openUntil.get(providerRouteRef);
    if (until == null) {
      return false;
    }
    if (clock.now().isBefore(until)) {
      return true;
    }
    openUntil.remove(providerRouteRef, until); // cool-off elapsed: close, without racing a re-trip
    return false;
  }

  /**
   * Trips the circuit for a route, opening it for the configured cool-off.
   *
   * @param providerRouteRef the provider route to open
   */
  public void trip(final String providerRouteRef) {
    Preconditions.requireNonBlank(providerRouteRef, "providerRouteRef");
    openUntil.put(providerRouteRef, clock.now().plus(coolOff));
  }

  /**
   * Closes the circuit for a route immediately (operator override / recovery signal).
   *
   * @param providerRouteRef the provider route to close
   */
  public void reset(final String providerRouteRef) {
    Preconditions.requireNonBlank(providerRouteRef, "providerRouteRef");
    openUntil.remove(providerRouteRef);
  }
}

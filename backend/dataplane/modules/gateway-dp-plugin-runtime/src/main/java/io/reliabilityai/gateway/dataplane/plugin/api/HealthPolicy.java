package io.reliabilityai.gateway.dataplane.plugin.api;

import io.reliabilityai.gateway.common.Preconditions;
import java.time.Duration;

/**
 * How a plugin's health is probed (Doc 28 §J).
 *
 * <p>The probe runs out of band, never on the request path: a health check that ran per request
 * would put a plugin's latency in front of every caller, which is exactly the coupling Doc 28 §1
 * budgets against.
 *
 * @param probeInterval how often the operator or scheduler should probe
 * @param probeTimeout the deadline for one probe; a probe that overruns yields FAILED, never a hang
 * @param failureThreshold consecutive failed probes before health is reported FAILED
 */
public record HealthPolicy(Duration probeInterval, Duration probeTimeout, int failureThreshold) {

  /** Compact constructor validating the probe shape. */
  public HealthPolicy {
    Preconditions.requireNonNull(probeInterval, "probeInterval");
    Preconditions.requireNonNull(probeTimeout, "probeTimeout");
    if (probeInterval.isZero() || probeInterval.isNegative()) {
      throw new IllegalArgumentException("probeInterval must be positive");
    }
    if (probeTimeout.isZero() || probeTimeout.isNegative()) {
      throw new IllegalArgumentException("probeTimeout must be positive");
    }
    if (probeTimeout.compareTo(probeInterval) > 0) {
      // Otherwise probes overlap forever and the plugin is never observed to recover.
      throw new IllegalArgumentException("probeTimeout must not exceed probeInterval");
    }
    if (failureThreshold < 1) {
      throw new IllegalArgumentException("failureThreshold must be at least 1");
    }
  }
}

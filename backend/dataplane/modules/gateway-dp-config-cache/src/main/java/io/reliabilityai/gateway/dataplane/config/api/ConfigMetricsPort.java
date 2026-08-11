package io.reliabilityai.gateway.dataplane.config.api;

import io.reliabilityai.gateway.canonical.identity.SnapshotVersion;

/**
 * Outbound seam for content-free config telemetry counters (Doc 36 §18). All signals are
 * content-free and low-cardinality (Doc 14 §7.1, Doc 27 §15.1); the consumer authors no metric
 * names (those are Doc 14/Doc 27-owned, Doc 36 CFG-L1). Emission is side-effect-free toward runtime
 * state (Doc 27 OT-A1) — a metrics failure never affects a pin.
 */
public interface ConfigMetricsPort {

  /**
   * Signals that a config version was pinned for a request (Doc 36 §18).
   *
   * @param version the pinned version
   */
  void snapshotPinned(SnapshotVersion version);

  /** Signals that a request is being served on the last-known-good snapshot (Doc 36 CFG-A6). */
  void lastKnownGoodActive();

  /** Signals a required config entry was missing (fail-closed path, Doc 36 §SDS). */
  void requiredMissing();

  /**
   * Signals that config drift was detected and last-known-good remains in service (Doc 36 §DRC).
   */
  void driftActive();
}

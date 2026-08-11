package io.reliabilityai.gateway.dataplane.config.adapter;

import io.reliabilityai.gateway.canonical.identity.SnapshotVersion;
import io.reliabilityai.gateway.canonical.snapshot.ConfigSnapshot;
import io.reliabilityai.gateway.common.Preconditions;
import io.reliabilityai.gateway.dataplane.config.api.ConfigMetricsPort;
import io.reliabilityai.gateway.ports.SnapshotSourcePort;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

/**
 * The data-plane last-known-good config cache (Doc 36 §6, AD-017/AD-022). Holds the most recent
 * C15-validated snapshot and serves it read-only; a new published snapshot swaps in <b>atomically
 * for new requests only</b> and never mutates in place (Doc 36 CFG-D10). When C15 is unavailable
 * the cache keeps serving the last-known-good — a control-plane outage never causes a request
 * outage (Doc 36 CFG-A6). On detected drift it continues on last-known-good and raises an alarm,
 * never silently accepting drifted config (Doc 36 §DRC DRC-2).
 *
 * <p>Thread-safe and virtual-thread-safe (AD-023): the held snapshot is an {@link AtomicReference}
 * swapped as a whole; readers never observe a partially-updated snapshot.
 */
public final class LastKnownGoodConfigCache implements SnapshotSourcePort<ConfigSnapshot> {

  private final AtomicReference<ConfigSnapshot> lastKnownGood = new AtomicReference<>();
  private final ConfigMetricsPort metrics;

  /**
   * Creates the cache with no snapshot yet (cold start); {@link #current()} is empty until the
   * first validated snapshot is applied, and a pin against an empty cache fails closed (Doc 36
   * §SDS).
   *
   * @param metrics the content-free metrics seam
   */
  public LastKnownGoodConfigCache(final ConfigMetricsPort metrics) {
    this.metrics = Preconditions.requireNonNull(metrics, "metrics");
  }

  @Override
  public Optional<ConfigSnapshot> current() {
    return Optional.ofNullable(lastKnownGood.get());
  }

  @Override
  public Optional<ConfigSnapshot> pinned(final SnapshotVersion version) {
    Preconditions.requireNonNull(version, "version");
    final ConfigSnapshot held = lastKnownGood.get();
    if (held != null && held.version().equals(version)) {
      return Optional.of(held);
    }
    return Optional.empty();
  }

  /**
   * Applies a newly published, C15-validated snapshot, swapping it in atomically for new requests
   * (Doc 36 §6/§13, CFG-D10). In-flight requests keep their already-pinned version (Doc 36 §RIF).
   *
   * @param snapshot the validated snapshot to swap in
   */
  public void applyPublished(final ConfigSnapshot snapshot) {
    Preconditions.requireNonNull(snapshot, "snapshot");
    lastKnownGood.set(snapshot);
  }

  /**
   * Rolls back to a prior validated snapshot version by re-pinning it — never an in-place mutation
   * (Doc 36 CFG-A10/§RIF, AD-013). In-flight requests keep their pinned version.
   *
   * @param priorValidated the prior validated snapshot to serve to new requests
   */
  public void rollbackTo(final ConfigSnapshot priorValidated) {
    Preconditions.requireNonNull(priorValidated, "priorValidated");
    lastKnownGood.set(priorValidated);
  }

  /**
   * Signals that a control-plane refresh failed; the cache keeps serving the last-known-good and
   * the outage is surfaced content-free (Doc 36 CFG-A6, AD-017).
   */
  public void refreshFailed() {
    if (lastKnownGood.get() != null) {
      safeMetric(metrics::lastKnownGoodActive);
    } else {
      // No last-known-good to fall back to; pins will fail closed (Doc 36 §SDS SDS-3).
      safeMetric(metrics::requiredMissing);
    }
  }

  /**
   * Marks that config drift was detected: continue serving the validated last-known-good and raise
   * an alarm; never silently accept drifted config (Doc 36 §DRC DRC-2).
   */
  public void markDriftDetected() {
    safeMetric(metrics::driftActive);
  }

  private static void safeMetric(final Runnable emit) {
    try {
      emit.run();
    } catch (final RuntimeException ignored) {
      // a metrics-port fault must never crash the control-plane refresh signalling (Doc 27 OT-A1)
    }
  }
}

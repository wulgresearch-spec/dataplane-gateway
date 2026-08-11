package io.reliabilityai.gateway.dataplane.app.runtime;

import io.reliabilityai.gateway.canonical.identity.SnapshotVersion;
import io.reliabilityai.gateway.dataplane.config.api.ConfigMetricsPort;

/**
 * Local no-op config metrics for the VPS default (Doc 36). Content-free counters routed nowhere
 * until a Prometheus/file exporter is wired; observability is passive and never affects the config
 * decision (Doc 27 OT-A1), so a no-op is the correct cheapest default — not a fake of any decision
 * path.
 */
final class NoOpConfigMetrics implements ConfigMetricsPort {

  @Override
  public void snapshotPinned(final SnapshotVersion version) {}

  @Override
  public void lastKnownGoodActive() {}

  @Override
  public void requiredMissing() {}

  @Override
  public void driftActive() {}
}

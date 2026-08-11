package io.reliabilityai.gateway.dataplane.app.runtime;

import io.reliabilityai.gateway.dataplane.observability.api.ObservabilityHealthPort;
import io.reliabilityai.gateway.dataplane.observability.api.RedactionVerdict;
import io.reliabilityai.gateway.dataplane.observability.application.TelemetryEmitService;
import io.reliabilityai.gateway.dataplane.observability.domain.BoundedExecutionDedup;
import io.reliabilityai.gateway.dataplane.observability.domain.DeterministicSampler;
import io.reliabilityai.gateway.dataplane.observability.domain.MetricLabelPolicy;
import java.util.Set;

/**
 * The single-VPS default observability wiring: a real {@link TelemetryEmitService} whose sinks are
 * local no-ops (telemetry goes nowhere until Prometheus / local-file exporters are wired). This is
 * not a fake of any decision path — observability is passive and removable with zero behavioral
 * change (Doc 27 OT-INV, AD-018); a no-op sink is the correct cheapest VPS default. Swap the sinks
 * for Prometheus/file/OTel exporters (or CloudWatch on AWS) with no change to any caller.
 */
final class LocalNoOpTelemetry {

  private LocalNoOpTelemetry() {}

  /** Builds the passive emitter with no-op sinks and the standard sampler/dedup/label policy. */
  static TelemetryEmitService emitter() {
    return new TelemetryEmitService(
        metric -> {}, // MetricSinkPort
        span -> {}, // TraceSinkPort
        record -> {}, // LogSinkPort
        observation -> {}, // EventFabricPort
        attributes -> RedactionVerdict.EMIT, // RedactionPort — permissive local default
        new NoOpHealth(),
        new DeterministicSampler(DeterministicSampler.ALWAYS),
        new BoundedExecutionDedup(1024),
        new MetricLabelPolicy(Set.of("outcome", "region")));
  }

  /** Content-free self-health counters routed to nowhere on the VPS default. */
  private static final class NoOpHealth implements ObservabilityHealthPort {
    @Override
    public void redactionRejected() {}

    @Override
    public void cardinalityViolation() {}

    @Override
    public void sinkFailure() {}

    @Override
    public void unknownSignal() {}
  }
}

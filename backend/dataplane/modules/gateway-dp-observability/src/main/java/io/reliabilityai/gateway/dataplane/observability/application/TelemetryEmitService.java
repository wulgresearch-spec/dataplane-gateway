package io.reliabilityai.gateway.dataplane.observability.application;

import io.reliabilityai.gateway.common.ContentFree;
import io.reliabilityai.gateway.common.Preconditions;
import io.reliabilityai.gateway.dataplane.observability.api.CanonicalLogRecord;
import io.reliabilityai.gateway.dataplane.observability.api.CanonicalMetric;
import io.reliabilityai.gateway.dataplane.observability.api.CanonicalSpan;
import io.reliabilityai.gateway.dataplane.observability.api.CanonicalTelemetryObservation;
import io.reliabilityai.gateway.dataplane.observability.api.EventFabricPort;
import io.reliabilityai.gateway.dataplane.observability.api.LogSinkPort;
import io.reliabilityai.gateway.dataplane.observability.api.MetricSinkPort;
import io.reliabilityai.gateway.dataplane.observability.api.ObservabilityHealthPort;
import io.reliabilityai.gateway.dataplane.observability.api.RedactionPort;
import io.reliabilityai.gateway.dataplane.observability.api.RedactionVerdict;
import io.reliabilityai.gateway.dataplane.observability.api.TelemetryEmitter;
import io.reliabilityai.gateway.dataplane.observability.api.TraceSinkPort;
import io.reliabilityai.gateway.dataplane.observability.domain.BoundedExecutionDedup;
import io.reliabilityai.gateway.dataplane.observability.domain.DeterministicSampler;
import io.reliabilityai.gateway.dataplane.observability.domain.MetricLabelPolicy;
import io.reliabilityai.gateway.ports.TelemetryEmitPort;

/**
 * The C9 telemetry emitter (Doc 27 §4, OT-INV). It is <b>passive and side-effect-free</b>: no
 * method alters, blocks, or fails a request or any runtime decision (Doc 27 OT-A1); every method
 * <b>fails closed by dropping telemetry</b> on any error — never runtime correctness (Doc 27 §18,
 * OT-D10). It is the only writer (Doc 27 REA-1), authors no policy/schema/metric-names (subordinate
 * to Doc 14, REA-3..7), redacts-before-emit and rejects residual sensitive patterns (Doc 27 §15.1),
 * samples traces deterministically from the correlation id (Doc 27 §19.1), de-duplicates metrics
 * per execution identity (Doc 27 §18.1), and enforces the Plane-A cardinality policy (Doc 27
 * OT-A6).
 *
 * <p>Implements both the typed {@link TelemetryEmitter} (Doc 27 §4) and the generic P0 {@link
 * TelemetryEmitPort} (which routes by type). Reachable only after a decision; additive and
 * removable with zero behavioral change (AD-018).
 */
public final class TelemetryEmitService implements TelemetryEmitter, TelemetryEmitPort {

  private final MetricSinkPort metricSink;
  private final TraceSinkPort traceSink;
  private final LogSinkPort logSink;
  private final EventFabricPort eventFabric;
  private final RedactionPort redaction;
  private final ObservabilityHealthPort health;
  private final DeterministicSampler sampler;
  private final BoundedExecutionDedup dedup;
  private final MetricLabelPolicy labelPolicy;

  /**
   * Creates the emitter against its injected sinks and policies (AD-002).
   *
   * @param metricSink the Plane-A metric sink (never sampled/dropped, Doc 27 OT-D5)
   * @param traceSink the trace sink (best-effort, Doc 27 §18.2)
   * @param logSink the structured-log sink (best-effort)
   * @param eventFabric the event-fabric sink for Plane-B observations (Doc 27 §EO)
   * @param redaction the reject-residual redaction gate (Doc 27 §15.1)
   * @param health the content-free self-health counters (Doc 27 §15.1/§18)
   * @param sampler the deterministic trace sampler (Doc 27 §19.1)
   * @param dedup the per-execution-identity metric de-duplicator (Doc 27 §18.1)
   * @param labelPolicy the Plane-A cardinality/label policy (Doc 27 OT-A6)
   */
  public TelemetryEmitService(
      final MetricSinkPort metricSink,
      final TraceSinkPort traceSink,
      final LogSinkPort logSink,
      final EventFabricPort eventFabric,
      final RedactionPort redaction,
      final ObservabilityHealthPort health,
      final DeterministicSampler sampler,
      final BoundedExecutionDedup dedup,
      final MetricLabelPolicy labelPolicy) {
    this.metricSink = Preconditions.requireNonNull(metricSink, "metricSink");
    this.traceSink = Preconditions.requireNonNull(traceSink, "traceSink");
    this.logSink = Preconditions.requireNonNull(logSink, "logSink");
    this.eventFabric = Preconditions.requireNonNull(eventFabric, "eventFabric");
    this.redaction = Preconditions.requireNonNull(redaction, "redaction");
    this.health = Preconditions.requireNonNull(health, "health");
    this.sampler = Preconditions.requireNonNull(sampler, "sampler");
    this.dedup = Preconditions.requireNonNull(dedup, "dedup");
    this.labelPolicy = Preconditions.requireNonNull(labelPolicy, "labelPolicy");
  }

  @Override
  public void metric(final CanonicalMetric metric) {
    if (metric == null) {
      return;
    }
    try {
      if (redaction.scan(metric.labels()) == RedactionVerdict.REJECTED) {
        safeHealth(health::redactionRejected);
        return;
      }
      if (!labelPolicy.isAllowed(metric.labels())) {
        // Plane-A cardinality guard (Doc 27 OT-A6): never emit an identifying/unbounded label.
        safeHealth(health::cardinalityViolation);
        return;
      }
      if (!dedup.firstOccurrence(metric.executionIdentity(), metric.name())) {
        // Already counted for this execution identity (Doc 27 RDD-2): drop, no double-count.
        return;
      }
      metricSink.record(metric);
    } catch (final RuntimeException e) {
      // OT-A1: telemetry never affects the request; drop and count, never throw.
      safeHealth(health::sinkFailure);
    }
  }

  @Override
  public void span(final CanonicalSpan span) {
    if (span == null) {
      return;
    }
    try {
      if (!sampler.shouldSample(span.correlationId())) {
        return;
      }
      if (redaction.scan(span.attributes()) == RedactionVerdict.REJECTED) {
        safeHealth(health::redactionRejected);
        return;
      }
      traceSink.record(span);
    } catch (final RuntimeException e) {
      safeHealth(health::sinkFailure);
    }
  }

  @Override
  public void log(final CanonicalLogRecord record) {
    if (record == null) {
      return;
    }
    try {
      if (redaction.scan(record.attributes()) == RedactionVerdict.REJECTED) {
        safeHealth(health::redactionRejected);
        return;
      }
      logSink.record(record);
    } catch (final RuntimeException e) {
      safeHealth(health::sinkFailure);
    }
  }

  @Override
  public void observation(final CanonicalTelemetryObservation observation) {
    if (observation == null) {
      return;
    }
    try {
      if (redaction.scan(observation.attributes()) == RedactionVerdict.REJECTED) {
        safeHealth(health::redactionRejected);
        return;
      }
      eventFabric.publish(observation);
    } catch (final RuntimeException e) {
      safeHealth(health::sinkFailure);
    }
  }

  @Override
  public <T extends ContentFree> void emit(final T signal) {
    if (signal == null) {
      return;
    }
    if (signal instanceof CanonicalMetric metric) {
      metric(metric);
    } else if (signal instanceof CanonicalSpan span) {
      span(span);
    } else if (signal instanceof CanonicalLogRecord record) {
      log(record);
    } else if (signal instanceof CanonicalTelemetryObservation observation) {
      observation(observation);
    } else {
      // Unknown content-free signal type: drop (side-effect-free, Doc 27 OT-A1).
      safeHealth(health::unknownSignal);
    }
  }

  /**
   * Emits a health signal fail-safely: even the health/self-observability seam must never throw
   * into the request path (Doc 27 OT-A1/OT-INV) — the whole point of this component is that
   * telemetry cannot affect the request, which must hold for the fail-safe reporting itself.
   */
  private static void safeHealth(final Runnable emit) {
    try {
      emit.run();
    } catch (final RuntimeException ignored) {
      // observability is side-effect-free — never affects the request, not even on a health-port
      // fault
    }
  }
}

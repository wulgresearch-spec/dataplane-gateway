package io.reliabilityai.gateway.dataplane.observability.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import io.reliabilityai.gateway.canonical.identity.AttemptId;
import io.reliabilityai.gateway.canonical.identity.CorrelationId;
import io.reliabilityai.gateway.canonical.identity.ExecutionIdentity;
import io.reliabilityai.gateway.canonical.identity.IdempotencyKey;
import io.reliabilityai.gateway.common.ContentFree;
import io.reliabilityai.gateway.dataplane.observability.api.CanonicalLogRecord;
import io.reliabilityai.gateway.dataplane.observability.api.CanonicalMetric;
import io.reliabilityai.gateway.dataplane.observability.api.CanonicalSpan;
import io.reliabilityai.gateway.dataplane.observability.api.CanonicalTelemetryObservation;
import io.reliabilityai.gateway.dataplane.observability.api.LogLevel;
import io.reliabilityai.gateway.dataplane.observability.api.MetricType;
import io.reliabilityai.gateway.dataplane.observability.api.RedactionVerdict;
import io.reliabilityai.gateway.dataplane.observability.domain.BoundedExecutionDedup;
import io.reliabilityai.gateway.dataplane.observability.domain.DeterministicSampler;
import io.reliabilityai.gateway.dataplane.observability.domain.MetricLabelPolicy;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** Behaviour tests for the side-effect-free telemetry emitter (Doc 27 OT-INV/OT-A1/§15.1/§18.1). */
class TelemetryEmitServiceTest {

  private static final CorrelationId CORR = new CorrelationId("corr-1");
  private static final ExecutionIdentity EXEC =
      new ExecutionIdentity(
          new IdempotencyKey("i"), new AttemptId("a"), new CorrelationId("corr-1"));

  private final RecordingSinks sinks = new RecordingSinks();
  private final ControllableRedaction redaction = new ControllableRedaction();
  private final CountingHealth health = new CountingHealth();

  private TelemetryEmitService service(final int sampleRate) {
    return new TelemetryEmitService(
        sinks.metric,
        sinks.trace,
        sinks.log,
        sinks.event,
        redaction,
        health,
        new DeterministicSampler(sampleRate),
        new BoundedExecutionDedup(1024),
        new MetricLabelPolicy(java.util.Set.of("outcome", "region")));
  }

  private static CanonicalMetric metric(final String name, final Map<String, String> labels) {
    return new CanonicalMetric(name, MetricType.COUNTER, 1, labels, EXEC);
  }

  @Test
  void metricEmittedOnceThenDeduped() {
    final var svc = service(DeterministicSampler.ALWAYS);
    svc.metric(metric("m_count", Map.of("outcome", "ok")));
    svc.metric(metric("m_count", Map.of("outcome", "ok"))); // same execId+name → deduped
    assertThat(sinks.metricCount).isEqualTo(1);
  }

  @Test
  void metricWithForbiddenLabelDropped() {
    final var svc = service(DeterministicSampler.ALWAYS);
    svc.metric(metric("m_count", Map.of("correlation_id", "corr-1")));
    assertThat(sinks.metricCount).isZero();
    assertThat(health.cardinality).isEqualTo(1);
  }

  @Test
  void metricRejectedByRedactionDropped() {
    final var svc = service(DeterministicSampler.ALWAYS);
    redaction.reject = true;
    svc.metric(metric("m_count", Map.of("outcome", "ok")));
    assertThat(sinks.metricCount).isZero();
    assertThat(health.redaction).isEqualTo(1);
  }

  @Test
  void sinkFailureIsSwallowedAndCounted() {
    sinks.throwOnMetric = true;
    final var svc = service(DeterministicSampler.ALWAYS);
    assertThatCode(() -> svc.metric(metric("m_count", Map.of()))).doesNotThrowAnyException();
    assertThat(health.sinkFailure).isEqualTo(1);
  }

  @Test
  void throwingHealthPortNeverEscapesTheTelemetryCall() {
    // Even the fail-safe health seam must never throw into the request path (Doc 27 OT-A1/OT-INV):
    // a failing sink AND a throwing health port must still leave metric() side-effect-free.
    sinks.throwOnMetric = true;
    final io.reliabilityai.gateway.dataplane.observability.api.ObservabilityHealthPort boomHealth =
        new io.reliabilityai.gateway.dataplane.observability.api.ObservabilityHealthPort() {
          @Override
          public void redactionRejected() {
            throw new RuntimeException("boom");
          }

          @Override
          public void cardinalityViolation() {
            throw new RuntimeException("boom");
          }

          @Override
          public void sinkFailure() {
            throw new RuntimeException("boom");
          }

          @Override
          public void unknownSignal() {
            throw new RuntimeException("boom");
          }
        };
    final TelemetryEmitService svc =
        new TelemetryEmitService(
            sinks.metric,
            sinks.trace,
            sinks.log,
            sinks.event,
            redaction,
            boomHealth,
            new DeterministicSampler(DeterministicSampler.ALWAYS),
            new BoundedExecutionDedup(1024),
            new MetricLabelPolicy(java.util.Set.of("outcome")));
    assertThatCode(() -> svc.metric(metric("m_count", Map.of()))).doesNotThrowAnyException();
  }

  @Test
  void spanSampledDeterministically() {
    service(DeterministicSampler.ALWAYS).span(span());
    assertThat(sinks.traceCount).isEqualTo(1);

    sinks.traceCount = 0;
    service(0).span(span()); // rate 0 → never kept
    assertThat(sinks.traceCount).isZero();
  }

  @Test
  void spanRejectedByRedactionDropped() {
    redaction.reject = true;
    service(DeterministicSampler.ALWAYS).span(span());
    assertThat(sinks.traceCount).isZero();
    assertThat(health.redaction).isEqualTo(1);
  }

  @Test
  void logAndObservationEmitAndRejectClosed() {
    final var svc = service(DeterministicSampler.ALWAYS);
    svc.log(new CanonicalLogRecord(LogLevel.INFO, "authn.ok", CORR, Map.of()));
    svc.observation(new CanonicalTelemetryObservation("exec.finalized", CORR, null, Map.of()));
    assertThat(sinks.logCount).isEqualTo(1);
    assertThat(sinks.eventCount).isEqualTo(1);

    redaction.reject = true;
    svc.log(new CanonicalLogRecord(LogLevel.WARN, "x", CORR, Map.of()));
    svc.observation(new CanonicalTelemetryObservation("y", CORR, null, Map.of()));
    assertThat(sinks.logCount).isEqualTo(1); // unchanged — rejected
    assertThat(sinks.eventCount).isEqualTo(1);
  }

  @Test
  void genericEmitRoutesByTypeAndDropsUnknown() {
    final var svc = service(DeterministicSampler.ALWAYS);
    svc.emit(metric("m_count", Map.of()));
    svc.emit(span());
    svc.emit((ContentFree) new UnknownSignal());
    assertThat(sinks.metricCount).isEqualTo(1);
    assertThat(sinks.traceCount).isEqualTo(1);
    assertThat(health.unknown).isEqualTo(1);
  }

  @Test
  void nullSignalsAreNoOps() {
    final var svc = service(DeterministicSampler.ALWAYS);
    assertThatCode(
            () -> {
              svc.metric(null);
              svc.span(null);
              svc.log(null);
              svc.observation(null);
              svc.emit((ContentFree) null); // clean no-op, not an "unknown signal"
            })
        .doesNotThrowAnyException();
    assertThat(sinks.metricCount + sinks.traceCount + sinks.logCount + sinks.eventCount).isZero();
    assertThat(health.unknown).isZero();
  }

  private static CanonicalSpan span() {
    return new CanonicalSpan("adapter.invoke", CORR, null, null, Map.of("outcome", "ok"));
  }

  private record UnknownSignal() implements ContentFree {}

  private static final class ControllableRedaction
      implements io.reliabilityai.gateway.dataplane.observability.api.RedactionPort {
    private boolean reject;

    @Override
    public RedactionVerdict scan(final Map<String, String> attributes) {
      return reject ? RedactionVerdict.REJECTED : RedactionVerdict.EMIT;
    }
  }

  private static final class CountingHealth
      implements io.reliabilityai.gateway.dataplane.observability.api.ObservabilityHealthPort {
    private int redaction;
    private int cardinality;
    private int sinkFailure;
    private int unknown;

    @Override
    public void redactionRejected() {
      redaction++;
    }

    @Override
    public void cardinalityViolation() {
      cardinality++;
    }

    @Override
    public void sinkFailure() {
      sinkFailure++;
    }

    @Override
    public void unknownSignal() {
      unknown++;
    }
  }

  private static final class RecordingSinks {
    private int metricCount;
    private int traceCount;
    private int logCount;
    private int eventCount;
    private boolean throwOnMetric;

    private final io.reliabilityai.gateway.dataplane.observability.api.MetricSinkPort metric =
        m -> {
          if (throwOnMetric) {
            throw new IllegalStateException("metric backend down");
          }
          metricCount++;
        };
    private final io.reliabilityai.gateway.dataplane.observability.api.TraceSinkPort trace =
        s -> traceCount++;
    private final io.reliabilityai.gateway.dataplane.observability.api.LogSinkPort log =
        r -> logCount++;
    private final io.reliabilityai.gateway.dataplane.observability.api.EventFabricPort event =
        o -> eventCount++;
  }
}

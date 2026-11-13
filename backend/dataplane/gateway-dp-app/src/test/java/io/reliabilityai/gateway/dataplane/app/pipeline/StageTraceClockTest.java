package io.reliabilityai.gateway.dataplane.app.pipeline;

import static org.assertj.core.api.Assertions.assertThat;

import io.reliabilityai.gateway.dataplane.app.MandatoryStage;
import io.reliabilityai.gateway.dataplane.app.pipeline.StageTrace.Disposition;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

/** Verifies that stage timing reads the injected clock and never affects stage order. */
class StageTraceClockTest {

  @Test
  void elapsedTimeComesFromTheInjectedClock() {
    final Instant origin = Instant.parse("2026-01-01T00:00:00Z");
    final AtomicReference<Instant> now = new AtomicReference<>(origin);
    final StageTrace trace = new StageTrace(now::get);
    now.set(origin.plusMillis(12L));

    trace.record(MandatoryStage.INGRESS, Disposition.EXECUTED, origin, "admitted");

    assertThat(trace.entries()).hasSize(1);
    assertThat(trace.entries().get(0).elapsedNanos()).isEqualTo(12_000_000L);
    assertThat(trace.stages()).containsExactly(MandatoryStage.INGRESS);
  }

  @Test
  void backwardClockAdjustmentCannotProduceNegativeDuration() {
    final Instant origin = Instant.parse("2026-01-01T00:00:00Z");
    final StageTrace trace = new StageTrace(() -> origin.minusSeconds(1L));

    trace.record(MandatoryStage.AUTHN, Disposition.REFUSED, origin, "denied");

    assertThat(trace.entries().get(0).elapsedNanos()).isZero();
    assertThat(trace.refusalStage()).contains(MandatoryStage.AUTHN);
  }
}

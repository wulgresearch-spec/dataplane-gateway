package io.reliabilityai.gateway.dataplane.observability.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.reliabilityai.gateway.canonical.identity.CorrelationId;
import org.junit.jupiter.api.Test;

/** Determinism tests for the trace sampler (Doc 27 §19.1 RSD-2). */
class DeterministicSamplerTest {

  @Test
  void alwaysAndNeverAreRateBounded() {
    final var corr = new CorrelationId("corr-1");
    assertThat(new DeterministicSampler(DeterministicSampler.ALWAYS).shouldSample(corr)).isTrue();
    assertThat(new DeterministicSampler(0).shouldSample(corr)).isFalse();
  }

  @Test
  void resolutionIsDeterministic() {
    final var sampler = new DeterministicSampler(5_000);
    final var corr = new CorrelationId("corr-42");
    final boolean first = sampler.shouldSample(corr);
    final boolean second = sampler.shouldSample(corr);
    assertThat(first).isEqualTo(second);
    // A second sampler at the same rate agrees (pure function).
    assertThat(new DeterministicSampler(5_000).shouldSample(corr)).isEqualTo(first);
  }

  @Test
  void distributesAcrossCorrelationIdsWithinTolerance() {
    final var sampler = new DeterministicSampler(5_000);
    int kept = 0;
    final int n = 400;
    for (int i = 0; i < n; i++) {
      if (sampler.shouldSample(new CorrelationId("corr-" + i))) {
        kept++;
      }
    }
    assertThat(kept).isBetween((int) (n * 0.35), (int) (n * 0.65));
  }

  @Test
  void rejectsInvalidRate() {
    assertThatThrownBy(() -> new DeterministicSampler(-1))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new DeterministicSampler(10_001))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void rejectsNullCorrelationId() {
    assertThatThrownBy(() -> new DeterministicSampler(5_000).shouldSample(null))
        .isInstanceOf(NullPointerException.class);
  }
}

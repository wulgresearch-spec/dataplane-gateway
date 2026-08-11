package io.reliabilityai.gateway.dataplane.metering.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.reliabilityai.gateway.canonical.io.CanonicalUsage;
import io.reliabilityai.gateway.canonical.io.UsageClass;
import org.junit.jupiter.api.Test;

/** Exact provider-usage aggregation semantics (Doc 23 §26). */
class UsageAggregationTest {

  private static CanonicalUsage u(final long p, final UsageClass cls) {
    return new CanonicalUsage(p, p, p, p, p, cls);
  }

  @Test
  void sumsUnitsExactly() {
    final CanonicalUsage sum =
        UsageAggregation.add(u(3, UsageClass.AUTHORITATIVE), u(4, UsageClass.AUTHORITATIVE));
    assertThat(sum.prompt()).isEqualTo(7);
    assertThat(sum.completion()).isEqualTo(7);
    assertThat(sum.toolTokens()).isEqualTo(7);
  }

  @Test
  void aggregateIsEstimatedIfAnyInputEstimated() {
    assertThat(
            UsageAggregation.add(u(1, UsageClass.AUTHORITATIVE), u(1, UsageClass.ESTIMATED))
                .usageClass())
        .isEqualTo(UsageClass.ESTIMATED); // never claim more authoritative than inputs
  }

  @Test
  void zeroIsAdditiveIdentity() {
    final CanonicalUsage v = u(5, UsageClass.AUTHORITATIVE);
    assertThat(UsageAggregation.add(UsageAggregation.ZERO, v)).isEqualTo(v);
  }

  @Test
  void overflowFailsClosed() {
    final CanonicalUsage max = u(Long.MAX_VALUE, UsageClass.AUTHORITATIVE);
    assertThatThrownBy(() -> UsageAggregation.add(max, u(1, UsageClass.AUTHORITATIVE)))
        .isInstanceOf(IllegalStateException.class);
  }
}

package io.reliabilityai.gateway.dataplane.reliability.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.reliabilityai.gateway.dataplane.reliability.api.ReliabilityPolicy;
import java.time.Duration;
import org.junit.jupiter.api.Test;

/**
 * Determinism, cap, and monotone-envelope properties of the seeded full-jitter backoff (Doc 20
 * §15).
 */
class BackoffCalculatorTest {

  private static final ReliabilityPolicy POLICY =
      new ReliabilityPolicy(10, 5, 10L, 1000L, Duration.ofSeconds(5));

  @Test
  void isDeterministicForSameSeed() {
    final Duration first = BackoffCalculator.backoff("corr-x", 3, POLICY);
    final Duration second = BackoffCalculator.backoff("corr-x", 3, POLICY);
    assertThat(first).isEqualTo(second); // replay-safe (RE-D9)
  }

  @Test
  void differsAcrossCorrelationIds() {
    // Anti-herd: two requests at the same attempt desynchronize (overwhelmingly likely distinct).
    assertThat(BackoffCalculator.backoff("corr-a", 4, POLICY))
        .isNotEqualTo(BackoffCalculator.backoff("corr-b", 4, POLICY));
  }

  @Test
  void neverExceedsCap() {
    for (int attempt = 1; attempt <= 40; attempt++) {
      final Duration d = BackoffCalculator.backoff("corr-cap", attempt, POLICY);
      assertThat(d.toMillis()).isBetween(0L, POLICY.backoffCapMillis());
    }
  }

  @Test
  void largeBaseNeverOverflowsToNegative() {
    // A pathologically large base must not overflow the left shift into a negative exponential.
    final ReliabilityPolicy hugeBase =
        new ReliabilityPolicy(
            10, 5, 4_000_000_000_000_000_000L, Long.MAX_VALUE, Duration.ofSeconds(5));
    for (int attempt = 1; attempt <= 20; attempt++) {
      final Duration d = BackoffCalculator.backoff("corr", attempt, hugeBase);
      assertThat(d.toMillis()).isGreaterThanOrEqualTo(0L); // never negative
    }
  }

  @Test
  void firstAttemptIsBoundedByBase() {
    // Exponential envelope at attempt 1 is `base`; full jitter keeps it in [0, base).
    for (int i = 0; i < 8; i++) {
      final Duration d = BackoffCalculator.backoff("corr-" + i, 1, POLICY);
      assertThat(d.toMillis()).isBetween(0L, POLICY.backoffBaseMillis());
    }
  }

  @Test
  void rejectsAttemptBelowOne() {
    assertThatThrownBy(() -> BackoffCalculator.backoff("corr", 0, POLICY))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void rejectsBlankCorrelationId() {
    assertThatThrownBy(() -> BackoffCalculator.backoff("  ", 1, POLICY))
        .isInstanceOf(IllegalArgumentException.class);
  }
}

package io.reliabilityai.gateway.dataplane.observability.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.reliabilityai.gateway.canonical.identity.AttemptId;
import io.reliabilityai.gateway.canonical.identity.CorrelationId;
import io.reliabilityai.gateway.canonical.identity.ExecutionIdentity;
import io.reliabilityai.gateway.canonical.identity.IdempotencyKey;
import org.junit.jupiter.api.Test;

/** De-duplication tests for metrics keyed by execution identity (Doc 27 §18.1 RDD-2). */
class BoundedExecutionDedupTest {

  private static ExecutionIdentity id(final String suffix) {
    return new ExecutionIdentity(
        new IdempotencyKey("idem-" + suffix),
        new AttemptId("attempt-" + suffix),
        new CorrelationId("corr-" + suffix));
  }

  @Test
  void firstOccurrenceThenDuplicate() {
    final var dedup = new BoundedExecutionDedup(100);
    assertThat(dedup.firstOccurrence(id("1"), "m_count")).isTrue();
    assertThat(dedup.firstOccurrence(id("1"), "m_count")).isFalse();
  }

  @Test
  void distinctMetricNamesAreIndependent() {
    final var dedup = new BoundedExecutionDedup(100);
    assertThat(dedup.firstOccurrence(id("1"), "m_a")).isTrue();
    assertThat(dedup.firstOccurrence(id("1"), "m_b")).isTrue();
  }

  @Test
  void distinctExecutionIdentitiesAreIndependent() {
    final var dedup = new BoundedExecutionDedup(100);
    assertThat(dedup.firstOccurrence(id("1"), "m")).isTrue();
    assertThat(dedup.firstOccurrence(id("2"), "m")).isTrue();
  }

  @Test
  void evictsLeastRecentlyUsedBeyondCapacity() {
    final var dedup = new BoundedExecutionDedup(2);
    assertThat(dedup.firstOccurrence(id("A"), "m")).isTrue();
    assertThat(dedup.firstOccurrence(id("B"), "m")).isTrue();
    assertThat(dedup.firstOccurrence(id("C"), "m")).isTrue(); // evicts A (LRU)
    // A was evicted → treated as first-occurrence again (bounded best-effort, Doc 27 §17).
    assertThat(dedup.firstOccurrence(id("A"), "m")).isTrue();
  }

  @Test
  void constructorRejectsNonPositiveCapacity() {
    assertThatThrownBy(() -> new BoundedExecutionDedup(0))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void rejectsNullArguments() {
    final var dedup = new BoundedExecutionDedup(4);
    assertThatThrownBy(() -> dedup.firstOccurrence(null, "m"))
        .isInstanceOf(NullPointerException.class);
    assertThatThrownBy(() -> dedup.firstOccurrence(id("1"), " "))
        .isInstanceOf(IllegalArgumentException.class);
  }
}

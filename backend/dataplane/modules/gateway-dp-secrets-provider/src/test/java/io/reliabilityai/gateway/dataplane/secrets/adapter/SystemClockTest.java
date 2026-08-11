package io.reliabilityai.gateway.dataplane.secrets.adapter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;

/**
 * The clock adapter must delegate to its injected {@link Clock} and never read ambient time (Doc 11
 * R-063). Asserted with a fixed clock, so the test states a delegation contract rather than a
 * wall-clock observation and cannot flake.
 */
class SystemClockTest {

  @Test
  void nowDelegatesToTheInjectedClock() {
    final Instant pinned = Instant.parse("2026-07-22T00:00:00Z");

    assertThat(new SystemClock(Clock.fixed(pinned, ZoneOffset.UTC)).now()).isEqualTo(pinned);
  }

  @Test
  void nowReflectsTheInjectedClockRatherThanASnapshotTakenAtConstruction() {
    // A delegating adapter must read through on every call. An implementation that captured the
    // instant once at construction would pass the test above and still be wrong.
    final Instant first = Instant.parse("2026-07-22T00:00:00Z");
    final Instant second = Instant.parse("2026-07-22T00:05:00Z");
    final MutableClock clock = new MutableClock(first);
    final SystemClock adapter = new SystemClock(clock);

    assertThat(adapter.now()).isEqualTo(first);
    clock.instant = second;
    assertThat(adapter.now()).isEqualTo(second);
  }

  @Test
  void rejectsNullClock() {
    assertThatThrownBy(() -> new SystemClock(null))
        .isInstanceOf(NullPointerException.class)
        .hasMessageContaining("clock");
  }

  /** A clock whose instant can be moved between calls, without any wall-clock dependency. */
  private static final class MutableClock extends Clock {
    private Instant instant;

    private MutableClock(final Instant instant) {
      this.instant = instant;
    }

    @Override
    public java.time.ZoneId getZone() {
      return ZoneOffset.UTC;
    }

    @Override
    public Clock withZone(final java.time.ZoneId zone) {
      return this;
    }

    @Override
    public Instant instant() {
      return instant;
    }
  }
}

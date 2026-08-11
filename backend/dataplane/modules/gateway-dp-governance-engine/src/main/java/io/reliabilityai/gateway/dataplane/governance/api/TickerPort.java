package io.reliabilityai.gateway.dataplane.governance.api;

/**
 * The monotonic elapsed-time seam used to measure evaluation cost.
 *
 * <p>Separate from {@code ClockPort} on purpose. A wall clock answers "what time is it", can jump
 * backwards when NTP corrects it, and is the right input for a maintenance window; a ticker answers
 * "how much time has passed", never goes backwards, and is the only correct input for a duration.
 * Measuring latency with a wall clock is how a negative latency ends up in a metrics dashboard.
 *
 * <p>It is a port rather than a direct {@code System.nanoTime()} call for two reasons: ambient
 * non-deterministic reads are forbidden inside decision cores (Doc 11 R-063), and a test that
 * injects a fixed ticker gets a decision whose recorded latency is an assertable constant instead
 * of noise.
 */
@FunctionalInterface
public interface TickerPort {

  /** A ticker frozen at zero — for tests that assert on a decision's exact contents. */
  TickerPort FROZEN = () -> 0L;

  /**
   * Reads the monotonic counter.
   *
   * @return elapsed nanoseconds from an arbitrary but fixed origin
   */
  long nanos();
}

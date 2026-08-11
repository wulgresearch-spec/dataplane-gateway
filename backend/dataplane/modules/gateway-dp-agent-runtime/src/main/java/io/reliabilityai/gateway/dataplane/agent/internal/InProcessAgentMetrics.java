package io.reliabilityai.gateway.dataplane.agent.internal;

import io.reliabilityai.gateway.common.Preconditions;
import io.reliabilityai.gateway.dataplane.agent.api.AgentMetricsPort;
import io.reliabilityai.gateway.dataplane.agent.api.PlanId;
import io.reliabilityai.gateway.dataplane.agent.api.StepKind;
import io.reliabilityai.gateway.dataplane.agent.api.StepStatus;
import io.reliabilityai.gateway.dataplane.agent.api.SupervisionStrategy;
import io.reliabilityai.gateway.dataplane.agent.api.TerminalReason;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.LongAdder;

/**
 * Counters held in this process.
 *
 * <p>Enough to make the runtime observable in a single-node deployment and to let tests assert on
 * signals rather than on log output. A fleet feeds the real telemetry infrastructure through the
 * same port; nothing here is a new sink (AD-025 OBC-3).
 *
 * <p>{@link LongAdder} rather than {@code AtomicLong}: these are written from every executor thread
 * and read rarely, which is the access pattern the adder is for.
 */
public final class InProcessAgentMetrics implements AgentMetricsPort {

  private final Map<String, LongAdder> counters = new ConcurrentHashMap<>();

  @Override
  public void runStarted(final PlanId planId, final int planVersion) {
    bump("run.started");
    bump("run.started." + planId.value());
  }

  @Override
  public void runTerminated(
      final PlanId planId,
      final TerminalReason reason,
      final Duration duration,
      final int stepsExecuted,
      final long costMicros) {
    bump("run.terminated");
    bump("run.terminated." + reason.name());
    add("run.steps", stepsExecuted);
    add("run.cost.micros", costMicros);
    add("run.duration.millis", duration.toMillis());
  }

  @Override
  public void stepFinished(
      final PlanId planId,
      final String stepName,
      final StepKind kind,
      final StepStatus status,
      final Duration duration,
      final long costMicros) {
    bump("step.finished");
    bump("step.finished." + kind.name() + "." + status.name());
    add("step.duration.millis", duration.toMillis());
  }

  @Override
  public void supervisionApplied(
      final PlanId planId, final SupervisionStrategy strategy, final String decision) {
    bump("supervision." + strategy.name() + "." + decision);
  }

  @Override
  public void restartIntensityExceeded(final PlanId planId) {
    bump("supervision.restart_intensity_exceeded");
  }

  @Override
  public void replayDivergence(final PlanId planId, final int planVersion) {
    // Expected value zero. A non-zero reading means non-determinism has leaked into the
    // interpreter,
    // and every recovery guarantee in this design rests on that not happening.
    bump("replay.divergence");
  }

  @Override
  public void interruptedStepRecovered(final String resolution) {
    bump("recovery.interrupted." + resolution);
  }

  @Override
  public void storeUnavailable(final String operation) {
    bump("store.unavailable");
  }

  @Override
  public void boundRefused(final PlanId planId, final String bound) {
    bump("bound.refused." + bound);
  }

  /**
   * Reads one counter.
   *
   * @param name the counter name
   * @return its value, zero when never touched
   */
  public long counter(final String name) {
    Preconditions.requireNonNull(name, "name");
    final LongAdder adder = counters.get(name);
    return adder == null ? 0L : adder.sum();
  }

  /**
   * Returns a snapshot of every counter.
   *
   * @return name to value, at the moment of the call
   */
  public Map<String, Long> snapshot() {
    final Map<String, Long> copy = new java.util.TreeMap<>();
    counters.forEach((name, adder) -> copy.put(name, adder.sum()));
    return java.util.Collections.unmodifiableMap(copy);
  }

  /** Resets every counter. For tests that assert on deltas. */
  public void reset() {
    counters.clear();
  }

  private void bump(final String name) {
    add(name, 1L);
  }

  private void add(final String name, final long delta) {
    counters.computeIfAbsent(name, key -> new LongAdder()).add(delta);
  }
}

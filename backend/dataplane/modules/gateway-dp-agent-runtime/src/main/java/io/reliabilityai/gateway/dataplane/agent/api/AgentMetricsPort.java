package io.reliabilityai.gateway.dataplane.agent.api;

import java.time.Duration;

/**
 * Where the Agent Runtime's operational signals go (AD-025 §49, OBC-3).
 *
 * <p>A port, so the runtime emits without knowing where. No new sink is introduced — the adapter
 * feeds the metrics infrastructure that already exists.
 *
 * <p>Four of these matter more than the rest, because each is silent until it is expensive: {@link
 * #runTerminated} dimensioned by reason tells an operator which ceiling is mis-set; {@link
 * #restartIntensityExceeded} is a crash loop; {@link #replayDivergence} should always read zero,
 * and a non-zero value is a defect rather than a warning; and {@link #interruptedStepRecovered}
 * shows how often nodes are dying mid-step.
 */
public interface AgentMetricsPort {

  /**
   * A metrics port that discards everything. For tests and for a runtime with no telemetry wired.
   */
  AgentMetricsPort NOOP = new AgentMetricsPort() {};

  /**
   * A run was admitted and created.
   *
   * @param planId the plan
   * @param planVersion the pinned version
   */
  default void runStarted(PlanId planId, int planVersion) {}

  /**
   * A run ended.
   *
   * @param planId the plan
   * @param reason the closed-set terminal reason
   * @param duration how long the run took, wall-clock
   * @param stepsExecuted the size of the execution graph
   * @param costMicros the total spend
   */
  default void runTerminated(
      PlanId planId,
      TerminalReason reason,
      Duration duration,
      int stepsExecuted,
      long costMicros) {}

  /**
   * A step attempt finished.
   *
   * @param planId the plan
   * @param stepName the plan-local step name
   * @param kind the step kind
   * @param status the outcome
   * @param duration how long the attempt took
   * @param costMicros what the attempt cost
   */
  default void stepFinished(
      PlanId planId,
      String stepName,
      StepKind kind,
      StepStatus status,
      Duration duration,
      long costMicros) {}

  /**
   * The supervisor made a decision.
   *
   * @param planId the plan
   * @param strategy the declared strategy
   * @param decision the decision's label
   */
  default void supervisionApplied(PlanId planId, SupervisionStrategy strategy, String decision) {}

  /**
   * A run exhausted its restart intensity.
   *
   * @param planId the plan
   */
  default void restartIntensityExceeded(PlanId planId) {}

  /**
   * A replay produced a different next step than the history records.
   *
   * <p>Expected value: zero. Anything else means non-determinism has leaked into the interpreter,
   * and every recovery guarantee in the design rests on that not happening.
   *
   * @param planId the plan
   * @param planVersion the pinned version
   */
  default void replayDivergence(PlanId planId, int planVersion) {}

  /**
   * An interrupted step was resolved during recovery.
   *
   * @param resolution how it was resolved: adopted from the layer below, treated as failed, or
   *     timed out waiting for an in-flight session (AD-025 §35.2)
   */
  default void interruptedStepRecovered(String resolution) {}

  /**
   * The run store could not be reached.
   *
   * @param operation the operation that failed
   */
  default void storeUnavailable(String operation) {}

  /**
   * A run was refused at admission or a step was refused by a bound.
   *
   * @param planId the plan
   * @param bound which bound refused: steps, depth, fanout, deadline or budget
   */
  default void boundRefused(PlanId planId, String bound) {}
}

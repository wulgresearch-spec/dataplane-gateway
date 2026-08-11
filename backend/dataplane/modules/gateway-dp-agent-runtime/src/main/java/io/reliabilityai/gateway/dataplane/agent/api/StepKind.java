package io.reliabilityai.gateway.dataplane.agent.api;

/**
 * The closed set of things a step executor may do.
 *
 * <p>Five kinds, and no sixth. The executor's whole surface is a switch over this enum, so adding a
 * capability to the Agent Runtime means adding a constant here and to the plan grammar — a visible,
 * reviewable change rather than a new branch buried in an orchestration method.
 */
public enum StepKind {

  /**
   * One model invocation: exactly one tool session, which performs at least one complete {@code
   * RequestPipeline} execution (AD-025 AGT-2, AGT-12).
   */
  PIPELINE,

  /**
   * One tool invocation through the Plugin Runtime, producing a {@link ToolResultArtifact}.
   *
   * <p>The artifact is data in the run history. It reaches a model only when a later {@link
   * #PIPELINE} step names it as an input, which is a new pipeline execution — AD-025 AGT-9. There
   * is no path from a tool's output into a model's context that skips a governed turn.
   */
  PLUGIN,

  /** A durable timer. Parks the run with no resources held until a recorded wake time. */
  WAIT,

  /** Evaluates a declared predicate over recorded results and records the boolean. */
  CONDITION,

  /** Evaluates a declared predicate and selects the next step by name. */
  BRANCH;

  /**
   * Reports whether this kind performs I/O outside the runtime.
   *
   * <p>The distinction matters for replay: the non-deterministic kinds are the ones whose results
   * must come from the history rather than from re-execution (AD-025 §46.2).
   *
   * @return true for the kinds whose outcome cannot be recomputed
   */
  public boolean nonDeterministic() {
    return this == PIPELINE || this == PLUGIN;
  }
}

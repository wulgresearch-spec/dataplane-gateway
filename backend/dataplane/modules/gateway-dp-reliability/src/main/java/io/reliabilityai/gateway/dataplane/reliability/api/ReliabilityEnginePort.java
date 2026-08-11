package io.reliabilityai.gateway.dataplane.reliability.api;

/**
 * The inbound Reliability Engine port (C2, Doc 20 §7, AD-002/AD-016). Drives provider invocation
 * within the Router's immutable candidate list, applying retry/failover/circuit/timeout within
 * budgets, and fails closed — never duplicating a successful execution or bypassing correctness
 * (RE-INV). Called on the hot path after the Router emits a decision, before
 * StreamGuard/SchemaLock.
 */
public interface ReliabilityEnginePort {

  /**
   * Executes the plan reliably and deterministically, fail-closed (Doc 20 §8).
   *
   * @param plan the immutable invocation plan (candidates + deadline)
   * @return the terminal invocation result (success or surfaced failure)
   */
  InvocationResult execute(InvocationPlan plan);
}

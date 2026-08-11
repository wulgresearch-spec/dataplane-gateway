package io.reliabilityai.gateway.dataplane.plugin.api;

import io.reliabilityai.gateway.common.Preconditions;

/**
 * The outcome of a tool invocation (Doc 28 §6 {@code PluginOutcome}).
 *
 * <p>A sealed set of exactly three shapes, so a caller cannot forget a case. There is no "partially
 * succeeded": a tool either produced a response the runtime is willing to hand on, or it did not.
 * Anything in between is {@link Isolated}, because forwarding a half-result is how a plugin failure
 * turns into a wrong answer instead of a missing one (Doc 28 PRT-INV).
 *
 * <p>Every variant carries its cost. Doc 28 §47 requires an accounting record for <em>every</em>
 * invocation, and a failed invocation still consumed CPU and wall-clock — omitting the cost of
 * failures would make a crash-looping plugin look free.
 */
public sealed interface ToolExecutionResult
    permits ToolExecutionResult.Completed,
        ToolExecutionResult.Isolated,
        ToolExecutionResult.Cancelled {

  /**
   * The cost this invocation incurred, whatever its outcome.
   *
   * @return the accounting record
   */
  PluginInvocationCost cost();

  /**
   * A tool that ran to completion and produced a response.
   *
   * @param response the tool output
   * @param cost the accounting record
   */
  record Completed(ToolResponse response, PluginInvocationCost cost)
      implements ToolExecutionResult {
    /** Compact constructor validating the outcome. */
    public Completed {
      Preconditions.requireNonNull(response, "response");
      Preconditions.requireNonNull(cost, "cost");
    }
  }

  /**
   * A tool that failed, breached a quota, or attempted a violation. Its contribution is discarded
   * and the caller proceeds unweakened (Doc 28 §EPFC).
   *
   * @param error the content-free failure description
   * @param cost the accounting record
   */
  record Isolated(ToolError error, PluginInvocationCost cost) implements ToolExecutionResult {
    /** Compact constructor validating the outcome. */
    public Isolated {
      Preconditions.requireNonNull(error, "error");
      Preconditions.requireNonNull(cost, "cost");
    }
  }

  /**
   * A tool whose invocation was cancelled before it produced a result — by the caller, or because
   * the enclosing request went away. Distinct from {@link Isolated}: nothing went wrong.
   *
   * @param cost the accounting record
   */
  record Cancelled(PluginInvocationCost cost) implements ToolExecutionResult {
    /** Compact constructor validating the outcome. */
    public Cancelled {
      Preconditions.requireNonNull(cost, "cost");
    }
  }
}

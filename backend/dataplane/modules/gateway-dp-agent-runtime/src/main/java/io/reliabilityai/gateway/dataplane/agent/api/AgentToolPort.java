package io.reliabilityai.gateway.dataplane.agent.api;

import io.reliabilityai.gateway.canonical.identity.CorrelationId;
import io.reliabilityai.gateway.canonical.identity.TenantScope;
import io.reliabilityai.gateway.common.Preconditions;
import java.time.Duration;
import java.util.Optional;

/**
 * The seam to tool execution, for {@link StepKind#PLUGIN} steps.
 *
 * <p><b>The Agent Runtime never executes a tool itself</b> (AD-025 AGT-4). This module depends on
 * no plugin-runtime type; the adapter behind this port lives in the composition root and calls the
 * existing Plugin Runtime unchanged — its sandbox, its permission evaluation, its quotas, its cost
 * reporting.
 *
 * <p><b>A tool result never reaches a model through this port.</b> It becomes a {@link
 * ToolResultArtifact} recorded in the run history. A model sees it only when a later {@link
 * StepKind#PIPELINE} step names the artifact as an input, and that is a new, fully governed
 * pipeline execution (AD-025 AGT-9). There is no injection path here, by construction.
 *
 * <p><b>A note on layering.</b> AD-025 AGT-4 has tools reached <em>through</em> C13 rather than
 * beside it. C13 does not exist yet, and the milestone brief enumerates {@code plugin} as a step
 * kind the executor handles, so this port exists as its own seam. It is deliberately shaped so that
 * collapsing it into {@link ToolSessionPort} later changes the adapter and nothing in the runtime.
 */
public interface AgentToolPort {

  /**
   * What a plugin step asks a tool to do.
   *
   * @param invocationRef an opaque, caller-derived reference recorded before the tool runs, so an
   *     interrupted step can later ask what became of it
   * @param capability the capability to exercise; resolution to a concrete plugin belongs to the
   *     adapter, so a plan can never pin itself to a plugin build
   * @param arguments the opaque argument payload
   * @param tenant the tenant scope
   * @param correlationId the run's correlation
   * @param budgetMicros the spend allotment
   * @param deadline the tool deadline
   */
  record ToolCall(
      String invocationRef,
      String capability,
      String arguments,
      TenantScope tenant,
      CorrelationId correlationId,
      long budgetMicros,
      Duration deadline) {

    /**
     * Validates the call.
     *
     * @param invocationRef the opaque invocation reference
     * @param capability the capability to exercise
     * @param arguments the argument payload
     * @param tenant the tenant scope
     * @param correlationId the correlation
     * @param budgetMicros the spend allotment
     * @param deadline the tool deadline
     */
    public ToolCall {
      Preconditions.requireNonBlank(invocationRef, "invocationRef");
      Preconditions.requireNonBlank(capability, "capability");
      Preconditions.requireNonNull(arguments, "arguments");
      Preconditions.requireNonNull(tenant, "tenant");
      Preconditions.requireNonNull(correlationId, "correlationId");
      Preconditions.requireNonNegative(budgetMicros, "budgetMicros");
      Preconditions.requireNonNull(deadline, "deadline");
    }
  }

  /** What a tool invocation produced. */
  sealed interface ToolOutcome permits Produced, Rejected, Aborted {

    /**
     * Returns what the invocation cost.
     *
     * @return spend in micros, as reported by the Plugin Runtime
     */
    long costMicros();
  }

  /**
   * The tool ran and produced content.
   *
   * @param content the tool's output, already bounded by the adapter
   * @param costMicros the reported spend
   * @param trusted whether policy declares this capability's output trustworthy; false by default,
   *     so the artifact is tainted unless something explicitly says otherwise
   */
  record Produced(String content, long costMicros, boolean trusted) implements ToolOutcome {

    /**
     * Validates the outcome.
     *
     * @param content the tool output
     * @param costMicros the reported spend
     * @param trusted the trust declaration
     */
    public Produced {
      Preconditions.requireNonNull(content, "content");
      Preconditions.requireNonNegative(costMicros, "costMicros");
    }
  }

  /**
   * The tool failed or was refused.
   *
   * @param failure the classified failure
   * @param reason the explanation
   * @param costMicros the spend incurred before failing
   */
  record Rejected(FailureClass failure, String reason, long costMicros) implements ToolOutcome {

    /**
     * Validates the outcome.
     *
     * @param failure the classified failure
     * @param reason the explanation
     * @param costMicros the spend incurred
     */
    public Rejected {
      Preconditions.requireNonNull(failure, "failure");
      Preconditions.requireNonBlank(reason, "reason");
      Preconditions.requireNonNegative(costMicros, "costMicros");
    }
  }

  /**
   * The invocation was cancelled.
   *
   * @param costMicros the spend incurred before cancelling
   */
  record Aborted(long costMicros) implements ToolOutcome {

    /**
     * Validates the outcome.
     *
     * @param costMicros the spend incurred
     */
    public Aborted {
      Preconditions.requireNonNegative(costMicros, "costMicros");
    }
  }

  /**
   * Invokes one tool and returns its outcome.
   *
   * @param call what the step asks for
   * @return what the tool produced
   */
  ToolOutcome invoke(ToolCall call);

  /**
   * Cancels an in-flight invocation.
   *
   * @param invocationRef the reference recorded when the step was scheduled
   */
  void cancel(String invocationRef);

  /**
   * Asks what became of an invocation, for interrupted-step resolution.
   *
   * @param invocationRef the reference recorded when the step was scheduled
   * @return the outcome if known, empty when the adapter has no record
   */
  Optional<ToolOutcome> lookup(String invocationRef);
}

package io.reliabilityai.gateway.dataplane.agent.api;

import io.reliabilityai.gateway.canonical.identity.CorrelationId;
import io.reliabilityai.gateway.canonical.identity.TenantScope;
import io.reliabilityai.gateway.common.Preconditions;
import java.time.Duration;
import java.util.List;
import java.util.Set;

/**
 * The seam to the Tool Session Orchestrator, C13 (AD-025 §82, contract SPC).
 *
 * <p><b>This is the only way the Agent Runtime reaches a model.</b> AD-025 AGT-1 forbids calling a
 * provider; AGT-2 forbids invoking {@code RequestPipeline}; AGT-12 requires exactly one session per
 * step. All three are enforced structurally rather than by convention: this module depends on no
 * pipeline type, no provider type and no plugin type, so there is no other call it could make.
 *
 * <p>What the implementer must guarantee, from AD-024: the session is bounded in turns, tool calls,
 * spend and time; <b>every turn is a complete {@code RequestPipeline} execution</b>, so governance,
 * routing, authentication, the provider call, streaming and audit all run again, per turn, with no
 * reuse of a previous turn's authorization; taint is tracked within the session and returned;
 * session cost is reported; and the session terminates.
 *
 * <p><b>Implementation status.</b> C13 does not exist in this repository — AD-024 is Proposed and
 * was never built. This port therefore has no production adapter today, which is recorded as a
 * blocker rather than papered over. A single-turn adapter over the existing pipeline satisfies the
 * contract for plans whose steps are one turn each, and is the degenerate case AD-025 §14
 * anticipates.
 */
public interface ToolSessionPort {

  /**
   * What a step asks a session to do.
   *
   * @param sessionRef an opaque, caller-derived reference recorded before the session opens, so an
   *     interrupted step can later ask what became of it (AD-025 §35.2)
   * @param instruction the step's instruction text
   * @param inputs the recorded prior results this step feeds in; the only route by which an earlier
   *     result — including a tool artifact — reaches a model, and it goes through a governed turn
   * @param grantedCapabilities the capabilities the session may use, already narrowed to a subset
   *     of the run's ceiling
   * @param tenant the tenant scope
   * @param correlationId the run's correlation
   * @param budgetMicros the spend allotment carved from the run's remainder
   * @param deadline the session deadline, never later than the run's remaining wall-clock
   * @param inboundTainted whether the run already carries untrusted content, so the session can
   *     apply the Rule of Two with the run's history in view rather than only its own
   */
  record SessionRequest(
      String sessionRef,
      String instruction,
      List<String> inputs,
      Set<String> grantedCapabilities,
      TenantScope tenant,
      CorrelationId correlationId,
      long budgetMicros,
      Duration deadline,
      boolean inboundTainted) {

    /**
     * Validates the request.
     *
     * @param sessionRef the opaque session reference
     * @param instruction the instruction text
     * @param inputs the prior results fed in
     * @param grantedCapabilities the granted capabilities
     * @param tenant the tenant scope
     * @param correlationId the correlation
     * @param budgetMicros the spend allotment
     * @param deadline the session deadline
     * @param inboundTainted the inbound taint flag
     */
    public SessionRequest {
      Preconditions.requireNonBlank(sessionRef, "sessionRef");
      Preconditions.requireNonNull(instruction, "instruction");
      inputs = inputs == null ? List.of() : List.copyOf(inputs);
      grantedCapabilities =
          Set.copyOf(Preconditions.requireNonNull(grantedCapabilities, "grantedCapabilities"));
      Preconditions.requireNonNull(tenant, "tenant");
      Preconditions.requireNonNull(correlationId, "correlationId");
      Preconditions.requireNonNegative(budgetMicros, "budgetMicros");
      Preconditions.requireNonNull(deadline, "deadline");
    }
  }

  /** What a session produced. */
  sealed interface SessionOutcome permits Completed, Failed, Cancelled {

    /**
     * Returns what the session cost.
     *
     * @return spend in micros, as measured by the metering and cost engines
     */
    long costMicros();

    /**
     * Reports whether {@link #costMicros()} is a measured figure.
     *
     * <p><b>Unknown is not zero.</b> An implementer that cannot obtain a priced figure must say so
     * here rather than report {@code 0}, because a zero would let a run spend without ever touching
     * its budget — the budget would still be checked, and would always pass. When this is false the
     * runtime charges the step's full allotment instead, which over-charges rather than
     * under-charges and keeps the bound enforceable.
     *
     * @return true when the cost was measured, false when it could not be established
     */
    boolean costKnown();

    /**
     * Returns how many complete pipeline executions the session performed.
     *
     * <p>Reported so the Agent Runtime can assert AGT-9 rather than assume it: a step that produced
     * a model answer having performed zero pipeline executions would mean the layer below found a
     * shortcut, and that is worth failing loudly over.
     *
     * @return the turn count, at least one for a completed session
     */
    int pipelineExecutions();
  }

  /**
   * A session that finished and produced an answer.
   *
   * @param output the model's answer
   * @param costMicros the measured spend, meaningful only when {@code costKnown}
   * @param costKnown whether the spend was measured
   * @param pipelineExecutions the turn count
   * @param tainted whether the session touched untrusted content
   */
  record Completed(
      String output, long costMicros, boolean costKnown, int pipelineExecutions, boolean tainted)
      implements SessionOutcome {

    /**
     * Validates the outcome.
     *
     * @param output the model's answer
     * @param costMicros the measured spend
     * @param costKnown whether the spend was measured
     * @param pipelineExecutions the turn count
     * @param tainted whether the session touched untrusted content
     */
    public Completed {
      Preconditions.requireNonNull(output, "output");
      Preconditions.requireNonNegative(costMicros, "costMicros");
      if (pipelineExecutions < 1) {
        throw new IllegalArgumentException(
            "a completed session must have performed at least one pipeline execution");
      }
    }
  }

  /**
   * A session that failed.
   *
   * @param failure the classified failure
   * @param reason the explanation
   * @param costMicros the spend incurred
   * @param costKnown whether the spend was measured
   * @param pipelineExecutions the turn count, possibly zero if it failed before the first turn
   */
  record Failed(
      FailureClass failure,
      String reason,
      long costMicros,
      boolean costKnown,
      int pipelineExecutions)
      implements SessionOutcome {

    /**
     * Validates the outcome.
     *
     * @param failure the classified failure
     * @param reason the explanation
     * @param costMicros the spend incurred
     * @param costKnown whether the spend was measured
     * @param pipelineExecutions the turn count
     */
    public Failed {
      Preconditions.requireNonNull(failure, "failure");
      Preconditions.requireNonBlank(reason, "reason");
      Preconditions.requireNonNegative(costMicros, "costMicros");
      if (pipelineExecutions < 0) {
        throw new IllegalArgumentException("pipelineExecutions must be non-negative");
      }
    }
  }

  /**
   * A session cancelled before it finished.
   *
   * @param costMicros the spend incurred before cancelling
   * @param costKnown whether the spend was measured
   * @param pipelineExecutions the turn count
   */
  record Cancelled(long costMicros, boolean costKnown, int pipelineExecutions)
      implements SessionOutcome {

    /**
     * Validates the outcome.
     *
     * @param costMicros the spend incurred before cancelling
     * @param costKnown whether the spend was measured
     * @param pipelineExecutions the turn count
     */
    public Cancelled {
      Preconditions.requireNonNegative(costMicros, "costMicros");
      if (pipelineExecutions < 0) {
        throw new IllegalArgumentException("pipelineExecutions must be non-negative");
      }
    }
  }

  /**
   * Opens exactly one session and runs it to its terminal outcome.
   *
   * @param request what the step asks for
   * @return what the session produced
   */
  SessionOutcome openSession(SessionRequest request);

  /**
   * Cancels an in-flight session.
   *
   * <p>Best-effort by nature: the session may already have finished. It must be safe to call for a
   * reference the implementer has never heard of, because a recovering node will do exactly that.
   *
   * @param sessionRef the reference recorded when the step was scheduled
   */
  void cancelSession(String sessionRef);

  /**
   * Asks what became of a session, for interrupted-step resolution (AD-025 §35.2).
   *
   * <p>The first and best of the three resolutions: if the layer below has a terminal record for
   * the session, the recovering node adopts it and the step costs nothing to recover. Only when
   * this returns empty does recovery fall back to treating the step as failed.
   *
   * @param sessionRef the reference recorded when the step was scheduled
   * @return the session's outcome if the implementer knows it, empty when it has no record
   */
  java.util.Optional<SessionOutcome> lookup(String sessionRef);
}

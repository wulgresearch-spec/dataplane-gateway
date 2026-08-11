package io.reliabilityai.gateway.dataplane.agent.application;

import io.reliabilityai.gateway.common.Preconditions;
import io.reliabilityai.gateway.dataplane.agent.api.AgentToolPort;
import io.reliabilityai.gateway.dataplane.agent.api.FailureClass;
import io.reliabilityai.gateway.dataplane.agent.api.RunSnapshot;
import io.reliabilityai.gateway.dataplane.agent.api.Step;
import io.reliabilityai.gateway.dataplane.agent.api.StepId;
import io.reliabilityai.gateway.dataplane.agent.api.StepResult;
import io.reliabilityai.gateway.dataplane.agent.api.ToolResultArtifact;
import io.reliabilityai.gateway.dataplane.agent.api.ToolSessionPort;
import io.reliabilityai.gateway.dataplane.agent.domain.Digest;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Turns a plan step into a call on the seams below, and the answer back into a {@link StepResult}.
 *
 * <p>Everything security-relevant about crossing that boundary is concentrated here, so there is
 * one place to read and one place to test:
 *
 * <ul>
 *   <li><b>Capability narrowing.</b> A session receives the intersection of the run's ceiling and
 *       the step's declaration, never the step's declaration alone (AD-025 P-1).
 *   <li><b>Budget carving.</b> A step's allotment comes out of the run's spendable remainder, so a
 *       step cannot be allotted money the run does not have.
 *   <li><b>Taint carry-across.</b> The run's accumulated taint is handed to the session, which is
 *       what lets the Rule of Two be applied over the whole run rather than one session at a time
 *       (AD-025 §60.2) — the property C13 structurally cannot provide, because it cannot see across
 *       sessions.
 *   <li><b>Artifact interposition.</b> A tool's output becomes a recorded artifact, never an
 *       argument to a model (AD-025 AGT-9).
 * </ul>
 *
 * <p>This class performs no policy evaluation of its own. Governance runs inside every pipeline
 * execution the session performs, as it always has; adding an agent-specific check here would
 * create a second policy implementation that would eventually disagree with the first.
 */
public final class ToolSessionRunner {

  private final ToolSessionPort sessions;
  private final AgentToolPort tools;

  /**
   * Creates a runner.
   *
   * @param sessions the C13 seam
   * @param tools the tool-execution seam
   */
  public ToolSessionRunner(final ToolSessionPort sessions, final AgentToolPort tools) {
    this.sessions = Preconditions.requireNonNull(sessions, "sessions");
    this.tools = Preconditions.requireNonNull(tools, "tools");
  }

  /**
   * Runs a model step as exactly one session.
   *
   * @param stepId the step identity
   * @param step the plan step
   * @param snapshot the run's folded state
   * @param sessionRef the reference recorded before this call, so an interruption can be resolved
   * @param now the instant to evaluate the run's remaining wall-clock against
   * @return the step result
   */
  public StepResult runPipelineStep(
      final StepId stepId,
      final Step.Pipeline step,
      final RunSnapshot snapshot,
      final String sessionRef,
      final java.time.Instant now) {

    Preconditions.requireNonNull(stepId, "stepId");
    Preconditions.requireNonNull(step, "step");
    Preconditions.requireNonNull(snapshot, "snapshot");

    // A step asking for something outside the run's ceiling gets a narrowed grant rather than a
    // failure: the ceiling is the authority, and the session simply cannot use what it was not
    // given.
    final Set<String> granted = snapshot.security().grantFor(step.requiredCapabilities());
    final long allotment = Math.min(step.budgetMicros(), snapshot.budget().spendableMicros());

    final ToolSessionPort.SessionRequest request =
        new ToolSessionPort.SessionRequest(
            sessionRef,
            step.instruction(),
            resolveInputs(step.inputRefs(), snapshot),
            granted,
            snapshot.security().tenant(),
            snapshot.security().correlationId(),
            allotment,
            capDeadline(step.deadline(), snapshot, now),
            snapshot.tainted());

    final ToolSessionPort.SessionOutcome outcome;
    try {
      outcome = sessions.openSession(request);
    } catch (final RuntimeException failure) {
      // A seam that throws is a transient failure of the layer below, not a crash of this run. It
      // is
      // classified rather than propagated so the supervisor gets to decide, and so the step's
      // failure
      // is recorded instead of the run stalling with an unresolved scheduled tail.
      return new StepResult.Failed(stepId, FailureClass.STEP_TRANSIENT, describe(failure), 0L);
    }

    final long charged = charge(outcome, allotment);
    return switch (outcome) {
      case ToolSessionPort.Completed completed -> {
        final String value = RunExecutor.bound(completed.output());
        yield new StepResult.Succeeded(
            stepId,
            value,
            Digest.of(completed.output()),
            Optional.empty(),
            charged,
            completed.tainted());
      }
      case ToolSessionPort.Failed failed ->
          new StepResult.Failed(stepId, failed.failure(), failed.reason(), charged);
      case ToolSessionPort.Cancelled ignored ->
          new StepResult.Cancelled(
              stepId,
              io.reliabilityai.gateway.dataplane.agent.api.CancellationCause.SUPERVISOR,
              charged);
    };
  }

  /**
   * Runs a tool step and turns its output into an artifact.
   *
   * @param stepId the step identity
   * @param step the plan step
   * @param snapshot the run's folded state
   * @param invocationRef the reference recorded before this call
   * @param now the instant to evaluate the run's remaining wall-clock against
   * @return the step result, carrying the artifact when the tool produced one
   */
  public StepResult runToolStep(
      final StepId stepId,
      final Step.Plugin step,
      final RunSnapshot snapshot,
      final String invocationRef,
      final java.time.Instant now) {

    Preconditions.requireNonNull(stepId, "stepId");
    Preconditions.requireNonNull(step, "step");
    Preconditions.requireNonNull(snapshot, "snapshot");

    // A capability outside the ceiling is refused outright rather than narrowed away: unlike a
    // model
    // step, which can still do useful work with fewer capabilities, a tool step with its single
    // capability removed has nothing left to do, and running it would waste a session to fail.
    if (!snapshot.security().permits(step.capability())) {
      return new StepResult.Failed(
          stepId,
          FailureClass.STEP_DENIED,
          "capability '" + step.capability() + "' is outside the run's ceiling",
          0L);
    }

    final AgentToolPort.ToolCall call =
        new AgentToolPort.ToolCall(
            invocationRef,
            step.capability(),
            renderArguments(step, snapshot),
            snapshot.security().tenant(),
            snapshot.security().correlationId(),
            Math.min(step.budgetMicros(), snapshot.budget().spendableMicros()),
            capDeadline(step.deadline(), snapshot, now));

    final AgentToolPort.ToolOutcome outcome;
    try {
      outcome = tools.invoke(call);
    } catch (final RuntimeException failure) {
      return new StepResult.Failed(stepId, FailureClass.TOOL_FAILURE, describe(failure), 0L);
    }

    return switch (outcome) {
      case AgentToolPort.Produced produced -> {
        final ToolResultArtifact artifact =
            RunExecutor.artifactOf(
                stepId, step.capability(), produced.content(), produced.trusted());
        yield new StepResult.Succeeded(
            stepId,
            artifact.content(),
            artifact.contentDigest(),
            Optional.of(artifact),
            produced.costMicros(),
            artifact.tainted());
      }
      case AgentToolPort.Rejected rejected ->
          new StepResult.Failed(
              stepId, rejected.failure(), rejected.reason(), rejected.costMicros());
      case AgentToolPort.Aborted aborted ->
          new StepResult.Cancelled(
              stepId,
              io.reliabilityai.gateway.dataplane.agent.api.CancellationCause.PLUGIN_FAILURE,
              aborted.costMicros());
    };
  }

  /**
   * Asks the layer below what became of an interrupted step (AD-025 §35.2, resolution 1).
   *
   * @param sessionRef the reference recorded when the step was scheduled
   * @return the session outcome if the layer below has a terminal record, empty otherwise
   */
  public Optional<ToolSessionPort.SessionOutcome> lookupSession(final String sessionRef) {
    Preconditions.requireNonNull(sessionRef, "sessionRef");
    try {
      return sessions.lookup(sessionRef);
    } catch (final RuntimeException unavailable) {
      // A lookup that fails is indistinguishable from a lookup that found nothing, and the caller's
      // fallback for both is the same: treat the step as failed rather than guess that it
      // succeeded.
      return Optional.empty();
    }
  }

  /**
   * Asks the tool layer what became of an interrupted invocation.
   *
   * @param invocationRef the reference recorded when the step was scheduled
   * @return the tool outcome if known, empty otherwise
   */
  public Optional<AgentToolPort.ToolOutcome> lookupTool(final String invocationRef) {
    Preconditions.requireNonNull(invocationRef, "invocationRef");
    try {
      return tools.lookup(invocationRef);
    } catch (final RuntimeException unavailable) {
      return Optional.empty();
    }
  }

  /**
   * Cancels whatever a scheduled step opened.
   *
   * <p>Both seams are told, because the reference alone does not say which kind of step it was and
   * cancelling something that was never opened must be harmless anyway.
   *
   * @param ref the reference recorded when the step was scheduled
   */
  public void cancelInFlight(final String ref) {
    Preconditions.requireNonNull(ref, "ref");
    try {
      sessions.cancelSession(ref);
    } catch (final RuntimeException ignored) {
      // Best-effort by contract: a cancellation that cannot be delivered must not stop the run
      // being
      // marked cancelled, or an unreachable dependency would keep runs alive indefinitely.
    }
    try {
      tools.cancel(ref);
    } catch (final RuntimeException ignored) {
      // As above.
    }
  }

  /**
   * Decides what to charge the run for a session.
   *
   * <p><b>Unknown is not zero.</b> A session that could not establish a priced figure is charged
   * its full allotment rather than nothing. Substituting zero would make the budget check pass
   * every time — the bound would still be evaluated, and would never bind, which is worse than
   * having no bound at all because it looks enforced. Over-charging ends a run early and visibly;
   * under-charging ends it never.
   *
   * @param outcome what the session reported
   * @param allotment what the step was allowed to spend
   * @return the amount to record against the run's budget
   */
  static long charge(final ToolSessionPort.SessionOutcome outcome, final long allotment) {
    return outcome.costKnown() ? outcome.costMicros() : allotment;
  }

  /** Resolves the recorded values a step names as inputs, skipping any that were never recorded. */
  private static List<String> resolveInputs(final List<String> refs, final RunSnapshot snapshot) {
    final List<String> inputs = new ArrayList<>(refs.size());
    for (final String ref : refs) {
      snapshot.resultOf(ref).ifPresent(inputs::add);
    }
    return List.copyOf(inputs);
  }

  /**
   * Appends any referenced results to the declared arguments, so a tool can act on prior output.
   */
  private static String renderArguments(final Step.Plugin step, final RunSnapshot snapshot) {
    if (step.inputRefs().isEmpty()) {
      return step.arguments();
    }
    final StringBuilder rendered = new StringBuilder(step.arguments());
    for (final String ref : step.inputRefs()) {
      snapshot.resultOf(ref).ifPresent(value -> rendered.append('\n').append(value));
    }
    return rendered.toString();
  }

  /**
   * Caps a step's deadline at the run's remaining wall-clock.
   *
   * <p>Without this a step could be handed a deadline longer than the run itself has left, and the
   * run would blow its bound inside a step nobody could interrupt.
   */
  private static Duration capDeadline(
      final Duration declared, final RunSnapshot snapshot, final java.time.Instant now) {
    final Duration runRemaining = snapshot.timeout().remainingAt(now);
    return declared.compareTo(runRemaining) <= 0 ? declared : runRemaining;
  }

  /** Renders a thrown failure without leaking a stack trace into a durable record. */
  private static String describe(final RuntimeException failure) {
    final String message = failure.getMessage();
    return failure.getClass().getSimpleName() + (message == null ? "" : ": " + message);
  }
}

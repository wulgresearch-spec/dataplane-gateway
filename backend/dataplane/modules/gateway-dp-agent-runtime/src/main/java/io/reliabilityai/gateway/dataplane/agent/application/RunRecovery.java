package io.reliabilityai.gateway.dataplane.agent.application;

import io.reliabilityai.gateway.common.Preconditions;
import io.reliabilityai.gateway.dataplane.agent.api.AgentToolPort;
import io.reliabilityai.gateway.dataplane.agent.api.FailureClass;
import io.reliabilityai.gateway.dataplane.agent.api.RunEvent;
import io.reliabilityai.gateway.dataplane.agent.api.RunHistory;
import io.reliabilityai.gateway.dataplane.agent.api.RunId;
import io.reliabilityai.gateway.dataplane.agent.api.RunRepository;
import io.reliabilityai.gateway.dataplane.agent.api.StepKind;
import io.reliabilityai.gateway.dataplane.agent.api.ToolSessionPort;
import io.reliabilityai.gateway.dataplane.agent.domain.Digest;
import java.time.Instant;
import java.util.Optional;

/**
 * Resolves what became of a step whose executor died mid-flight (AD-025 §35).
 *
 * <p>A history whose tail is {@link RunEvent.StepScheduled} means a node stopped between the two
 * durable writes that bracket a step. The step may have completed, partly completed, or never
 * started, and <b>the history cannot tell us which</b>. That is the fundamental limit of a
 * two-phase record across a non-transactional boundary; no amount of design removes it, and a
 * document that claimed otherwise would be wrong.
 *
 * <p>Three resolutions, in order (AD-025 §35.2):
 *
 * <ol>
 *   <li><b>Ask the layer below.</b> The scheduling event records the session reference, so if C13
 *       or the tool layer holds a terminal record, adopt it. This is the common case, and it costs
 *       no inference and no money — the step's work is recovered rather than repeated.
 *   <li><b>No record: treat as failed.</b> The session never started or died with its node. The
 *       supervisor then decides, under the run's declared policy.
 *   <li><b>Still in flight: wait, then fail.</b> Bounded by the session deadline, which is itself
 *       bounded, so this cannot stall a run indefinitely.
 * </ol>
 *
 * <p><b>What this deliberately does not do is guess.</b> An unresolvable step is never assumed to
 * have succeeded, and a step holding a non-idempotent capability is never silently re-executed: the
 * guarantee is at-least-once for idempotent work and at-most-once for irreversible work, which is
 * weaker than exactly-once and is the strongest honest claim available here.
 */
public final class RunRecovery {

  /** How a resolution was reached, used as a metrics dimension. */
  public enum Resolution {
    /**
     * The layer below held a terminal record and it was adopted. No work repeated, nothing paid.
     */
    ADOPTED,
    /** The layer below had no record, so the step is treated as failed. */
    ASSUMED_FAILED,
    /** The layer below reported the work still in flight past its deadline. */
    ABANDONED_IN_FLIGHT,
    /** The step was pure, so it was simply recomputed. Free, and observable by nobody. */
    RECOMPUTED,
    /** The store could not be reached, so nothing was decided and the run stays as it is. */
    DEFERRED
  }

  private RunRecovery() {
    throw new AssertionError("no instances");
  }

  /**
   * Resolves an interrupted step and records the outcome.
   *
   * @param executor the executor whose store and clock are used to record the resolution
   * @param runId the run
   * @param history the history, whose tail must be a scheduling event
   * @return what the resolution did
   */
  static RunExecutor.Advance resolveInterrupted(
      final RunExecutor executor, final RunId runId, final RunHistory history) {

    Preconditions.requireNonNull(executor, "executor");
    Preconditions.requireNonNull(runId, "runId");
    Preconditions.requireNonNull(history, "history");

    final RunEvent.StepScheduled scheduled =
        history
            .interruptedStep()
            .orElseThrow(
                () -> new IllegalArgumentException("history does not end in a scheduled step"));

    return executor.resolveInterruptedStep(runId, history, scheduled);
  }

  /**
   * Asks the session seam what became of an interrupted model step.
   *
   * @param runner the seam to the layers below
   * @param scheduled the unresolved scheduling event
   * @param now the recording instant
   * @return the event to record and how it was reached
   */
  static Resolved resolveSession(
      final ToolSessionRunner runner, final RunEvent.StepScheduled scheduled, final Instant now) {
    Preconditions.requireNonNull(runner, "runner");
    Preconditions.requireNonNull(scheduled, "scheduled");
    Preconditions.requireNonNull(now, "now");

    final Optional<ToolSessionPort.SessionOutcome> known =
        runner.lookupSession(scheduled.sessionRef());
    if (known.isEmpty()) {
      return new Resolved(
          new RunEvent.StepFailed(
              scheduled.stepId(),
              scheduled.stepName(),
              FailureClass.INTERRUPTED_UNRESOLVED,
              "executor died mid-step and the session layer holds no record",
              0L,
              now),
          Resolution.ASSUMED_FAILED);
    }

    return switch (known.get()) {
      case ToolSessionPort.Completed completed ->
          new Resolved(
              new RunEvent.StepCompleted(
                  scheduled.stepId(),
                  scheduled.stepName(),
                  RunExecutor.bound(completed.output()),
                  Digest.of(completed.output()),
                  completed.costKnown() ? completed.costMicros() : 0L,
                  completed.tainted(),
                  now),
              Resolution.ADOPTED);
      case ToolSessionPort.Failed failed ->
          new Resolved(
              new RunEvent.StepFailed(
                  scheduled.stepId(),
                  scheduled.stepName(),
                  failed.failure(),
                  failed.reason(),
                  failed.costMicros(),
                  now),
              Resolution.ADOPTED);
      case ToolSessionPort.Cancelled cancelled ->
          new Resolved(
              new RunEvent.StepCancelled(
                  scheduled.stepId(),
                  scheduled.stepName(),
                  io.reliabilityai.gateway.dataplane.agent.api.CancellationCause.SUPERVISOR,
                  cancelled.costMicros(),
                  now),
              Resolution.ADOPTED);
    };
  }

  /**
   * Asks the tool seam what became of an interrupted tool step.
   *
   * @param runner the seam to the layers below
   * @param scheduled the unresolved scheduling event
   * @param now the recording instant
   * @return the event to record and how it was reached
   */
  static Resolved resolveTool(
      final ToolSessionRunner runner, final RunEvent.StepScheduled scheduled, final Instant now) {

    final Optional<AgentToolPort.ToolOutcome> known = runner.lookupTool(scheduled.sessionRef());
    if (known.isEmpty()) {
      return new Resolved(
          new RunEvent.StepFailed(
              scheduled.stepId(),
              scheduled.stepName(),
              FailureClass.INTERRUPTED_UNRESOLVED,
              "executor died mid-step and the tool layer holds no record",
              0L,
              now),
          Resolution.ASSUMED_FAILED);
    }

    return switch (known.get()) {
      case AgentToolPort.Produced produced ->
          new Resolved(
              new RunEvent.StepCompleted(
                  scheduled.stepId(),
                  scheduled.stepName(),
                  RunExecutor.bound(produced.content()),
                  Digest.of(produced.content()),
                  produced.costMicros(),
                  !produced.trusted(),
                  now),
              Resolution.ADOPTED);
      case AgentToolPort.Rejected rejected ->
          new Resolved(
              new RunEvent.StepFailed(
                  scheduled.stepId(),
                  scheduled.stepName(),
                  rejected.failure(),
                  rejected.reason(),
                  rejected.costMicros(),
                  now),
              Resolution.ADOPTED);
      case AgentToolPort.Aborted aborted ->
          new Resolved(
              new RunEvent.StepCancelled(
                  scheduled.stepId(),
                  scheduled.stepName(),
                  io.reliabilityai.gateway.dataplane.agent.api.CancellationCause.PLUGIN_FAILURE,
                  aborted.costMicros(),
                  now),
              Resolution.ADOPTED);
    };
  }

  /**
   * Resolves a step kind that performs no outbound I/O.
   *
   * <p>{@code WAIT}, {@code CONDITION} and {@code BRANCH} have no session to ask about, and they
   * are pure — so an interrupted one is simply re-run rather than resolved. This is the only place
   * where re-execution is safe, and it is safe precisely because nothing outside the runtime
   * observed it.
   *
   * @param scheduled the unresolved scheduling event
   * @return true when the step may simply be run again
   */
  static boolean safelyRepeatable(final RunEvent.StepScheduled scheduled) {
    return !scheduled.kind().nonDeterministic();
  }

  /**
   * What a resolution decided.
   *
   * @param event the event to append
   * @param resolution how the decision was reached
   */
  record Resolved(RunEvent event, Resolution resolution) {

    /**
     * Validates the resolution.
     *
     * @param event the event to append
     * @param resolution the resolution path
     */
    Resolved {
      Preconditions.requireNonNull(event, "event");
      Preconditions.requireNonNull(resolution, "resolution");
    }
  }

  /**
   * Lists the runs a node should sweep on startup.
   *
   * <p>The sweep is what makes crash recovery automatic rather than something an operator triggers.
   * It is stateless and idempotent: running it twice resolves nothing twice, because the
   * conditional append makes the second attempt a conflict.
   *
   * @param runs the store
   * @param tenant the tenant to sweep
   * @param limit the most runs to return
   * @return the unfinished runs, oldest first
   */
  public static java.util.List<RunId> sweep(
      final RunRepository runs,
      final io.reliabilityai.gateway.canonical.identity.TenantScope tenant,
      final int limit) {
    Preconditions.requireNonNull(runs, "runs");
    Preconditions.requireNonNull(tenant, "tenant");
    return runs.unfinished(tenant, limit);
  }

  /**
   * Reports whether an interrupted step of this kind can be resolved by asking the layer below.
   *
   * @param kind the step kind
   * @return true for the kinds that opened something the layer below can be asked about
   */
  static boolean resolvableByLookup(final StepKind kind) {
    return kind == StepKind.PIPELINE || kind == StepKind.PLUGIN;
  }
}

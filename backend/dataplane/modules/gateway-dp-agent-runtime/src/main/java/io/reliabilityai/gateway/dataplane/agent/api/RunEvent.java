package io.reliabilityai.gateway.dataplane.agent.api;

import io.reliabilityai.gateway.common.Preconditions;
import java.time.Instant;

/**
 * One durable fact about a run (AD-025 §20).
 *
 * <p><b>The history is the run.</b> There is no separate state table, no checkpoint file and no
 * audit log alongside it — those would be second sources of truth, and a second source of truth is
 * a source of silent disagreement. Run state is <em>folded</em> from these events every time it is
 * needed (AD-025 AGT-15).
 *
 * <p>Events are append-only and immutable. Nothing in this package can modify or delete one; the
 * only removal is a whole-run retention purge, which is the store's business and not an edit.
 *
 * <p><b>Two-phase recording.</b> {@link StepScheduled} is written <em>before</em> a step executes
 * and a terminal step event <em>after</em> (AD-025 AGT-14). A history whose tail is {@code
 * StepScheduled} therefore means an executor died mid-step. That gap is not a modelling flaw — it
 * is the only honest record of a world in which a process can stop between two writes.
 *
 * <p>Every event carries {@code at}, taken from the injected clock at the moment of writing. Replay
 * reads these recorded values; the interpreter never reads a clock of its own (AD-025 §45.2).
 */
public sealed interface RunEvent
    permits RunEvent.RunCreated,
        RunEvent.RunQueued,
        RunEvent.StepScheduled,
        RunEvent.StepCompleted,
        RunEvent.StepFailed,
        RunEvent.StepSkipped,
        RunEvent.StepCancelled,
        RunEvent.ToolArtifactProduced,
        RunEvent.SupervisionApplied,
        RunEvent.BranchTaken,
        RunEvent.RunParked,
        RunEvent.RunResumed,
        RunEvent.RunCheckpointed,
        RunEvent.RunCancelled,
        RunEvent.RunTerminated {

  /**
   * Returns the recorded instant.
   *
   * @return when this fact was written, from the injected clock
   */
  Instant at();

  /**
   * Returns the wire tag used by the durable journal.
   *
   * <p>A stable, explicit string rather than a class name: the journal must stay readable after a
   * refactor, and deriving the tag from a type name would make renaming a class a data migration.
   *
   * @return the tag, never blank
   */
  String tag();

  /**
   * The run exists. Always the first event, exactly once.
   *
   * @param version the pinned plan version and journal format
   * @param bounds the five bounds, fixed for the run's life
   * @param budget the spend grant
   * @param security the immutable security context
   * @param parent the parent run, empty for a root run
   * @param depth the run's depth in the tree; zero for a root run
   * @param at the recorded instant
   */
  record RunCreated(
      RunVersion version,
      RunBounds bounds,
      RunBudget budget,
      RunSecurityContext security,
      java.util.Optional<RunId> parent,
      int depth,
      Instant at)
      implements RunEvent {

    /**
     * Validates the event.
     *
     * @param version the pinned version
     * @param bounds the run bounds
     * @param budget the spend grant
     * @param security the security context
     * @param parent the parent run, if any
     * @param depth the tree depth
     * @param at the recorded instant
     */
    public RunCreated {
      Preconditions.requireNonNull(version, "version");
      Preconditions.requireNonNull(bounds, "bounds");
      Preconditions.requireNonNull(budget, "budget");
      Preconditions.requireNonNull(security, "security");
      Preconditions.requireNonNull(parent, "parent");
      Preconditions.requireNonNull(at, "at");
      if (depth < 0) {
        throw new IllegalArgumentException("depth must be non-negative, was " + depth);
      }
    }

    @Override
    public String tag() {
      return "run.created";
    }
  }

  /**
   * The run is claimable by any executor.
   *
   * @param at the recorded instant
   */
  record RunQueued(Instant at) implements RunEvent {

    /**
     * Validates the event.
     *
     * @param at the recorded instant
     */
    public RunQueued {
      Preconditions.requireNonNull(at, "at");
    }

    @Override
    public String tag() {
      return "run.queued";
    }
  }

  /**
   * A step is about to execute. Written before the work, which is what makes an interruption
   * visible.
   *
   * @param stepId the derived step identity
   * @param planStepIndex the plan position
   * @param stepName the plan-local step name
   * @param kind the step kind
   * @param attempt the 1-based attempt number
   * @param sessionRef an opaque reference to the session or invocation this step opened, used by
   *     interrupted-step resolution to ask the layer below what actually happened (AD-025 §35.2)
   * @param at the recorded instant
   */
  record StepScheduled(
      StepId stepId,
      int planStepIndex,
      String stepName,
      StepKind kind,
      int attempt,
      String sessionRef,
      Instant at)
      implements RunEvent {

    /**
     * Validates the event.
     *
     * @param stepId the step identity
     * @param planStepIndex the plan position
     * @param stepName the step name
     * @param kind the step kind
     * @param attempt the attempt number
     * @param sessionRef the session reference
     * @param at the recorded instant
     */
    public StepScheduled {
      Preconditions.requireNonNull(stepId, "stepId");
      Preconditions.requireNonBlank(stepName, "stepName");
      Preconditions.requireNonNull(kind, "kind");
      Preconditions.requireNonNull(sessionRef, "sessionRef");
      Preconditions.requireNonNull(at, "at");
      if (planStepIndex < 0) {
        throw new IllegalArgumentException("planStepIndex must be non-negative");
      }
      if (attempt < 1) {
        throw new IllegalArgumentException("attempt must be >= 1, was " + attempt);
      }
    }

    @Override
    public String tag() {
      return "step.scheduled";
    }
  }

  /**
   * A step executed and produced a value.
   *
   * @param stepId the step identity
   * @param stepName the plan-local step name, denormalised so a reader needs no plan to make sense
   *     of the history
   * @param value the recorded result
   * @param valueDigest the result digest
   * @param costMicros the reported spend
   * @param tainted whether the result carries untrusted content
   * @param at the recorded instant
   */
  record StepCompleted(
      StepId stepId,
      String stepName,
      String value,
      String valueDigest,
      long costMicros,
      boolean tainted,
      Instant at)
      implements RunEvent {

    /**
     * Validates the event.
     *
     * @param stepId the step identity
     * @param stepName the step name
     * @param value the recorded result
     * @param valueDigest the result digest
     * @param costMicros the reported spend
     * @param tainted the taint flag
     * @param at the recorded instant
     */
    public StepCompleted {
      Preconditions.requireNonNull(stepId, "stepId");
      Preconditions.requireNonBlank(stepName, "stepName");
      Preconditions.requireNonNull(value, "value");
      Preconditions.requireNonBlank(valueDigest, "valueDigest");
      Preconditions.requireNonNegative(costMicros, "costMicros");
      Preconditions.requireNonNull(at, "at");
    }

    @Override
    public String tag() {
      return "step.completed";
    }
  }

  /**
   * A step executed and failed.
   *
   * @param stepId the step identity
   * @param stepName the plan-local step name
   * @param failure the classified failure
   * @param reason the operator-facing explanation
   * @param costMicros the spend incurred before failing
   * @param at the recorded instant
   */
  record StepFailed(
      StepId stepId,
      String stepName,
      FailureClass failure,
      String reason,
      long costMicros,
      Instant at)
      implements RunEvent {

    /**
     * Validates the event.
     *
     * @param stepId the step identity
     * @param stepName the step name
     * @param failure the classified failure
     * @param reason the explanation
     * @param costMicros the spend incurred
     * @param at the recorded instant
     */
    public StepFailed {
      Preconditions.requireNonNull(stepId, "stepId");
      Preconditions.requireNonBlank(stepName, "stepName");
      Preconditions.requireNonNull(failure, "failure");
      Preconditions.requireNonBlank(reason, "reason");
      Preconditions.requireNonNegative(costMicros, "costMicros");
      Preconditions.requireNonNull(at, "at");
    }

    @Override
    public String tag() {
      return "step.failed";
    }
  }

  /**
   * Supervision absorbed a step failure and the plan continued.
   *
   * @param stepId the step identity
   * @param stepName the plan-local step name
   * @param failure the absorbed failure
   * @param at the recorded instant
   */
  record StepSkipped(StepId stepId, String stepName, FailureClass failure, Instant at)
      implements RunEvent {

    /**
     * Validates the event.
     *
     * @param stepId the step identity
     * @param stepName the step name
     * @param failure the absorbed failure
     * @param at the recorded instant
     */
    public StepSkipped {
      Preconditions.requireNonNull(stepId, "stepId");
      Preconditions.requireNonBlank(stepName, "stepName");
      Preconditions.requireNonNull(failure, "failure");
      Preconditions.requireNonNull(at, "at");
    }

    @Override
    public String tag() {
      return "step.skipped";
    }
  }

  /**
   * A step was cancelled.
   *
   * @param stepId the step identity
   * @param stepName the plan-local step name
   * @param cause why it was cancelled
   * @param costMicros the spend incurred before cancelling
   * @param at the recorded instant
   */
  record StepCancelled(
      StepId stepId, String stepName, CancellationCause cause, long costMicros, Instant at)
      implements RunEvent {

    /**
     * Validates the event.
     *
     * @param stepId the step identity
     * @param stepName the step name
     * @param cause the cancellation cause
     * @param costMicros the spend incurred
     * @param at the recorded instant
     */
    public StepCancelled {
      Preconditions.requireNonNull(stepId, "stepId");
      Preconditions.requireNonBlank(stepName, "stepName");
      Preconditions.requireNonNull(cause, "cause");
      Preconditions.requireNonNegative(costMicros, "costMicros");
      Preconditions.requireNonNull(at, "at");
    }

    @Override
    public String tag() {
      return "step.cancelled";
    }
  }

  /**
   * A tool step produced an artifact.
   *
   * <p>Recorded separately from {@link StepCompleted} so that the audit question "what did a tool
   * put into this run, and did it ever reach a model?" is answerable by scanning one event type.
   *
   * @param stepId the producing step
   * @param artifactId the artifact identity
   * @param capability the capability exercised
   * @param contentDigest the artifact digest
   * @param tainted whether the artifact is untrusted content
   * @param at the recorded instant
   */
  record ToolArtifactProduced(
      StepId stepId,
      String artifactId,
      String capability,
      String contentDigest,
      boolean tainted,
      Instant at)
      implements RunEvent {

    /**
     * Validates the event.
     *
     * @param stepId the producing step
     * @param artifactId the artifact identity
     * @param capability the exercised capability
     * @param contentDigest the artifact digest
     * @param tainted the taint flag
     * @param at the recorded instant
     */
    public ToolArtifactProduced {
      Preconditions.requireNonNull(stepId, "stepId");
      Preconditions.requireNonBlank(artifactId, "artifactId");
      Preconditions.requireNonBlank(capability, "capability");
      Preconditions.requireNonBlank(contentDigest, "contentDigest");
      Preconditions.requireNonNull(at, "at");
    }

    @Override
    public String tag() {
      return "tool.artifact";
    }
  }

  /**
   * The supervisor made a decision. Written for every decision, including the ones that do nothing.
   *
   * @param stepId the step the decision was about
   * @param strategy the policy's declared strategy
   * @param decision the decision's label
   * @param restartCount how many restarts had occurred inside the intensity window
   * @param at the recorded instant
   */
  record SupervisionApplied(
      StepId stepId, SupervisionStrategy strategy, String decision, int restartCount, Instant at)
      implements RunEvent {

    /**
     * Validates the event.
     *
     * @param stepId the step identity
     * @param strategy the declared strategy
     * @param decision the decision label
     * @param restartCount the windowed restart count
     * @param at the recorded instant
     */
    public SupervisionApplied {
      Preconditions.requireNonNull(stepId, "stepId");
      Preconditions.requireNonNull(strategy, "strategy");
      Preconditions.requireNonBlank(decision, "decision");
      Preconditions.requireNonNull(at, "at");
      if (restartCount < 0) {
        throw new IllegalArgumentException("restartCount must be non-negative");
      }
    }

    @Override
    public String tag() {
      return "supervision.applied";
    }
  }

  /**
   * A branch predicate was evaluated and a target chosen.
   *
   * @param stepId the branch step
   * @param stepName the plan-local step name
   * @param outcome the predicate's value
   * @param targetStep the chosen target
   * @param at the recorded instant
   */
  record BranchTaken(StepId stepId, String stepName, boolean outcome, String targetStep, Instant at)
      implements RunEvent {

    /**
     * Validates the event.
     *
     * @param stepId the branch step
     * @param stepName the step name
     * @param outcome the predicate value
     * @param targetStep the chosen target
     * @param at the recorded instant
     */
    public BranchTaken {
      Preconditions.requireNonNull(stepId, "stepId");
      Preconditions.requireNonBlank(stepName, "stepName");
      Preconditions.requireNonBlank(targetStep, "targetStep");
      Preconditions.requireNonNull(at, "at");
    }

    @Override
    public String tag() {
      return "branch.taken";
    }
  }

  /**
   * The run parked until a recorded instant, holding nothing.
   *
   * @param wakeAt when the run becomes claimable again
   * @param at the recorded instant
   */
  record RunParked(Instant wakeAt, Instant at) implements RunEvent {

    /**
     * Validates the event.
     *
     * @param wakeAt the wake instant
     * @param at the recorded instant
     */
    public RunParked {
      Preconditions.requireNonNull(wakeAt, "wakeAt");
      Preconditions.requireNonNull(at, "at");
    }

    @Override
    public String tag() {
      return "run.parked";
    }
  }

  /**
   * The run left a parked state. Carries the parked duration so the wall-clock bound can exclude
   * it.
   *
   * @param parkedNanos how long the run was parked
   * @param at the recorded instant
   */
  record RunResumed(long parkedNanos, Instant at) implements RunEvent {

    /**
     * Validates the event.
     *
     * @param parkedNanos the parked duration
     * @param at the recorded instant
     */
    public RunResumed {
      Preconditions.requireNonNegative(parkedNanos, "parkedNanos");
      Preconditions.requireNonNull(at, "at");
    }

    @Override
    public String tag() {
      return "run.resumed";
    }
  }

  /**
   * A step boundary was reached.
   *
   * <p>Redundant by construction — the boundary is already implied by the terminal step event — and
   * kept anyway, because an explicit marker makes a truncated journal's last good position readable
   * without folding the whole history.
   *
   * @param historyOffset the durable event count at this boundary
   * @param at the recorded instant
   */
  record RunCheckpointed(long historyOffset, Instant at) implements RunEvent {

    /**
     * Validates the event.
     *
     * @param historyOffset the durable event count
     * @param at the recorded instant
     */
    public RunCheckpointed {
      Preconditions.requireNonNegative(historyOffset, "historyOffset");
      Preconditions.requireNonNull(at, "at");
    }

    @Override
    public String tag() {
      return "run.checkpointed";
    }
  }

  /**
   * A cancellation was accepted. Every cancellation becomes one of these, whatever its source.
   *
   * @param cause why the run is being cancelled
   * @param reason the operator-facing explanation
   * @param at the recorded instant
   */
  record RunCancelled(CancellationCause cause, String reason, Instant at) implements RunEvent {

    /**
     * Validates the event.
     *
     * @param cause the cancellation cause
     * @param reason the explanation
     * @param at the recorded instant
     */
    public RunCancelled {
      Preconditions.requireNonNull(cause, "cause");
      Preconditions.requireNonBlank(reason, "reason");
      Preconditions.requireNonNull(at, "at");
    }

    @Override
    public String tag() {
      return "run.cancelled";
    }
  }

  /**
   * The run ended. Always the last event, exactly once.
   *
   * @param reason the closed-set terminal reason
   * @param state the absorbing state, always {@code reason.state()}
   * @param stepsExecuted how many steps the execution graph contained
   * @param totalCostMicros what the run cost in total
   * @param at the recorded instant
   */
  record RunTerminated(
      TerminalReason reason, RunState state, int stepsExecuted, long totalCostMicros, Instant at)
      implements RunEvent {

    /**
     * Validates the event, including that the state matches the reason.
     *
     * @param reason the terminal reason
     * @param state the absorbing state
     * @param stepsExecuted the executed step count
     * @param totalCostMicros the total spend
     * @param at the recorded instant
     */
    public RunTerminated {
      Preconditions.requireNonNull(reason, "reason");
      Preconditions.requireNonNull(state, "state");
      Preconditions.requireNonNull(at, "at");
      Preconditions.requireNonNegative(totalCostMicros, "totalCostMicros");
      if (stepsExecuted < 0) {
        throw new IllegalArgumentException("stepsExecuted must be non-negative");
      }
      // The reason fixes the state. Allowing them to disagree would mean two code paths could
      // record
      // the same ending under different states, and no query could then be trusted.
      if (reason.state() != state) {
        throw new IllegalArgumentException(
            "reason " + reason + " requires state " + reason.state() + ", got " + state);
      }
    }

    /**
     * Creates a termination event with the state derived from the reason.
     *
     * @param reason the terminal reason
     * @param stepsExecuted the executed step count
     * @param totalCostMicros the total spend
     * @param at the recorded instant
     * @return the event
     */
    public static RunTerminated of(
        final TerminalReason reason,
        final int stepsExecuted,
        final long totalCostMicros,
        final Instant at) {
      return new RunTerminated(reason, reason.state(), stepsExecuted, totalCostMicros, at);
    }

    @Override
    public String tag() {
      return "run.terminated";
    }
  }
}

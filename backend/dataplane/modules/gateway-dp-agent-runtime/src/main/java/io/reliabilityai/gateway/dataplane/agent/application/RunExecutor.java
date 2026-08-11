package io.reliabilityai.gateway.dataplane.agent.application;

import io.reliabilityai.gateway.common.Preconditions;
import io.reliabilityai.gateway.dataplane.agent.api.AgentMetricsPort;
import io.reliabilityai.gateway.dataplane.agent.api.ConditionOperator;
import io.reliabilityai.gateway.dataplane.agent.api.FailureClass;
import io.reliabilityai.gateway.dataplane.agent.api.Plan;
import io.reliabilityai.gateway.dataplane.agent.api.PlanRepository;
import io.reliabilityai.gateway.dataplane.agent.api.RunAuditPort;
import io.reliabilityai.gateway.dataplane.agent.api.RunEvent;
import io.reliabilityai.gateway.dataplane.agent.api.RunHistory;
import io.reliabilityai.gateway.dataplane.agent.api.RunId;
import io.reliabilityai.gateway.dataplane.agent.api.RunRepository;
import io.reliabilityai.gateway.dataplane.agent.api.RunRepository.AppendResult;
import io.reliabilityai.gateway.dataplane.agent.api.RunSnapshot;
import io.reliabilityai.gateway.dataplane.agent.api.RunState;
import io.reliabilityai.gateway.dataplane.agent.api.RunStoreUnavailableException;
import io.reliabilityai.gateway.dataplane.agent.api.Step;
import io.reliabilityai.gateway.dataplane.agent.api.StepId;
import io.reliabilityai.gateway.dataplane.agent.api.StepKind;
import io.reliabilityai.gateway.dataplane.agent.api.StepResult;
import io.reliabilityai.gateway.dataplane.agent.api.StepStatus;
import io.reliabilityai.gateway.dataplane.agent.api.SupervisorDecision;
import io.reliabilityai.gateway.dataplane.agent.api.TerminalReason;
import io.reliabilityai.gateway.dataplane.agent.api.ToolResultArtifact;
import io.reliabilityai.gateway.dataplane.agent.domain.BoundsEvaluator;
import io.reliabilityai.gateway.dataplane.agent.domain.Digest;
import io.reliabilityai.gateway.dataplane.agent.domain.ReplayEngine;
import io.reliabilityai.gateway.ports.ClockPort;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Advances one run by one step, then yields (AD-025 §18.2, §25).
 *
 * <p><b>Stateless and interchangeable.</b> It holds no run state between calls: it loads the
 * history, folds it, decides, executes one step, records the outcome and returns. A long run may be
 * advanced by fifty different nodes, and killing this process mid-step loses nothing that another
 * node cannot recover. That is a deliberate divergence from every agent framework studied, all of
 * which hold a run in one process for its lifetime.
 *
 * <p>The shape of {@link #advance} is fixed by AD-025 §25 and is the most important control-flow
 * contract in the runtime:
 *
 * <pre>
 *   LOAD    history                      -- the only authority on what has happened
 *   REPLAY  fold it                      -- deterministic, no I/O, no clock, no provider calls
 *   DECIDE  the next step                -- deterministic
 *   ADMIT   check the five bounds        -- may terminate the run instead
 *   RECORD  StepScheduled                -- durable, BEFORE the work
 *   EXECUTE one session or one tool      -- the only I/O in the whole method
 *   RECORD  StepCompleted / StepFailed   -- durable, AFTER the work
 *   YIELD   any node may take it from here
 * </pre>
 *
 * <p>The two durable writes bracketing the execution are the whole of crash recovery: a history
 * whose tail is {@code StepScheduled} is a step that was interrupted, and AD-025 §35.2 says how to
 * resolve it.
 */
public final class RunExecutor {

  /** What one call to {@link #advance} did. */
  public sealed interface Advance
      permits Advance.Advanced,
          Advance.Parked,
          Advance.Terminated,
          Advance.Contended,
          Advance.Stalled,
          Advance.Idle {

    /**
     * A step ran and the run continues.
     *
     * @param stepId the step that ran
     * @param status what it produced
     * @param state the run's state afterwards
     */
    record Advanced(StepId stepId, StepStatus status, RunState state) implements Advance {}

    /**
     * The run parked until an instant, holding nothing.
     *
     * @param until when it becomes claimable again
     */
    record Parked(Instant until) implements Advance {}

    /**
     * The run ended.
     *
     * @param reason the closed-set terminal reason
     */
    record Terminated(TerminalReason reason) implements Advance {}

    /**
     * Another executor won the append. This node must abandon the step, not overwrite it.
     *
     * @param actualOffset the offset the store actually holds
     */
    record Contended(long actualOffset) implements Advance {}

    /**
     * The store could not be reached, so nothing was recorded and nothing was executed.
     *
     * @param reason a short explanation
     */
    record Stalled(String reason) implements Advance {}

    /** Nothing to do: the run is terminal, unknown, or parked with time still to run. */
    record Idle() implements Advance {}
  }

  private final RunRepository runs;
  private final PlanRepository plans;
  private final ToolSessionRunner sessions;
  private final ClockPort clock;
  private final AgentMetricsPort metrics;
  private final RunAuditPort audit;

  /** How much of a step's output is recorded verbatim; the rest is represented by its digest. */
  private static final int MAX_RECORDED_VALUE = 8192;

  /**
   * Creates an executor.
   *
   * @param runs the durable run store
   * @param plans the pinned-plan resolver
   * @param sessions the seam to sessions and tools
   * @param clock the injected clock; the only source of time in this class, and never read by the
   *     replay fold
   * @param metrics where operational signals go
   * @param audit where recorded events are mirrored
   */
  public RunExecutor(
      final RunRepository runs,
      final PlanRepository plans,
      final ToolSessionRunner sessions,
      final ClockPort clock,
      final AgentMetricsPort metrics,
      final RunAuditPort audit) {
    this.runs = Preconditions.requireNonNull(runs, "runs");
    this.plans = Preconditions.requireNonNull(plans, "plans");
    this.sessions = Preconditions.requireNonNull(sessions, "sessions");
    this.clock = Preconditions.requireNonNull(clock, "clock");
    this.metrics = Preconditions.requireNonNull(metrics, "metrics");
    this.audit = Preconditions.requireNonNull(audit, "audit");
  }

  /**
   * Advances one run by at most one step.
   *
   * @param runId the run to advance
   * @return what happened
   */
  public Advance advance(final RunId runId) {
    Preconditions.requireNonNull(runId, "runId");

    final RunHistory history;
    try {
      final Optional<RunHistory> loaded = runs.load(runId);
      if (loaded.isEmpty()) {
        return new Advance.Idle();
      }
      history = loaded.get();
    } catch (final RunStoreUnavailableException unavailable) {
      metrics.storeUnavailable("load");
      return new Advance.Stalled(unavailable.getMessage());
    }

    if (history.terminated()) {
      return new Advance.Idle();
    }

    // An interrupted tail must be resolved before anything else: the run's real position is not
    // what
    // the fold says until we know whether the scheduled step actually happened.
    if (history.endsInterrupted()) {
      return RunRecovery.resolveInterrupted(this, runId, history);
    }

    final RunSnapshot snapshot;
    final Plan plan;
    try {
      final Optional<Plan> pinned = plans.resolve(snapshotVersion(history));
      if (pinned.isEmpty()) {
        return terminate(
            runId,
            history,
            SupervisorDecision.Terminate.failure(
                FailureClass.PLAN_INVALID, "pinned plan version is no longer retained"),
            0,
            0L);
      }
      plan = pinned.get();
      snapshot = ReplayEngine.fold(plan, history);
    } catch (final RunStoreUnavailableException unavailable) {
      metrics.storeUnavailable("plan");
      return new Advance.Stalled(unavailable.getMessage());
    }

    if (snapshot.terminal()) {
      return new Advance.Idle();
    }

    final Instant now = clock.now();

    // A cancellation accepted while the run was parked or queued takes effect at the next boundary.
    if (snapshot.cancellation().isPresent()) {
      final var cancellation = snapshot.cancellation().get();
      return terminate(
          runId,
          history,
          SupervisorDecision.Terminate.failure(FailureClass.CANCELLED, cancellation.reason()),
          snapshot.stepsExecuted(),
          snapshot.budget().consumedMicros());
    }

    // SM-8: a bound terminates a run from any non-terminal state, waiting included.
    final Optional<BoundsEvaluator.Refusal> expired = BoundsEvaluator.checkDeadline(snapshot, now);
    if (expired.isPresent()) {
      metrics.boundRefused(plan.id(), expired.get().bound());
      return terminate(
          runId,
          history,
          Supervisor.onBoundRefusal(expired.get()),
          snapshot.stepsExecuted(),
          snapshot.budget().consumedMicros());
    }

    if (snapshot.state() == RunState.WAITING) {
      if (snapshot.wakeAt().map(now::isBefore).orElse(false)) {
        return new Advance.Idle();
      }
      // Due. Record the resume before doing anything else: the parked interval must be excluded
      // from
      // the wall-clock bound (AD-025 §37), and a run that resumed without saying so would burn its
      // deadline while holding nothing.
      return resume(runId, history, snapshot, now);
    }

    if (planExhausted(plan, snapshot)) {
      return terminate(
          runId,
          history,
          Supervisor.onPlanExhausted(anySkipped(history)),
          snapshot.stepsExecuted(),
          snapshot.budget().consumedMicros());
    }

    final Step step = plan.stepAt(snapshot.cursor().planStepIndex());

    final Optional<BoundsEvaluator.Refusal> refusal = BoundsEvaluator.admit(snapshot, step, now);
    if (refusal.isPresent()) {
      metrics.boundRefused(plan.id(), refusal.get().bound());
      return terminate(
          runId,
          history,
          Supervisor.onBoundRefusal(refusal.get()),
          snapshot.stepsExecuted(),
          snapshot.budget().consumedMicros());
    }

    return runStep(runId, history, plan, snapshot, step, now);
  }

  private Advance runStep(
      final RunId runId,
      final RunHistory history,
      final Plan plan,
      final RunSnapshot snapshot,
      final Step step,
      final Instant now) {

    final StepId stepId = StepId.of(runId, snapshot.cursor().executionIndex());
    final String sessionRef = stepId.value() + "@" + snapshot.cursor().attempt();

    // RECORD before EXECUTE. If this append loses the race, another node is already running this
    // step; abandoning here is what stops the same step being executed twice.
    final AppendResult scheduled =
        runs.append(
            runId,
            history.offset(),
            new RunEvent.StepScheduled(
                stepId,
                snapshot.cursor().planStepIndex(),
                step.name(),
                step.kind(),
                snapshot.cursor().attempt(),
                sessionRef,
                now));
    final Advance conflict = rejectIfNotAppended(runId, scheduled, history.offset());
    if (conflict != null) {
      return conflict;
    }
    long offset = ((AppendResult.Appended) scheduled).newOffset();
    mirror(runId, offset - 1, lastAppended(runId, offset));

    final long startNanos = System.nanoTime();
    final StepResult result = execute(stepId, step, snapshot, sessionRef, now);
    final Duration took = Duration.ofNanos(System.nanoTime() - startNanos);
    metrics.stepFinished(
        plan.id(), step.name(), step.kind(), result.status(), took, result.costMicros());

    return completeStep(runId, plan, snapshot, step, result, offset, now);
  }

  /**
   * Records a step's outcome and applies supervision.
   *
   * <p>Shared by normal execution and by interrupted-step recovery, deliberately. A recovered step
   * must go through exactly the same recording and supervision path as one that ran to completion
   * in this process — a second path would be a second policy, and the two would drift.
   */
  private Advance completeStep(
      final RunId runId,
      final Plan plan,
      final RunSnapshot snapshot,
      final Step step,
      final StepResult result,
      final long offsetIn,
      final Instant now) {

    long offset = offsetIn;
    final StepId stepId = result.stepId();

    // RECORD after EXECUTE. Everything between the two writes is the window a crash can land in.
    final List<RunEvent> tail = new ArrayList<>();
    switch (result) {
      case StepResult.Succeeded succeeded -> {
        succeeded
            .artifact()
            .ifPresent(
                artifact ->
                    tail.add(
                        new RunEvent.ToolArtifactProduced(
                            stepId,
                            artifact.artifactId(),
                            artifact.capability(),
                            artifact.contentDigest(),
                            artifact.tainted(),
                            now)));
        tail.add(
            new RunEvent.StepCompleted(
                stepId,
                step.name(),
                succeeded.value(),
                succeeded.valueDigest(),
                succeeded.costMicros(),
                succeeded.tainted(),
                now));
      }
      case StepResult.Failed failed ->
          tail.add(
              new RunEvent.StepFailed(
                  stepId,
                  step.name(),
                  failed.failure(),
                  failed.reason(),
                  failed.costMicros(),
                  now));
      case StepResult.Cancelled cancelled ->
          tail.add(
              new RunEvent.StepCancelled(
                  stepId, step.name(), cancelled.cause(), cancelled.costMicros(), now));
      case StepResult.Deferred deferred -> {
        // The wait step itself is finished: it decided when to wake, and that is all it had to do.
        // Recording only the park would leave the cursor on the wait forever, so the run would
        // park,
        // resume, re-execute the same wait and park again — an infinite loop that a property test
        // over generated plans found immediately.
        tail.add(
            new RunEvent.StepCompleted(
                stepId,
                step.name(),
                deferred.wakeAt().toString(),
                Digest.of(deferred.wakeAt().toString()),
                0L,
                false,
                now));
        tail.add(new RunEvent.RunParked(deferred.wakeAt(), now));
      }
    }

    for (final RunEvent event : tail) {
      final AppendResult appended = runs.append(runId, offset, event);
      final Advance failure = rejectIfNotAppended(runId, appended, offset);
      if (failure != null) {
        return failure;
      }
      mirror(runId, offset, event);
      offset = ((AppendResult.Appended) appended).newOffset();
    }

    return applyDecision(runId, plan, snapshot, step, result, offset, now);
  }

  /**
   * Resolves a step whose executor died between the two durable writes (AD-025 §35.2).
   *
   * <p>Model and tool steps are resolved by asking the layer below what became of the reference the
   * scheduling event recorded. The pure kinds — wait, condition, branch — are simply recomputed,
   * because nothing outside this runtime observed them and re-running a pure function is free. That
   * is the only place in the design where re-execution during recovery is safe, and it is safe for
   * a reason that does not generalise.
   *
   * @param runId the run
   * @param history the history, whose tail is the unresolved scheduling event
   * @param scheduled that scheduling event
   * @return what the resolution did
   */
  Advance resolveInterruptedStep(
      final RunId runId, final RunHistory history, final RunEvent.StepScheduled scheduled) {

    final Instant now = clock.now();

    final Optional<Plan> pinned = plans.resolve(snapshotVersion(history));
    if (pinned.isEmpty()) {
      return terminate(
          runId,
          history,
          SupervisorDecision.Terminate.failure(
              FailureClass.PLAN_INVALID, "pinned plan version is no longer retained"),
          0,
          0L);
    }
    final Plan plan = pinned.get();

    // Fold everything except the unresolved tail: that is the state the dead executor decided from,
    // and resolving against any other state would attribute the step to the wrong position.
    final RunSnapshot before = ReplayEngine.foldTo(plan, history, history.offset() - 1);
    final Step step = plan.stepAt(scheduled.planStepIndex());

    if (RunRecovery.resolvableByLookup(scheduled.kind())) {
      final RunRecovery.Resolved resolved =
          scheduled.kind() == StepKind.PIPELINE
              ? RunRecovery.resolveSession(sessions, scheduled, now)
              : RunRecovery.resolveTool(sessions, scheduled, now);
      metrics.interruptedStepRecovered(resolved.resolution().name());
      return recordResolved(runId, plan, before, step, resolved.event(), history.offset(), now);
    }

    metrics.interruptedStepRecovered(RunRecovery.Resolution.RECOMPUTED.name());
    final StepResult recomputed =
        execute(scheduled.stepId(), step, before, scheduled.sessionRef(), now);
    return completeStep(runId, plan, before, step, recomputed, history.offset(), now);
  }

  /** Appends a resolution event, then runs supervision over the outcome it implies. */
  private Advance recordResolved(
      final RunId runId,
      final Plan plan,
      final RunSnapshot before,
      final Step step,
      final RunEvent event,
      final long offsetIn,
      final Instant now) {

    final AppendResult appended = runs.append(runId, offsetIn, event);
    final Advance failure = rejectIfNotAppended(runId, appended, offsetIn);
    if (failure != null) {
      return failure;
    }
    mirror(runId, offsetIn, event);
    final long offset = ((AppendResult.Appended) appended).newOffset();

    final StepResult implied =
        switch (event) {
          case RunEvent.StepCompleted completed ->
              new StepResult.Succeeded(
                  completed.stepId(),
                  completed.value(),
                  completed.valueDigest(),
                  Optional.empty(),
                  completed.costMicros(),
                  completed.tainted());
          case RunEvent.StepFailed failed ->
              new StepResult.Failed(
                  failed.stepId(), failed.failure(), failed.reason(), failed.costMicros());
          case RunEvent.StepCancelled cancelled ->
              new StepResult.Cancelled(
                  cancelled.stepId(), cancelled.cause(), cancelled.costMicros());
          default ->
              throw new IllegalStateException(
                  "recovery produced a non-terminal step event: " + event.tag());
        };

    return applyDecision(runId, plan, before, step, implied, offset, now);
  }

  private Advance applyDecision(
      final RunId runId,
      final Plan plan,
      final RunSnapshot snapshot,
      final Step step,
      final StepResult result,
      final long offsetIn,
      final Instant now) {

    final SupervisorDecision decision =
        Supervisor.decide(snapshot, plan.restartPolicy(), step, result, now);
    metrics.supervisionApplied(plan.id(), plan.restartPolicy().strategy(), decision.label());

    long offset = offsetIn;
    final StepId stepId = StepId.of(runId, snapshot.cursor().executionIndex());

    // Every decision is recorded, including the ones that change nothing. A supervision choice that
    // left no trace would make "why did this run stop here?" unanswerable from the audit trail.
    final RunEvent supervision =
        new RunEvent.SupervisionApplied(
            stepId,
            plan.restartPolicy().strategy(),
            decision.label(),
            plan.restartPolicy().intensity().countWithin(snapshot.restartTimes(), now),
            now);
    final AppendResult applied = runs.append(runId, offset, supervision);
    final Advance failure = rejectIfNotAppended(runId, applied, offset);
    if (failure != null) {
      return failure;
    }
    mirror(runId, offset, supervision);
    offset = ((AppendResult.Appended) applied).newOffset();

    return switch (decision) {
      case SupervisorDecision.Advance ignored ->
          new Advance.Advanced(stepId, result.status(), RunState.QUEUED);
      case SupervisorDecision.Skip ignored ->
          new Advance.Advanced(stepId, StepStatus.SKIPPED, RunState.QUEUED);
      case SupervisorDecision.RetryStep retry -> {
        // A zero-delay retry is not a pause. Recording a park that expires the instant it is
        // written
        // costs a durable write and an extra claim for nothing, and it makes RetryMode.IMMEDIATE
        // behave like a park, which is precisely what it is named for not doing.
        if (retry.delay().isZero()) {
          yield new Advance.Advanced(stepId, result.status(), RunState.RETRYING);
        }
        yield park(runId, offset, now.plus(retry.delay()), now);
      }
      case SupervisorDecision.Park parked -> {
        // The park event was already appended by completeStep for the Deferred result that produced
        // this decision. Appending another would record one pause twice and double-count the run's
        // excluded waiting time against its wall-clock bound.
        yield new Advance.Parked(parked.until());
      }
      case SupervisorDecision.JumpTo ignored ->
          new Advance.Advanced(stepId, result.status(), RunState.QUEUED);
      case SupervisorDecision.Compensate ignored ->
          new Advance.Advanced(stepId, result.status(), RunState.QUEUED);
      case SupervisorDecision.Terminate terminate -> {
        if (terminate
            .failure()
            .filter(f -> f == FailureClass.RESTART_INTENSITY_EXCEEDED)
            .isPresent()) {
          metrics.restartIntensityExceeded(plan.id());
        }
        yield recordTermination(
            runId,
            offset,
            terminate,
            snapshot.stepsExecuted() + 1,
            snapshot.budget().consumedMicros() + result.costMicros(),
            now,
            plan.id(),
            snapshot.startedAt());
      }
    };
  }

  /**
   * Leaves a parked state, recording the interval so it does not count against the deadline.
   *
   * <p>Records and yields rather than continuing inline. One extra claim is cheap, and it keeps the
   * "record, then yield" shape that makes every step boundary a place another node can take over.
   */
  private Advance resume(
      final RunId runId, final RunHistory history, final RunSnapshot snapshot, final Instant now) {

    final Instant parkedAt = lastParkInstant(history).orElse(now);
    final long parkedNanos = Math.max(0L, Duration.between(parkedAt, now).toNanos());
    final RunEvent resumed = new RunEvent.RunResumed(parkedNanos, now);
    final AppendResult appended = runs.append(runId, history.offset(), resumed);
    final Advance failure = rejectIfNotAppended(runId, appended, history.offset());
    if (failure != null) {
      return failure;
    }
    mirror(runId, history.offset(), resumed);
    return new Advance.Advanced(
        StepId.of(runId, snapshot.cursor().executionIndex()),
        StepStatus.SUCCEEDED,
        RunState.QUEUED);
  }

  /** The instant of the most recent park, which is when the current pause began. */
  private static Optional<Instant> lastParkInstant(final RunHistory history) {
    for (int i = history.events().size() - 1; i >= 0; i--) {
      if (history.events().get(i) instanceof RunEvent.RunParked parked) {
        return Optional.of(parked.at());
      }
    }
    return Optional.empty();
  }

  private Advance park(
      final RunId runId, final long offset, final Instant until, final Instant now) {
    final RunEvent parked = new RunEvent.RunParked(until, now);
    final AppendResult appended = runs.append(runId, offset, parked);
    final Advance failure = rejectIfNotAppended(runId, appended, offset);
    if (failure != null) {
      return failure;
    }
    mirror(runId, offset, parked);
    return new Advance.Parked(until);
  }

  /**
   * Executes one step. The only method in this class that performs outbound I/O.
   *
   * <p>The switch is exhaustive over {@link StepKind} by construction, so a sixth kind cannot be
   * added without the compiler pointing here.
   */
  private StepResult execute(
      final StepId stepId,
      final Step step,
      final RunSnapshot snapshot,
      final String sessionRef,
      final Instant now) {

    return switch (step) {
      case Step.Pipeline pipeline ->
          sessions.runPipelineStep(stepId, pipeline, snapshot, sessionRef, now);
      case Step.Plugin plugin -> sessions.runToolStep(stepId, plugin, snapshot, sessionRef, now);
      case Step.Wait wait -> new StepResult.Deferred(stepId, now.plus(wait.duration()));
      case Step.Condition condition -> evaluateCondition(stepId, condition, snapshot);
      case Step.Branch branch -> evaluateBranch(stepId, branch, snapshot);
    };
  }

  private static StepResult evaluateCondition(
      final StepId stepId, final Step.Condition condition, final RunSnapshot snapshot) {
    final String subject = snapshot.results().get(condition.subjectRef());
    final boolean outcome = condition.operator().test(subject, condition.operand());
    final String value = Boolean.toString(outcome);
    return new StepResult.Succeeded(stepId, value, Digest.of(value), Optional.empty(), 0L, false);
  }

  private static StepResult evaluateBranch(
      final StepId stepId, final Step.Branch branch, final RunSnapshot snapshot) {
    final String subject = snapshot.results().get(branch.subjectRef());
    final boolean outcome = branch.operator().test(subject, branch.operand());
    final String value = Boolean.toString(outcome);
    return new StepResult.Succeeded(stepId, value, Digest.of(value), Optional.empty(), 0L, false);
  }

  /**
   * Records a termination reached before any step ran this round.
   *
   * @param runId the run
   * @param history its history, whose length is the append offset
   * @param decision the termination decision
   * @param stepsExecuted the execution-graph size
   * @param costMicros the total spend
   * @return the outcome
   */
  Advance terminate(
      final RunId runId,
      final RunHistory history,
      final SupervisorDecision.Terminate decision,
      final int stepsExecuted,
      final long costMicros) {
    return recordTermination(
        runId,
        history.offset(),
        decision,
        stepsExecuted,
        costMicros,
        clock.now(),
        snapshotVersion(history).planId(),
        history.created().map(RunEvent.RunCreated::at).orElse(clock.now()));
  }

  private Advance recordTermination(
      final RunId runId,
      final long offset,
      final SupervisorDecision.Terminate decision,
      final int stepsExecuted,
      final long costMicros,
      final Instant now,
      final io.reliabilityai.gateway.dataplane.agent.api.PlanId planId,
      final Instant startedAt) {

    final RunEvent terminated =
        RunEvent.RunTerminated.of(decision.reason(), stepsExecuted, costMicros, now);
    final AppendResult appended = runs.append(runId, offset, terminated);
    final Advance failure = rejectIfNotAppended(runId, appended, offset);
    if (failure != null) {
      return failure;
    }
    mirror(runId, offset, terminated);
    // Emitted only after the termination is durable, so a counter can never claim a run ended that
    // a
    // crash then unmakes.
    metrics.runTerminated(
        planId, decision.reason(), Duration.between(startedAt, now), stepsExecuted, costMicros);
    return new Advance.Terminated(decision.reason());
  }

  /**
   * Turns a non-success append into the matching outcome, or null when the append succeeded.
   *
   * <p>Returning null for the happy path is unusual, and it is deliberate: every append site needs
   * the same three-way handling, and an {@code Optional} here would put an {@code isPresent} check
   * in front of every one of them for no added clarity.
   */
  private Advance rejectIfNotAppended(
      final RunId runId, final AppendResult result, final long attemptedOffset) {
    return switch (result) {
      case AppendResult.Appended ignored -> null;
      case AppendResult.Conflict conflict -> {
        // Another executor advanced this run. Abandoning is correct and cheap; overwriting would be
        // the one thing that can corrupt a history.
        yield new Advance.Contended(conflict.actualOffset());
      }
      case AppendResult.Unavailable unavailable -> {
        metrics.storeUnavailable("append@" + attemptedOffset + " run=" + runId);
        // Fail closed. AD-025 AGT-14: nothing proceeds unrecorded, because an executor carrying on
        // with unrecorded steps is executing a run whose history is a lie.
        yield new Advance.Stalled(unavailable.reason());
      }
    };
  }

  /**
   * Mirrors a durable event to the audit sink, absorbing anything it throws.
   *
   * <p>AD-025 OBC-2: the sink is best-effort and the history is authoritative. Letting a failing
   * sink propagate would turn an observability outage into a run outage — the exact inversion the
   * contract exists to prevent — and the event is already durable by the time this is called, so
   * there is nothing for the caller to do about it anyway.
   *
   * @param runId the run
   * @param offset the event's offset
   * @param event the recorded event
   */
  private void mirror(final RunId runId, final long offset, final RunEvent event) {
    try {
      audit.recorded(runId, offset, event);
    } catch (final RuntimeException sinkFailure) {
      metrics.storeUnavailable("audit-sink");
    }
  }

  private RunEvent lastAppended(final RunId runId, final long offset) {
    return runs.load(runId)
        .flatMap(RunHistory::last)
        .orElseThrow(
            () ->
                new IllegalStateException(
                    "append at " + offset + " reported success but the history is empty"));
  }

  private static io.reliabilityai.gateway.dataplane.agent.api.RunVersion snapshotVersion(
      final RunHistory history) {
    return history
        .created()
        .map(RunEvent.RunCreated::version)
        .orElseThrow(() -> new IllegalArgumentException("history does not begin with run.created"));
  }

  private static boolean planExhausted(final Plan plan, final RunSnapshot snapshot) {
    return snapshot.cursor().planStepIndex() >= plan.size();
  }

  private static boolean anySkipped(final RunHistory history) {
    for (final RunEvent event : history.events()) {
      if (event instanceof RunEvent.StepSkipped) {
        return true;
      }
    }
    return false;
  }

  /**
   * Builds the artifact a tool step's output becomes.
   *
   * <p>Kept here rather than in the adapter so that the taint default lives in the runtime: an
   * adapter that forgot the flag would produce an untainted artifact, and run-level Rule-of-Two
   * enforcement would silently stop seeing one of its limbs.
   *
   * @param stepId the producing step
   * @param capability the exercised capability
   * @param content the tool output
   * @param trusted whether policy declares the source trustworthy
   * @return the artifact
   */
  static ToolResultArtifact artifactOf(
      final StepId stepId, final String capability, final String content, final boolean trusted) {
    final String bounded = bound(content);
    final String digest = Digest.of(content);
    return trusted
        ? ToolResultArtifact.trusted(stepId, capability, bounded, digest)
        : ToolResultArtifact.tainted(stepId, capability, bounded, digest);
  }

  /**
   * Truncates a recorded value.
   *
   * <p>The history is a control-plane structure, not a copy of every payload a run touched. The
   * full value's digest is recorded regardless, so truncation never weakens divergence detection.
   *
   * @param value the value to record
   * @return the value, or its first {@value #MAX_RECORDED_VALUE} characters with a marker
   */
  static String bound(final String value) {
    if (value.length() <= MAX_RECORDED_VALUE) {
      return value;
    }
    return value.substring(0, MAX_RECORDED_VALUE) + "…[truncated]";
  }

  /**
   * Reports whether an operator is a total predicate over a possibly-absent subject.
   *
   * <p>Always true — every {@link ConditionOperator} is total by contract. The method exists so the
   * property is asserted in one place the tests can point at, rather than being an assumption
   * spread across the two evaluation sites above.
   *
   * @param operator the operator to check
   * @return true, for every operator
   */
  static boolean isTotal(final ConditionOperator operator) {
    Preconditions.requireNonNull(operator, "operator");
    operator.test(null, "");
    return true;
  }
}

package io.reliabilityai.gateway.dataplane.agent.application;

import io.reliabilityai.gateway.common.Preconditions;
import io.reliabilityai.gateway.dataplane.agent.api.AgentMetricsPort;
import io.reliabilityai.gateway.dataplane.agent.api.CancellationCause;
import io.reliabilityai.gateway.dataplane.agent.api.Plan;
import io.reliabilityai.gateway.dataplane.agent.api.PlanRepository;
import io.reliabilityai.gateway.dataplane.agent.api.Run;
import io.reliabilityai.gateway.dataplane.agent.api.RunAuditPort;
import io.reliabilityai.gateway.dataplane.agent.api.RunBounds;
import io.reliabilityai.gateway.dataplane.agent.api.RunBudget;
import io.reliabilityai.gateway.dataplane.agent.api.RunEvent;
import io.reliabilityai.gateway.dataplane.agent.api.RunHistory;
import io.reliabilityai.gateway.dataplane.agent.api.RunId;
import io.reliabilityai.gateway.dataplane.agent.api.RunRepository;
import io.reliabilityai.gateway.dataplane.agent.api.RunRepository.AppendResult;
import io.reliabilityai.gateway.dataplane.agent.api.RunSecurityContext;
import io.reliabilityai.gateway.dataplane.agent.api.RunSnapshot;
import io.reliabilityai.gateway.dataplane.agent.api.RunState;
import io.reliabilityai.gateway.dataplane.agent.api.RunStoreUnavailableException;
import io.reliabilityai.gateway.dataplane.agent.domain.ReplayEngine;
import io.reliabilityai.gateway.dataplane.agent.domain.RunStateMachine;
import io.reliabilityai.gateway.ports.ClockPort;
import java.time.Instant;
import java.util.Optional;

/**
 * The Agent Runtime's front door (C14, AD-025).
 *
 * <p>Start a run, cancel a run, ask about a run. Nothing else: this class holds no loop, no thread
 * and no state. Advancing runs is the {@link RunScheduler}'s job and executing a step is the {@link
 * RunExecutor}'s, so a caller that starts a run and walks away has left nothing running in this
 * process — the run lives in the store and any node can pick it up.
 *
 * <p><b>What this runtime cannot do, structurally.</b> It has no reference to a provider, to {@code
 * RequestPipeline}, to the Plugin Runtime or to the Governance Engine. AD-025 AGT-1, AGT-2 and
 * AGT-4 are consequences of the module's dependency graph rather than rules a reviewer enforces.
 *
 * <p><b>What it deliberately does not implement:</b> planning (a plan arrives as data and is
 * executed as given), memory, and multi-agent composition. Each is out of scope for this milestone
 * and would change the termination and budget arguments if bolted on.
 */
public final class AgentRuntime {

  private final RunRepository runs;
  private final PlanRepository plans;
  private final RunExecutor executor;
  private final ClockPort clock;
  private final AgentMetricsPort metrics;
  private final RunAuditPort audit;

  /**
   * Creates the runtime.
   *
   * @param runs the durable run store
   * @param plans the pinned-plan resolver
   * @param executor the stateless step executor
   * @param clock the injected clock
   * @param metrics where operational signals go
   * @param audit where recorded events are mirrored
   */
  public AgentRuntime(
      final RunRepository runs,
      final PlanRepository plans,
      final RunExecutor executor,
      final ClockPort clock,
      final AgentMetricsPort metrics,
      final RunAuditPort audit) {
    this.runs = Preconditions.requireNonNull(runs, "runs");
    this.plans = Preconditions.requireNonNull(plans, "plans");
    this.executor = Preconditions.requireNonNull(executor, "executor");
    this.clock = Preconditions.requireNonNull(clock, "clock");
    this.metrics = Preconditions.requireNonNull(metrics, "metrics");
    this.audit = Preconditions.requireNonNull(audit, "audit");
  }

  /** Why a run could not be started. */
  public sealed interface StartOutcome permits StartOutcome.Started, StartOutcome.Refused {

    /**
     * The run exists and is claimable.
     *
     * @param runId the new run
     */
    record Started(RunId runId) implements StartOutcome {}

    /**
     * The run was not created, and nothing was recorded.
     *
     * @param reason a short operator-facing explanation
     */
    record Refused(String reason) implements StartOutcome {}
  }

  /**
   * Admits and creates a run.
   *
   * <p>Everything that defines what the run may do is fixed here and never changes: the plan
   * version, the bounds, the budget and the capability ceiling (AD-025 §24.2). A run that could
   * widen any of them would have no bounds at all, and the termination proof would fail at its
   * first premise.
   *
   * @param runId the identity to create the run under, supplied by the caller so that a retried
   *     submission is idempotent rather than creating a second run
   * @param plan the plan to execute; its version is pinned into the run
   * @param security the caller's authenticated context
   * @param requestedBounds the bounds to run under, tightened against the plan's own
   * @param grantMicros the spend grant
   * @return the outcome
   */
  public StartOutcome start(
      final RunId runId,
      final Plan plan,
      final RunSecurityContext security,
      final RunBounds requestedBounds,
      final long grantMicros) {

    Preconditions.requireNonNull(runId, "runId");
    Preconditions.requireNonNull(plan, "plan");
    Preconditions.requireNonNull(security, "security");
    Preconditions.requireNonNull(requestedBounds, "requestedBounds");

    if (!plans.retains(plan.id(), plan.version())) {
      return new StartOutcome.Refused(
          "plan " + plan.id() + " v" + plan.version() + " is not retained by the plan repository");
    }

    // The ceiling is the intersection of what the plan needs and what the principal holds.
    // Computing
    // it once here, rather than per step, is what makes it auditable and replay-stable (AD-025
    // P-6).
    if (!security.permitsAll(plan.declaredCapabilities())) {
      return new StartOutcome.Refused(
          "principal lacks capabilities required by the plan: " + missing(plan, security));
    }

    final RunBounds bounds = requestedBounds.tighten(plan.bounds());
    final RunEvent.RunCreated created =
        new RunEvent.RunCreated(
            io.reliabilityai.gateway.dataplane.agent.api.RunVersion.pin(plan.id(), plan.version()),
            bounds,
            RunBudget.of(Math.min(grantMicros, bounds.spendMicros())),
            security,
            Optional.empty(),
            0,
            clock.now());

    final AppendResult result;
    try {
      result = runs.create(runId, created);
    } catch (final RunStoreUnavailableException unavailable) {
      metrics.storeUnavailable("create");
      return new StartOutcome.Refused("run store unavailable: " + unavailable.getMessage());
    }

    return switch (result) {
      case AppendResult.Appended ignored -> {
        mirror(runId, 0L, created);
        metrics.runStarted(plan.id(), plan.version());
        queue(runId);
        yield new StartOutcome.Started(runId);
      }
      case AppendResult.Conflict ignored ->
          new StartOutcome.Refused("run " + runId + " already exists");
      case AppendResult.Unavailable unavailable ->
          new StartOutcome.Refused("run store unavailable: " + unavailable.reason());
    };
  }

  /**
   * Makes a newly created run claimable.
   *
   * <p>Separate from creation so that the creation record is durable before the run becomes visible
   * to an executor. Creating and queueing in one write would let a node claim a run whose creation
   * had not yet been acknowledged.
   */
  private void queue(final RunId runId) {
    final RunEvent queued = new RunEvent.RunQueued(clock.now());
    final AppendResult appended = runs.append(runId, 1L, queued);
    if (appended instanceof AppendResult.Appended) {
      mirror(runId, 1L, queued);
    }
  }

  /**
   * Cancels a run.
   *
   * <p>Accepted from every non-terminal state and never refused (AD-025 SM-7, AGT-28). The
   * cancellation becomes a durable event immediately; the run stops at its next step boundary, and
   * any in-flight session is told to stop.
   *
   * @param runId the run to cancel
   * @param cause why
   * @param reason a short operator-facing explanation
   * @return true when a cancellation was recorded, false when the run was unknown or already
   *     terminal
   */
  public boolean cancel(final RunId runId, final CancellationCause cause, final String reason) {
    Preconditions.requireNonNull(runId, "runId");
    Preconditions.requireNonNull(cause, "cause");
    Preconditions.requireNonBlank(reason, "reason");

    final Optional<RunHistory> loaded;
    try {
      loaded = runs.load(runId);
    } catch (final RunStoreUnavailableException unavailable) {
      metrics.storeUnavailable("cancel");
      return false;
    }
    if (loaded.isEmpty() || loaded.get().terminated()) {
      return false;
    }

    final RunHistory history = loaded.get();
    final RunEvent cancelled = new RunEvent.RunCancelled(cause, reason, clock.now());
    final AppendResult appended = runs.append(runId, history.offset(), cancelled);
    if (appended instanceof AppendResult.Appended) {
      mirror(runId, history.offset(), cancelled);
      return true;
    }
    return false;
  }

  /**
   * Loads a run: its pinned plan and its folded state.
   *
   * @param runId the run
   * @return the run, or empty when it is unknown or its pinned plan version is no longer retained
   */
  public Optional<Run> lookup(final RunId runId) {
    Preconditions.requireNonNull(runId, "runId");
    return runs.load(runId)
        .flatMap(
            history ->
                history
                    .created()
                    .flatMap(created -> plans.resolve(created.version()))
                    .map(plan -> new Run(runId, plan, ReplayEngine.fold(plan, history))));
  }

  /**
   * Loads a run's folded state without resolving its plan.
   *
   * <p>Useful when the pinned plan version has been purged: the run's history still explains what
   * it did, even though nothing can advance it any further.
   *
   * @param runId the run
   * @return the snapshot, or empty when the run is unknown
   */
  public Optional<RunSnapshot> snapshot(final RunId runId) {
    Preconditions.requireNonNull(runId, "runId");
    return runs.load(runId)
        .flatMap(
            history ->
                history
                    .created()
                    .flatMap(created -> plans.resolve(created.version()))
                    .map(plan -> ReplayEngine.fold(plan, history)));
  }

  /**
   * Advances a run by one step, for callers driving a run directly.
   *
   * @param runId the run
   * @return what the step did
   */
  public RunExecutor.Advance advance(final RunId runId) {
    return executor.advance(runId);
  }

  /**
   * Reports whether a run may legally move to a state.
   *
   * <p>Exposed so that callers and tests interrogate the same table the runtime enforces, rather
   * than a second copy of the rules that can drift from it.
   *
   * @param from the current state
   * @param to the proposed state
   * @return true when the transition is legal
   */
  public static boolean permits(final RunState from, final RunState to) {
    return RunStateMachine.permits(from, to);
  }

  /**
   * Returns the current instant from the injected clock.
   *
   * @return now, as this runtime sees it
   */
  public Instant now() {
    return clock.now();
  }

  /**
   * Mirrors a durable event to the audit sink, absorbing anything it throws.
   *
   * <p>Same contract as the executor's: best-effort, never load-bearing (AD-025 OBC-2).
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

  private static String missing(final Plan plan, final RunSecurityContext security) {
    final java.util.TreeSet<String> lacking = new java.util.TreeSet<>(plan.declaredCapabilities());
    lacking.removeAll(security.capabilities());
    return String.join(",", lacking);
  }
}

package io.reliabilityai.gateway.dataplane.agent.domain;

import io.reliabilityai.gateway.common.Preconditions;
import io.reliabilityai.gateway.dataplane.agent.api.ExecutionCursor;
import io.reliabilityai.gateway.dataplane.agent.api.Plan;
import io.reliabilityai.gateway.dataplane.agent.api.RunBudget;
import io.reliabilityai.gateway.dataplane.agent.api.RunCancellation;
import io.reliabilityai.gateway.dataplane.agent.api.RunEvent;
import io.reliabilityai.gateway.dataplane.agent.api.RunHistory;
import io.reliabilityai.gateway.dataplane.agent.api.RunSnapshot;
import io.reliabilityai.gateway.dataplane.agent.api.RunState;
import io.reliabilityai.gateway.dataplane.agent.api.StepId;
import io.reliabilityai.gateway.dataplane.agent.api.ToolResultArtifact;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Reconstructs a run's state by folding its history (AD-025 §46).
 *
 * <p><b>Replay is not re-execution.</b> No model is called, no tool runs, no money is spent and no
 * side effect recurs. Folding a forty-step run costs a database read and a loop — which is the
 * whole practical payoff of the design, and the property that distinguishes it from LangGraph,
 * which re-executes the interrupted node on resume, and from the CLI agents, which lose the run
 * entirely.
 *
 * <p><b>This class is deterministic and performs no I/O.</b> Straight from Temporal and Azure
 * Durable Functions, whose orchestrators are forbidden from reading a wall clock, generating
 * identifiers or calling out. Here that is structural: every method is static, takes only recorded
 * data, and returns a value. There is no clock field to read and no port to call.
 *
 * <p>Consequently every non-deterministic thing a run does — a model's answer, a tool's output, the
 * current time, a wake instant — arrives as a <em>recorded event</em>, and the fold consumes the
 * record. That is what makes two folds of the same history produce the same snapshot, which is what
 * makes {@code REPLAY_DIVERGENCE} a meaningful signal rather than noise.
 */
public final class ReplayEngine {

  private ReplayEngine() {
    throw new AssertionError("no instances");
  }

  /**
   * Folds a whole history into a snapshot.
   *
   * @param plan the pinned plan version the run started with
   * @param history the run's recorded events
   * @return the run's state
   * @throws IllegalArgumentException when the history does not begin with a creation event; a
   *     history that starts anywhere else is not a run this engine can explain, and guessing would
   *     fabricate state
   */
  public static RunSnapshot fold(final Plan plan, final RunHistory history) {
    Preconditions.requireNonNull(plan, "plan");
    Preconditions.requireNonNull(history, "history");

    final RunEvent.RunCreated created =
        history
            .created()
            .orElseThrow(
                () ->
                    new IllegalArgumentException(
                        "history for " + history.runId() + " does not begin with run.created"));

    final Folded folded = new Folded(created);
    for (final RunEvent event : history.events()) {
      folded.apply(event, plan);
    }
    return folded.toSnapshot(history);
  }

  /**
   * Folds only the first {@code upTo} events.
   *
   * <p>Used by divergence checking and by inspection tooling: "what did the interpreter believe at
   * offset N?" is answerable without re-running anything.
   *
   * @param plan the pinned plan version
   * @param history the run's recorded events
   * @param upTo how many events to fold
   * @return the run's state at that offset
   */
  public static RunSnapshot foldTo(final Plan plan, final RunHistory history, final long upTo) {
    Preconditions.requireNonNull(history, "history");
    Preconditions.requireNonNegative(upTo, "upTo");
    final long bounded = Math.min(upTo, history.offset());
    return fold(plan, new RunHistory(history.runId(), history.events().subList(0, (int) bounded)));
  }

  /**
   * The mutable accumulator. Private, short-lived and never escapes; the fold's result is a value.
   */
  private static final class Folded {

    private final RunEvent.RunCreated created;
    private RunState state = RunState.CREATED;
    private ExecutionCursor cursor = ExecutionCursor.START;
    private RunBudget budget;
    private final Map<String, String> results = new LinkedHashMap<>();
    private final Map<String, ToolResultArtifact> artifacts = new LinkedHashMap<>();
    private final List<Instant> restartTimes = new ArrayList<>();
    private Optional<RunCancellation> cancellation = Optional.empty();
    private Optional<io.reliabilityai.gateway.dataplane.agent.api.TerminalReason> terminalReason =
        Optional.empty();
    private Optional<Instant> wakeAt = Optional.empty();
    private int stepsExecuted;
    private long parkedNanos;
    private boolean tainted;

    Folded(final RunEvent.RunCreated created) {
      this.created = created;
      this.budget = created.budget();
    }

    void apply(final RunEvent event, final Plan plan) {
      switch (event) {
        case RunEvent.RunCreated ignored -> state = RunState.CREATED;

        case RunEvent.RunQueued ignored -> state = RunState.QUEUED;

        case RunEvent.StepScheduled scheduled -> {
          state = RunState.RUNNING;
          cursor =
              new ExecutionCursor(
                  scheduled.planStepIndex(),
                  scheduled.stepId().index(),
                  scheduled.attempt(),
                  cursor.historyOffset());
        }

        case RunEvent.StepCompleted completed -> {
          results.put(completed.stepName(), completed.value());
          budget = budget.consume(completed.costMicros());
          stepsExecuted++;
          // Taint is monotone across the whole run and never decays (AD-025 AGT-22). There is no
          // "it was several steps ago" exemption: a model's context and a run's recorded results
          // both
          // persist, so a limb acquired at step 2 is still a limb at step 40.
          tainted = tainted || completed.tainted();
          cursor = cursor.advance();
          state = RunState.QUEUED;
        }

        case RunEvent.StepFailed failed -> {
          budget = budget.consume(failed.costMicros());
          stepsExecuted++;
          // Deliberately no cursor move: the supervisor decides what happens next, and moving here
          // would silently skip the failed step before anybody chose to.
          state = RunState.RUNNING;
        }

        case RunEvent.StepSkipped ignored -> {
          cursor = cursor.advance();
          state = RunState.QUEUED;
        }

        case RunEvent.StepCancelled cancelled -> {
          budget = budget.consume(cancelled.costMicros());
          stepsExecuted++;
        }

        case RunEvent.ToolArtifactProduced produced -> {
          artifacts.put(
              produced.artifactId(),
              new ToolResultArtifact(
                  produced.artifactId(),
                  produced.stepId(),
                  produced.capability(),
                  produced.contentDigest(),
                  results.getOrDefault(produced.artifactId(), ""),
                  produced.tainted()));
          tainted = tainted || produced.tainted();
        }

        case RunEvent.SupervisionApplied supervision -> applySupervision(supervision);

        case RunEvent.BranchTaken branch -> {
          results.put(branch.stepName(), Boolean.toString(branch.outcome()));
          stepsExecuted++;
          final int target = plan.indexOf(branch.targetStep());
          cursor = target >= 0 ? cursor.jumpTo(target) : cursor.advance();
          state = RunState.QUEUED;
        }

        case RunEvent.RunParked parked -> {
          state = RunState.WAITING;
          wakeAt = Optional.of(parked.wakeAt());
        }

        case RunEvent.RunResumed resumed -> {
          parkedNanos += resumed.parkedNanos();
          wakeAt = Optional.empty();
          state = RunState.QUEUED;
        }

        case RunEvent.RunCheckpointed ignored -> {
          // A marker only. The boundary it names is already implied by the step event before it, so
          // folding it must change nothing — if it did, the checkpoint would be state rather than a
          // pointer, and there would be two things to disagree.
        }

        case RunEvent.RunCancelled cancelled ->
            cancellation =
                Optional.of(
                    new RunCancellation(cancelled.cause(), cancelled.reason(), cancelled.at()));

        case RunEvent.RunTerminated terminated -> {
          state = terminated.state();
          terminalReason = Optional.of(terminated.reason());
        }
      }
    }

    private void applySupervision(final RunEvent.SupervisionApplied supervision) {
      switch (supervision.decision()) {
        case "retry" -> {
          restartTimes.add(supervision.at());
          cursor = cursor.retry();
          state = RunState.RETRYING;
        }
        case "skip" -> {
          cursor = cursor.advance();
          state = RunState.QUEUED;
        }
        case "restart-from" -> {
          restartTimes.add(supervision.at());
          state = RunState.QUEUED;
        }
        case "park" -> state = RunState.WAITING;
        default -> {
          // advance, branch, compensate and terminate are recorded for audit; the state change they
          // imply arrives with the event that actually carries it, so folding them twice would
          // double-count. Only the decisions with no other event of their own act here.
        }
      }
    }

    RunSnapshot toSnapshot(final RunHistory history) {
      return new RunSnapshot(
          history.runId(),
          created.version(),
          state,
          cursor.atOffset(history.offset()),
          created.bounds(),
          budget,
          created.security(),
          results,
          artifacts,
          restartTimes,
          cancellation,
          terminalReason,
          wakeAt,
          stepsExecuted,
          parkedNanos,
          tainted,
          created.parent(),
          created.depth(),
          created.at());
    }
  }

  /**
   * Returns the step identity the interpreter would derive at a given position.
   *
   * <p>Exposed so that divergence checking can compare a recomputed identity against a recorded one
   * without duplicating the derivation rule in two places.
   *
   * @param runId the run
   * @param cursor the position
   * @return the derived step identity
   */
  public static StepId stepIdAt(
      final io.reliabilityai.gateway.dataplane.agent.api.RunId runId,
      final ExecutionCursor cursor) {
    Preconditions.requireNonNull(runId, "runId");
    Preconditions.requireNonNull(cursor, "cursor");
    return StepId.of(runId, cursor.executionIndex());
  }

  /**
   * Reports whether a recorded step event agrees with what the interpreter would have decided.
   *
   * <p>Disagreement is {@code REPLAY_DIVERGENCE} — non-determinism has leaked into the interpreter,
   * or a plan changed under an in-flight run. AD-025 §46.3 makes it a hard failure rather than
   * something to resolve by preferring one side: a run whose history no longer explains its
   * behaviour has lost the audit integrity that is the product.
   *
   * @param expected the step the interpreter derives at the current position
   * @param recorded the step the history says was scheduled
   * @return true when they agree
   */
  public static boolean agrees(final StepId expected, final StepId recorded) {
    Preconditions.requireNonNull(expected, "expected");
    Preconditions.requireNonNull(recorded, "recorded");
    return expected.equals(recorded);
  }
}

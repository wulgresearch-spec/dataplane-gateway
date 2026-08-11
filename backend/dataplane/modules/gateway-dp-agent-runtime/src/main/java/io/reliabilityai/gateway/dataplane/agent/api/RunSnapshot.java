package io.reliabilityai.gateway.dataplane.agent.api;

import io.reliabilityai.gateway.common.Preconditions;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * A run's state, folded from its history (AD-025 §46.1).
 *
 * <p><b>Derived, never stored.</b> Every field here is a function of the events; nothing in this
 * runtime persists a snapshot as authority. That is what makes the executor stateless — any node
 * can reconstruct this from the store and continue — and it is what makes {@code REPLAY_DIVERGENCE}
 * meaningful, because there is a single computation to disagree with.
 *
 * @param runId the run
 * @param version the pinned plan version
 * @param state the folded lifecycle state
 * @param cursor the folded position
 * @param bounds the run's fixed bounds
 * @param budget the grant and what has been consumed
 * @param security the immutable security context
 * @param results each completed step's recorded value, by step name
 * @param artifacts each tool artifact produced, by artifact id
 * @param restartTimes the instants of recorded restarts, for the intensity window
 * @param cancellation the accepted cancellation, if any
 * @param terminalReason the closed-set reason, present only once the run has ended
 * @param wakeAt when a parked run becomes claimable again
 * @param stepsExecuted the size of the execution graph so far
 * @param parkedNanos total recorded parked time, excluded from the wall-clock bound
 * @param tainted whether any recorded result carried untrusted content into this run
 * @param parent the parent run, empty for a root run
 * @param depth the run's depth in the tree
 * @param startedAt when the run was created
 */
public record RunSnapshot(
    RunId runId,
    RunVersion version,
    RunState state,
    ExecutionCursor cursor,
    RunBounds bounds,
    RunBudget budget,
    RunSecurityContext security,
    Map<String, String> results,
    Map<String, ToolResultArtifact> artifacts,
    List<Instant> restartTimes,
    Optional<RunCancellation> cancellation,
    Optional<TerminalReason> terminalReason,
    Optional<Instant> wakeAt,
    int stepsExecuted,
    long parkedNanos,
    boolean tainted,
    Optional<RunId> parent,
    int depth,
    Instant startedAt) {

  /**
   * Validates and freezes the snapshot.
   *
   * @param runId the run
   * @param version the pinned version
   * @param state the folded state
   * @param cursor the folded position
   * @param bounds the run bounds
   * @param budget the run budget
   * @param security the security context
   * @param results the recorded step values
   * @param artifacts the recorded artifacts
   * @param restartTimes the recorded restart instants
   * @param cancellation the accepted cancellation
   * @param terminalReason the terminal reason
   * @param wakeAt the parked wake instant
   * @param stepsExecuted the execution-graph size
   * @param parkedNanos the recorded parked time
   * @param tainted the run-level taint flag
   * @param parent the parent run
   * @param depth the tree depth
   * @param startedAt the creation instant
   */
  public RunSnapshot {
    Preconditions.requireNonNull(runId, "runId");
    Preconditions.requireNonNull(version, "version");
    Preconditions.requireNonNull(state, "state");
    Preconditions.requireNonNull(cursor, "cursor");
    Preconditions.requireNonNull(bounds, "bounds");
    Preconditions.requireNonNull(budget, "budget");
    Preconditions.requireNonNull(security, "security");
    results = results == null ? Map.of() : Map.copyOf(results);
    artifacts = artifacts == null ? Map.of() : Map.copyOf(artifacts);
    restartTimes = restartTimes == null ? List.of() : List.copyOf(restartTimes);
    Preconditions.requireNonNull(cancellation, "cancellation");
    Preconditions.requireNonNull(terminalReason, "terminalReason");
    Preconditions.requireNonNull(wakeAt, "wakeAt");
    Preconditions.requireNonNull(parent, "parent");
    Preconditions.requireNonNull(startedAt, "startedAt");
    Preconditions.requireNonNegative(parkedNanos, "parkedNanos");
    if (stepsExecuted < 0) {
      throw new IllegalArgumentException("stepsExecuted must be non-negative");
    }
    if (depth < 0) {
      throw new IllegalArgumentException("depth must be non-negative");
    }
  }

  /**
   * Returns a recorded step's value.
   *
   * @param stepName the plan-local step name
   * @return the recorded value, or empty when that step has not completed
   */
  public Optional<String> resultOf(final String stepName) {
    Preconditions.requireNonNull(stepName, "stepName");
    return Optional.ofNullable(results.get(stepName));
  }

  /**
   * Returns the run's wall-clock accounting.
   *
   * @return a timeout carrying the start instant, the bound and the recorded parked time
   */
  public RunTimeout timeout() {
    return new RunTimeout(startedAt, bounds.wallClock(), parkedNanos);
  }

  /**
   * Reports whether the run has ended.
   *
   * @return true when the state is absorbing
   */
  public boolean terminal() {
    return state.terminal();
  }

  /**
   * Reports whether an executor may claim this run right now.
   *
   * <p>A parked run is deliberately excluded until its wake instant: it is not finished, but
   * claiming it early would mean an executor spinning on a run with nothing to do.
   *
   * @param now the instant to evaluate at, from the injected clock
   * @return true when the run is ready to be advanced
   */
  public boolean claimableAt(final Instant now) {
    Preconditions.requireNonNull(now, "now");
    if (state.terminal()) {
      return false;
    }
    if (state == RunState.WAITING) {
      return wakeAt.map(instant -> !now.isBefore(instant)).orElse(false);
    }
    return state == RunState.CREATED || state == RunState.QUEUED || state == RunState.RETRYING;
  }

  /**
   * Returns the checkpoint this snapshot represents.
   *
   * @param at the instant to stamp the checkpoint with
   * @return the checkpoint
   */
  public Checkpoint checkpoint(final Instant at) {
    return new Checkpoint(runId, cursor.historyOffset(), cursor, state, at);
  }

  /**
   * Reports whether the step bound still permits another step.
   *
   * @return true when {@code stepsExecuted} is below the bound
   */
  public boolean stepsRemain() {
    return stepsExecuted < bounds.maxSteps();
  }
}

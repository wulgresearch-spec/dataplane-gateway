package io.reliabilityai.gateway.dataplane.agent.domain;

import io.reliabilityai.gateway.common.Preconditions;
import io.reliabilityai.gateway.dataplane.agent.api.IllegalRunTransitionException;
import io.reliabilityai.gateway.dataplane.agent.api.RunState;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

/**
 * The run state machine (AD-025 §53).
 *
 * <p>A static table, not a set of scattered {@code if} statements. Every legal edge is declared in
 * one place a reviewer can read, and every other edge is illegal — which makes "is this transition
 * legal?" a lookup rather than an argument.
 *
 * <p>The invariants this class enforces:
 *
 * <ul>
 *   <li><b>SM-1</b> Terminal states are absorbing. No edge leaves one.
 *   <li><b>SM-2</b> Every non-terminal state has a path to a terminal state.
 *   <li><b>SM-7</b> Cancellation is accepted from every non-terminal state.
 *   <li><b>SM-8</b> A bound may terminate a run from any non-terminal state, including the waiting
 *       ones — without which a run parked on a timer could outlive its wall-clock bound forever.
 * </ul>
 *
 * <p>All three of the last are testable properties of the table rather than claims in a comment,
 * and the test suite checks them by exhausting the transition space.
 */
public final class RunStateMachine {

  private static final Map<RunState, Set<RunState>> EDGES = buildEdges();

  private RunStateMachine() {
    throw new AssertionError("no instances");
  }

  private static Map<RunState, Set<RunState>> buildEdges() {
    final Map<RunState, Set<RunState>> edges = new EnumMap<>(RunState.class);

    // Admitted, waiting to be made claimable. May be cancelled or refused before it ever runs.
    edges.put(
        RunState.CREATED,
        EnumSet.of(
            RunState.QUEUED,
            RunState.CANCELLED,
            RunState.FAILED,
            RunState.TIMED_OUT,
            RunState.DEAD_LETTER));

    // Claimable. An executor moves it to RUNNING; a sweep may terminate it on a bound.
    edges.put(
        RunState.QUEUED,
        EnumSet.of(
            RunState.RUNNING,
            RunState.CANCELLED,
            RunState.TIMED_OUT,
            RunState.FAILED,
            RunState.DEAD_LETTER));

    // The only state holding a session (SM-3). Everything a step can produce leaves from here.
    edges.put(
        RunState.RUNNING,
        EnumSet.of(
            RunState.QUEUED,
            RunState.WAITING,
            RunState.RETRYING,
            RunState.COMPLETED,
            RunState.FAILED,
            RunState.CANCELLED,
            RunState.TIMED_OUT,
            RunState.DEAD_LETTER));

    // Parked on a durable timer. Holds nothing; a bound can still end it (SM-8).
    edges.put(
        RunState.WAITING,
        EnumSet.of(
            RunState.QUEUED,
            RunState.RUNNING,
            RunState.CANCELLED,
            RunState.TIMED_OUT,
            RunState.FAILED,
            RunState.DEAD_LETTER));

    // A retry is scheduled. Distinct from WAITING so metrics and operators can tell a backoff from
    // a
    // planned pause — they look identical in a store row otherwise, and mean very different things.
    edges.put(
        RunState.RETRYING,
        EnumSet.of(
            RunState.QUEUED,
            RunState.RUNNING,
            RunState.CANCELLED,
            RunState.TIMED_OUT,
            RunState.FAILED,
            RunState.DEAD_LETTER));

    for (final RunState terminal : RunState.TERMINAL_STATES) {
      edges.put(terminal, EnumSet.noneOf(RunState.class));
    }

    // Freeze the successor sets, not merely the map. An unmodifiable map holding mutable values is
    // not an immutable table: a caller who obtained a successor set could add an edge to it and
    // widen the state machine for the whole process. That is not hypothetical — the test asserting
    // this property found the table mutable and, by adding QUEUED -> COMPLETED, made a second test
    // fail somewhere else entirely. A shared table that any caller can edit is worse than no table.
    final Map<RunState, Set<RunState>> frozen = new EnumMap<>(RunState.class);
    edges.forEach(
        (state, successors) ->
            frozen.put(state, java.util.Collections.unmodifiableSet(successors)));
    return java.util.Collections.unmodifiableMap(frozen);
  }

  /**
   * Reports whether a transition is legal.
   *
   * <p>A self-transition is legal for no state. Re-entering {@code RUNNING} from {@code RUNNING}
   * would mean two steps in flight at once, which SM-3 and SM-5 exist to prevent.
   *
   * @param from the current state
   * @param to the proposed state
   * @return true when the edge exists
   */
  public static boolean permits(final RunState from, final RunState to) {
    Preconditions.requireNonNull(from, "from");
    Preconditions.requireNonNull(to, "to");
    return EDGES.get(from).contains(to);
  }

  /**
   * Enforces a transition.
   *
   * @param from the current state
   * @param to the proposed state
   * @throws IllegalRunTransitionException when the edge does not exist
   */
  public static void requirePermitted(final RunState from, final RunState to) {
    if (!permits(from, to)) {
      throw new IllegalRunTransitionException(from, to);
    }
  }

  /**
   * Returns every state reachable in one transition.
   *
   * @param from the current state
   * @return the successors, empty for a terminal state
   */
  public static Set<RunState> successorsOf(final RunState from) {
    Preconditions.requireNonNull(from, "from");
    return EDGES.get(from);
  }

  /**
   * Reports whether a run in this state can still be cancelled.
   *
   * <p>SM-7: always, unless it has already ended. A run has no veto over cancellation.
   *
   * @param from the current state
   * @return true for every non-terminal state
   */
  public static boolean cancellable(final RunState from) {
    Preconditions.requireNonNull(from, "from");
    return !from.terminal();
  }

  /**
   * Reports whether a bound may terminate a run in this state.
   *
   * <p>SM-8: from any non-terminal state, the waiting ones included.
   *
   * @param from the current state
   * @return true for every non-terminal state
   */
  public static boolean boundMayTerminate(final RunState from) {
    Preconditions.requireNonNull(from, "from");
    return !from.terminal();
  }
}

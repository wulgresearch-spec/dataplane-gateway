package io.reliabilityai.gateway.dataplane.agent.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.reliabilityai.gateway.dataplane.agent.api.IllegalRunTransitionException;
import io.reliabilityai.gateway.dataplane.agent.api.RunState;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayNameGeneration;
import org.junit.jupiter.api.DisplayNameGenerator;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * The transition table, exhausted.
 *
 * <p>Every one of the hundred ordered state pairs is asserted, so a future edit that adds an edge
 * has to add it here too. That is the point: a state machine whose illegal transitions are only
 * implicitly illegal is a state machine nobody can safely change.
 */
@DisplayNameGeneration(DisplayNameGenerator.ReplaceUnderscores.class)
class RunStateMachineTest {

  /** The edges the design permits. Everything absent from this set must be refused. */
  private static final Set<String> LEGAL =
      Set.of(
          "CREATED->QUEUED",
          "CREATED->CANCELLED",
          "CREATED->FAILED",
          "CREATED->TIMED_OUT",
          "CREATED->DEAD_LETTER",
          "QUEUED->RUNNING",
          "QUEUED->CANCELLED",
          "QUEUED->TIMED_OUT",
          "QUEUED->FAILED",
          "QUEUED->DEAD_LETTER",
          "RUNNING->QUEUED",
          "RUNNING->WAITING",
          "RUNNING->RETRYING",
          "RUNNING->COMPLETED",
          "RUNNING->FAILED",
          "RUNNING->CANCELLED",
          "RUNNING->TIMED_OUT",
          "RUNNING->DEAD_LETTER",
          "WAITING->QUEUED",
          "WAITING->RUNNING",
          "WAITING->CANCELLED",
          "WAITING->TIMED_OUT",
          "WAITING->FAILED",
          "WAITING->DEAD_LETTER",
          "RETRYING->QUEUED",
          "RETRYING->RUNNING",
          "RETRYING->CANCELLED",
          "RETRYING->TIMED_OUT",
          "RETRYING->FAILED",
          "RETRYING->DEAD_LETTER");

  static Stream<Arguments> everyOrderedPair() {
    final List<Arguments> pairs = new ArrayList<>();
    for (final RunState from : RunState.values()) {
      for (final RunState to : RunState.values()) {
        pairs.add(Arguments.of(from, to));
      }
    }
    return pairs.stream();
  }

  @ParameterizedTest(name = "{0} -> {1}")
  @MethodSource("everyOrderedPair")
  void theTableAgreesWithTheDesignForEveryOrderedPairOfStates(
      final RunState from, final RunState to) {
    final boolean expected = LEGAL.contains(from + "->" + to);
    assertThat(RunStateMachine.permits(from, to)).as("%s -> %s", from, to).isEqualTo(expected);
  }

  @ParameterizedTest
  @EnumSource(RunState.class)
  void noStateTransitionsToItself(final RunState state) {
    // A self-transition on RUNNING would mean two steps in flight at once, which SM-3 and SM-5
    // exist
    // to prevent; on the others it would be a no-op that hides a missing decision.
    assertThat(RunStateMachine.permits(state, state)).isFalse();
  }

  @ParameterizedTest
  @EnumSource(
      value = RunState.class,
      names = {"COMPLETED", "CANCELLED", "TIMED_OUT", "FAILED", "DEAD_LETTER"})
  void terminalStatesAreAbsorbing(final RunState terminal) {
    // SM-1.
    assertThat(RunStateMachine.successorsOf(terminal)).isEmpty();
    for (final RunState any : RunState.values()) {
      assertThat(RunStateMachine.permits(terminal, any)).isFalse();
    }
  }

  @ParameterizedTest
  @EnumSource(
      value = RunState.class,
      names = {"CREATED", "QUEUED", "RUNNING", "WAITING", "RETRYING"})
  void everyNonTerminalStateCanReachATerminalState(final RunState start) {
    // SM-2, proven by search rather than asserted. A non-terminal state with no path out would be a
    // run that can never end, and the termination guarantee would be false for it.
    assertThat(reachesTerminalFrom(start)).isTrue();
  }

  @ParameterizedTest
  @EnumSource(
      value = RunState.class,
      names = {"CREATED", "QUEUED", "RUNNING", "WAITING", "RETRYING"})
  void cancellationIsAcceptedFromEveryNonTerminalState(final RunState from) {
    // SM-7: a run has no veto over cancellation.
    assertThat(RunStateMachine.cancellable(from)).isTrue();
    assertThat(RunStateMachine.permits(from, RunState.CANCELLED)).isTrue();
  }

  @ParameterizedTest
  @EnumSource(
      value = RunState.class,
      names = {"COMPLETED", "CANCELLED", "TIMED_OUT", "FAILED", "DEAD_LETTER"})
  void aRunThatHasAlreadyEndedCannotBeCancelled(final RunState terminal) {
    assertThat(RunStateMachine.cancellable(terminal)).isFalse();
  }

  @ParameterizedTest
  @EnumSource(
      value = RunState.class,
      names = {"CREATED", "QUEUED", "RUNNING", "WAITING", "RETRYING"})
  void aBoundMayTerminateARunFromAnyNonTerminalStateIncludingTheWaitingOnes(final RunState from) {
    // SM-8. Without this a run parked on a timer outlives its wall-clock bound indefinitely, and
    // step 8 of the termination argument fails.
    assertThat(RunStateMachine.boundMayTerminate(from)).isTrue();
    assertThat(RunStateMachine.permits(from, RunState.TIMED_OUT)).isTrue();
  }

  @Test
  void aWaitingRunCanBeTimedOutEvenThoughItIsHoldingNothing() {
    assertThat(RunStateMachine.permits(RunState.WAITING, RunState.TIMED_OUT)).isTrue();
    assertThat(RunState.WAITING.holdsResources()).isFalse();
  }

  @Test
  void onlyRunningMayHoldASession() {
    // SM-3.
    for (final RunState state : RunState.values()) {
      assertThat(state.mayHoldSession()).isEqualTo(state == RunState.RUNNING);
    }
  }

  @Test
  void theWaitingStatesHoldNoExecutionResources() {
    // SM-6 is what lets a run park for hours at zero cost.
    assertThat(RunState.WAITING.holdsResources()).isFalse();
    assertThat(RunState.RETRYING.holdsResources()).isFalse();
    assertThat(RunState.QUEUED.holdsResources()).isFalse();
    assertThat(RunState.RUNNING.holdsResources()).isTrue();
  }

  @Test
  void requirePermittedRefusesAnIllegalTransitionRatherThanLoggingIt() {
    assertThatThrownBy(() -> RunStateMachine.requirePermitted(RunState.COMPLETED, RunState.RUNNING))
        .isInstanceOf(IllegalRunTransitionException.class)
        .hasMessageContaining("COMPLETED -> RUNNING");
  }

  @Test
  void theRefusalCarriesBothStatesSoACallerCanReportThemWithoutParsingTheMessage() {
    final IllegalRunTransitionException refused =
        new IllegalRunTransitionException(RunState.FAILED, RunState.QUEUED);
    assertThat(refused.from()).isEqualTo(RunState.FAILED);
    assertThat(refused.to()).isEqualTo(RunState.QUEUED);
  }

  @Test
  void theRefusalStillCarriesBothStatesAfterASerializationRoundTrip() throws Exception {
    // The test above only ever sees a freshly-constructed instance, so it could not catch this:
    // from and to were transient, and the class is serializable whether anyone intends it or not
    // (Throwable is) and declares a serialVersionUID. With no readObject to restore them, a
    // round-tripped refusal answered null from both accessors -- on the one exception whose entire
    // job is to say which transition was refused, and precisely when it is being carried somewhere
    // to be reported. Same defect shape as KmsException.reason.
    final IllegalRunTransitionException original =
        new IllegalRunTransitionException(RunState.COMPLETED, RunState.RUNNING);

    final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
    try (ObjectOutputStream out = new ObjectOutputStream(bytes)) {
      out.writeObject(original);
    }
    final IllegalRunTransitionException restored;
    try (ObjectInputStream in =
        new ObjectInputStream(new ByteArrayInputStream(bytes.toByteArray()))) {
      restored = (IllegalRunTransitionException) in.readObject();
    }

    assertThat(restored.from()).isEqualTo(RunState.COMPLETED);
    assertThat(restored.to()).isEqualTo(RunState.RUNNING);
    assertThat(restored.getMessage()).contains("COMPLETED -> RUNNING");
  }

  @Test
  void requirePermittedAcceptsALegalTransition() {
    RunStateMachine.requirePermitted(RunState.QUEUED, RunState.RUNNING);
  }

  @Test
  void theTerminalSetIsExactlyTheFiveEndingStates() {
    assertThat(RunState.TERMINAL_STATES)
        .containsExactlyInAnyOrder(
            RunState.COMPLETED,
            RunState.CANCELLED,
            RunState.TIMED_OUT,
            RunState.FAILED,
            RunState.DEAD_LETTER);
  }

  @Test
  void theSuccessorSetIsUnmodifiableSoNobodyCanWidenTheTableAtRuntime() {
    assertThatThrownBy(() -> RunStateMachine.successorsOf(RunState.QUEUED).add(RunState.COMPLETED))
        .isInstanceOf(UnsupportedOperationException.class);
  }

  @Test
  void everyStateAppearsInTheTable() {
    for (final RunState state : RunState.values()) {
      assertThat(RunStateMachine.successorsOf(state)).isNotNull();
    }
  }

  @Test
  void runningIsTheOnlyStateThatCanCompleteARunSuccessfully() {
    for (final RunState from : RunState.values()) {
      assertThat(RunStateMachine.permits(from, RunState.COMPLETED))
          .as("%s -> COMPLETED", from)
          .isEqualTo(from == RunState.RUNNING);
    }
  }

  private static boolean reachesTerminalFrom(final RunState start) {
    final Deque<RunState> frontier = new ArrayDeque<>();
    final Set<RunState> seen = EnumSet.of(start);
    frontier.add(start);
    while (!frontier.isEmpty()) {
      final RunState current = frontier.poll();
      if (current.terminal()) {
        return true;
      }
      for (final RunState next : RunStateMachine.successorsOf(current)) {
        if (seen.add(next)) {
          frontier.add(next);
        }
      }
    }
    return false;
  }
}

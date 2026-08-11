package io.reliabilityai.gateway.dataplane.agent.domain;

import static io.reliabilityai.gateway.dataplane.agent.AgentFixtures.T0;
import static io.reliabilityai.gateway.dataplane.agent.AgentFixtures.created;
import static io.reliabilityai.gateway.dataplane.agent.AgentFixtures.model;
import static io.reliabilityai.gateway.dataplane.agent.AgentFixtures.plan;
import static io.reliabilityai.gateway.dataplane.agent.AgentFixtures.security;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.reliabilityai.gateway.dataplane.agent.api.CancellationCause;
import io.reliabilityai.gateway.dataplane.agent.api.ConditionOperator;
import io.reliabilityai.gateway.dataplane.agent.api.FailureClass;
import io.reliabilityai.gateway.dataplane.agent.api.Plan;
import io.reliabilityai.gateway.dataplane.agent.api.RunEvent;
import io.reliabilityai.gateway.dataplane.agent.api.RunHistory;
import io.reliabilityai.gateway.dataplane.agent.api.RunId;
import io.reliabilityai.gateway.dataplane.agent.api.RunSnapshot;
import io.reliabilityai.gateway.dataplane.agent.api.RunState;
import io.reliabilityai.gateway.dataplane.agent.api.Step;
import io.reliabilityai.gateway.dataplane.agent.api.StepId;
import io.reliabilityai.gateway.dataplane.agent.api.StepKind;
import io.reliabilityai.gateway.dataplane.agent.api.SupervisionStrategy;
import io.reliabilityai.gateway.dataplane.agent.api.TerminalReason;
import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.DisplayNameGeneration;
import org.junit.jupiter.api.DisplayNameGenerator;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * The fold that reconstructs a run from its history.
 *
 * <p>Determinism is the property under test throughout. If two folds of one history could disagree,
 * crash recovery would be a guess and {@code REPLAY_DIVERGENCE} would be noise rather than a defect
 * signal.
 */
@DisplayNameGeneration(DisplayNameGenerator.ReplaceUnderscores.class)
class ReplayEngineTest {

  private static final RunId RUN = RunId.of("r-1");

  private static RunHistory historyOf(final Plan plan, final RunEvent... tail) {
    RunHistory history = RunHistory.empty(RUN).append(created(plan, security("chat", "search")));
    for (final RunEvent event : tail) {
      history = history.append(event);
    }
    return history;
  }

  private static RunEvent completed(final int index, final String name, final long cost) {
    return new RunEvent.StepCompleted(
        StepId.of(RUN, index), name, "out-" + name, "d", cost, false, T0);
  }

  private static RunEvent completedTainted(final int index, final String name) {
    return new RunEvent.StepCompleted(StepId.of(RUN, index), name, "out", "d", 0L, true, T0);
  }

  private static RunEvent scheduled(final int index, final int planIndex, final String name) {
    return new RunEvent.StepScheduled(
        StepId.of(RUN, index), planIndex, name, StepKind.PIPELINE, 1, "ref-" + index, T0);
  }

  @Test
  void aFreshHistoryFoldsToTheCreatedState() {
    final Plan plan = plan(model("a"));
    final RunSnapshot snapshot = ReplayEngine.fold(plan, historyOf(plan));
    assertThat(snapshot.state()).isEqualTo(RunState.CREATED);
    assertThat(snapshot.cursor().planStepIndex()).isZero();
    assertThat(snapshot.stepsExecuted()).isZero();
    assertThat(snapshot.terminal()).isFalse();
  }

  @Test
  void aHistoryThatDoesNotStartWithCreationIsRefusedRatherThanGuessedAt() {
    final RunHistory orphan = RunHistory.empty(RUN).append(new RunEvent.RunQueued(T0));
    assertThatThrownBy(() -> ReplayEngine.fold(plan(model("a")), orphan))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("run.created");
  }

  @Test
  void anEmptyHistoryIsRefused() {
    assertThatThrownBy(() -> ReplayEngine.fold(plan(model("a")), RunHistory.empty(RUN)))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void twoFoldsOfTheSameHistoryProduceIdenticalSnapshots() {
    final Plan plan = plan(model("a"), model("b"));
    final RunHistory history = historyOf(plan, scheduled(0, 0, "a"), completed(0, "a", 10L));
    assertThat(ReplayEngine.fold(plan, history)).isEqualTo(ReplayEngine.fold(plan, history));
  }

  @Test
  void foldingCostsNoProviderCallsBecauseItOnlyReadsRecordedResults() {
    // Not directly observable here — the point is that the engine has no port to call. This asserts
    // the consequence: a fold of a completed step returns the recorded value, not a fresh one.
    final Plan plan = plan(model("a"));
    final RunSnapshot snapshot =
        ReplayEngine.fold(plan, historyOf(plan, scheduled(0, 0, "a"), completed(0, "a", 3L)));
    assertThat(snapshot.resultOf("a")).contains("out-a");
  }

  @Test
  void aCompletedStepAdvancesTheCursorAndAccumulatesItsCost() {
    final Plan plan = plan(model("a"), model("b"));
    final RunSnapshot snapshot =
        ReplayEngine.fold(plan, historyOf(plan, scheduled(0, 0, "a"), completed(0, "a", 250L)));
    assertThat(snapshot.cursor().planStepIndex()).isEqualTo(1);
    assertThat(snapshot.stepsExecuted()).isEqualTo(1);
    assertThat(snapshot.budget().consumedMicros()).isEqualTo(250L);
    assertThat(snapshot.state()).isEqualTo(RunState.QUEUED);
  }

  @Test
  void aFailedStepDoesNotAdvanceTheCursorBecauseTheSupervisorHasNotDecidedYet() {
    final Plan plan = plan(model("a"), model("b"));
    final RunHistory history =
        historyOf(
            plan,
            scheduled(0, 0, "a"),
            new RunEvent.StepFailed(
                StepId.of(RUN, 0), "a", FailureClass.STEP_TRANSIENT, "boom", 5L, T0));
    final RunSnapshot snapshot = ReplayEngine.fold(plan, history);
    assertThat(snapshot.cursor().planStepIndex()).isZero();
    assertThat(snapshot.budget().consumedMicros()).isEqualTo(5L);
  }

  @Test
  void aFailedStepStillCostsMoneyAndTheLedgerSaysSo() {
    final Plan plan = plan(model("a"));
    final RunSnapshot snapshot =
        ReplayEngine.fold(
            plan,
            historyOf(
                plan,
                scheduled(0, 0, "a"),
                new RunEvent.StepFailed(
                    StepId.of(RUN, 0), "a", FailureClass.STEP_PERMANENT, "bad", 99L, T0)));
    assertThat(snapshot.budget().consumedMicros()).isEqualTo(99L);
  }

  @Test
  void aRetryDecisionMovesTheRunToRetryingAndRecordsARestartInstant() {
    final Plan plan = plan(model("a"));
    final RunHistory history =
        historyOf(
            plan,
            scheduled(0, 0, "a"),
            new RunEvent.StepFailed(
                StepId.of(RUN, 0), "a", FailureClass.STEP_TRANSIENT, "boom", 1L, T0),
            new RunEvent.SupervisionApplied(
                StepId.of(RUN, 0), SupervisionStrategy.RETRY_STEP, "retry", 0, T0));
    final RunSnapshot snapshot = ReplayEngine.fold(plan, history);
    assertThat(snapshot.state()).isEqualTo(RunState.RETRYING);
    assertThat(snapshot.restartTimes()).containsExactly(T0);
    assertThat(snapshot.cursor().attempt()).isEqualTo(2);
    assertThat(snapshot.cursor().planStepIndex()).isZero();
  }

  @Test
  void aRetryTakesADistinctExecutionIndexSoItGetsItsOwnStepIdentity() {
    final Plan plan = plan(model("a"));
    final RunHistory history =
        historyOf(
            plan,
            scheduled(0, 0, "a"),
            new RunEvent.StepFailed(
                StepId.of(RUN, 0), "a", FailureClass.STEP_TRANSIENT, "boom", 1L, T0),
            new RunEvent.SupervisionApplied(
                StepId.of(RUN, 0), SupervisionStrategy.RETRY_STEP, "retry", 0, T0));
    assertThat(ReplayEngine.fold(plan, history).cursor().executionIndex()).isEqualTo(1);
  }

  @Test
  void aSkipDecisionAdvancesPastTheFailedStep() {
    final Plan plan = plan(model("a"), model("b"));
    final RunHistory history =
        historyOf(
            plan,
            scheduled(0, 0, "a"),
            new RunEvent.StepFailed(
                StepId.of(RUN, 0), "a", FailureClass.TOOL_FAILURE, "boom", 0L, T0),
            new RunEvent.SupervisionApplied(
                StepId.of(RUN, 0), SupervisionStrategy.SKIP_STEP, "skip", 0, T0));
    assertThat(ReplayEngine.fold(plan, history).cursor().planStepIndex()).isEqualTo(1);
  }

  @Test
  void taintIsMonotoneAndNeverDecaysAcrossAWholeRun() {
    // AGT-22: there is no "it was several steps ago" exemption, because both a model's context and
    // a
    // run's recorded results persist.
    final Plan plan = plan(model("a"), model("b"), model("c"));
    final RunHistory history =
        historyOf(
            plan,
            scheduled(0, 0, "a"),
            completedTainted(0, "a"),
            scheduled(1, 1, "b"),
            completed(1, "b", 0L),
            scheduled(2, 2, "c"),
            completed(2, "c", 0L));
    assertThat(ReplayEngine.fold(plan, history).tainted()).isTrue();
  }

  @Test
  void anUntaintedRunStaysUntainted() {
    final Plan plan = plan(model("a"));
    final RunHistory history = historyOf(plan, scheduled(0, 0, "a"), completed(0, "a", 0L));
    assertThat(ReplayEngine.fold(plan, history).tainted()).isFalse();
  }

  @Test
  void aTaintedToolArtifactTaintsTheRun() {
    final Plan plan = plan(model("a"));
    final RunHistory history =
        historyOf(
            plan,
            scheduled(0, 0, "a"),
            new RunEvent.ToolArtifactProduced(StepId.of(RUN, 0), "art", "search", "dig", true, T0));
    final RunSnapshot snapshot = ReplayEngine.fold(plan, history);
    assertThat(snapshot.tainted()).isTrue();
    assertThat(snapshot.artifacts()).containsKey("art");
  }

  @Test
  void aBranchRecordsItsOutcomeAndJumpsToTheChosenTarget() {
    final Step branch = new Step.Branch("pick", "a", ConditionOperator.EXISTS, "", "a", "z");
    final Plan plan = plan(model("a"), branch, model("z"));
    final RunHistory history =
        historyOf(
            plan,
            scheduled(0, 0, "a"),
            completed(0, "a", 0L),
            new RunEvent.BranchTaken(StepId.of(RUN, 1), "pick", false, "z", T0));
    final RunSnapshot snapshot = ReplayEngine.fold(plan, history);
    assertThat(snapshot.cursor().planStepIndex()).isEqualTo(plan.indexOf("z"));
    assertThat(snapshot.resultOf("pick")).contains("false");
  }

  @Test
  void parkingSetsAWakeInstantAndHoldsNothing() {
    final Plan plan = plan(model("a"));
    final Instant wake = T0.plus(Duration.ofHours(6));
    final RunSnapshot snapshot =
        ReplayEngine.fold(plan, historyOf(plan, new RunEvent.RunParked(wake, T0)));
    assertThat(snapshot.state()).isEqualTo(RunState.WAITING);
    assertThat(snapshot.wakeAt()).contains(wake);
    assertThat(snapshot.state().holdsResources()).isFalse();
  }

  @Test
  void resumingAccumulatesParkedTimeSoTheDeadlineExcludesIt() {
    final Plan plan = plan(model("a"));
    final RunHistory history =
        historyOf(
            plan,
            new RunEvent.RunParked(T0.plusSeconds(60), T0),
            new RunEvent.RunResumed(Duration.ofHours(6).toNanos(), T0.plusSeconds(60)));
    final RunSnapshot snapshot = ReplayEngine.fold(plan, history);
    assertThat(snapshot.parkedNanos()).isEqualTo(Duration.ofHours(6).toNanos());
    assertThat(snapshot.wakeAt()).isEmpty();
    assertThat(snapshot.timeout().consumedAt(T0.plus(Duration.ofHours(6))))
        .isEqualTo(Duration.ZERO);
  }

  @Test
  void aCheckpointMarkerChangesNothingAboutTheFold() {
    // If folding it changed state, the checkpoint would be state rather than a pointer, and there
    // would be two things able to disagree about where a run is.
    final Plan plan = plan(model("a"));
    final RunHistory withoutMarker = historyOf(plan, scheduled(0, 0, "a"), completed(0, "a", 1L));
    final RunHistory withMarker =
        withoutMarker.append(new RunEvent.RunCheckpointed(withoutMarker.offset(), T0));
    final RunSnapshot before = ReplayEngine.fold(plan, withoutMarker);
    final RunSnapshot after = ReplayEngine.fold(plan, withMarker);
    assertThat(after.state()).isEqualTo(before.state());
    assertThat(after.cursor().planStepIndex()).isEqualTo(before.cursor().planStepIndex());
    assertThat(after.stepsExecuted()).isEqualTo(before.stepsExecuted());
  }

  @Test
  void aCancellationIsVisibleInTheSnapshotBeforeTheRunActuallyStops() {
    final Plan plan = plan(model("a"));
    final RunSnapshot snapshot =
        ReplayEngine.fold(
            plan, historyOf(plan, new RunEvent.RunCancelled(CancellationCause.USER, "stop", T0)));
    assertThat(snapshot.cancellation()).isPresent();
    assertThat(snapshot.cancellation().orElseThrow().cause()).isEqualTo(CancellationCause.USER);
    assertThat(snapshot.terminal()).isFalse();
  }

  @Test
  void aTerminationFoldsToTheAbsorbingStateAndItsReason() {
    final Plan plan = plan(model("a"));
    final RunSnapshot snapshot =
        ReplayEngine.fold(
            plan,
            historyOf(
                plan, RunEvent.RunTerminated.of(TerminalReason.COMPLETED_SUCCESS, 1, 5L, T0)));
    assertThat(snapshot.state()).isEqualTo(RunState.COMPLETED);
    assertThat(snapshot.terminalReason()).contains(TerminalReason.COMPLETED_SUCCESS);
    assertThat(snapshot.terminal()).isTrue();
    assertThat(snapshot.claimableAt(T0)).isFalse();
  }

  @Test
  void foldingZeroEventsIsRefusedBecauseThereIsNoCreationRecordToExplainTheRun() {
    final Plan plan = plan(model("a"));
    final RunHistory history = historyOf(plan, scheduled(0, 0, "a"));
    assertThatThrownBy(() -> ReplayEngine.foldTo(plan, history, 0L))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @ParameterizedTest
  @ValueSource(ints = {1, 2, 3, 4, 5})
  void foldingAPrefixReproducesTheStateAtThatOffset(final int upTo) {
    final Plan plan = plan(model("a"), model("b"));
    final RunHistory history =
        historyOf(
            plan,
            scheduled(0, 0, "a"),
            completed(0, "a", 1L),
            scheduled(1, 1, "b"),
            completed(1, "b", 1L));
    final RunSnapshot prefix = ReplayEngine.foldTo(plan, history, upTo);
    assertThat(prefix.cursor().historyOffset()).isEqualTo(Math.min(upTo, history.offset()));
  }

  @Test
  void foldingBeyondTheEndIsClampedRatherThanThrowing() {
    final Plan plan = plan(model("a"));
    final RunHistory history = historyOf(plan);
    assertThat(ReplayEngine.foldTo(plan, history, 999L).cursor().historyOffset())
        .isEqualTo(history.offset());
  }

  @Test
  void theStepIdentityIsDerivedFromTheCursorAndNeverGenerated() {
    // A random identifier would make two replays of the same history produce different ids, which
    // would make divergence detection impossible.
    final io.reliabilityai.gateway.dataplane.agent.api.ExecutionCursor cursor =
        new io.reliabilityai.gateway.dataplane.agent.api.ExecutionCursor(2, 7, 1, 9L);
    assertThat(ReplayEngine.stepIdAt(RUN, cursor)).isEqualTo(StepId.of(RUN, 7));
    assertThat(ReplayEngine.stepIdAt(RUN, cursor)).isEqualTo(ReplayEngine.stepIdAt(RUN, cursor));
  }

  @Test
  void agreementIsExactIdentityRatherThanApproximateMatching() {
    assertThat(ReplayEngine.agrees(StepId.of(RUN, 3), StepId.of(RUN, 3))).isTrue();
    assertThat(ReplayEngine.agrees(StepId.of(RUN, 3), StepId.of(RUN, 4))).isFalse();
    assertThat(ReplayEngine.agrees(StepId.of(RUN, 3), StepId.of(RunId.of("other"), 3))).isFalse();
  }

  @Test
  void aHistoryEndingInAScheduledStepIsRecognisedAsInterrupted() {
    final Plan plan = plan(model("a"));
    final RunHistory history = historyOf(plan, scheduled(0, 0, "a"));
    assertThat(history.endsInterrupted()).isTrue();
    assertThat(history.interruptedStep()).isPresent();
  }

  @Test
  void aHistoryEndingInACompletedStepIsNotInterrupted() {
    final Plan plan = plan(model("a"));
    final RunHistory history = historyOf(plan, scheduled(0, 0, "a"), completed(0, "a", 0L));
    assertThat(history.endsInterrupted()).isFalse();
    assertThat(history.interruptedStep()).isEmpty();
  }

  @Test
  void aHistoryIsImmutableAndAppendingReturnsANewOne() {
    final Plan plan = plan(model("a"));
    final RunHistory original = historyOf(plan);
    final RunHistory extended = original.append(new RunEvent.RunQueued(T0));
    assertThat(original.offset()).isEqualTo(1L);
    assertThat(extended.offset()).isEqualTo(2L);
    assertThatThrownBy(() -> original.events().add(new RunEvent.RunQueued(T0)))
        .isInstanceOf(UnsupportedOperationException.class);
  }

  @Test
  void aConsumerCanReadTheHistoryFromAnOffsetToReconnectAStream() {
    final Plan plan = plan(model("a"));
    final RunHistory history = historyOf(plan, scheduled(0, 0, "a"), completed(0, "a", 0L));
    assertThat(history.since(1L)).hasSize(2);
    assertThat(history.since(3L)).isEmpty();
    assertThat(history.since(99L)).isEmpty();
  }

  @Test
  void aRunKnowsWhichStepComesNextAndWhenThePlanIsExhausted() {
    final Plan plan = plan(model("a"), model("b"));
    final RunHistory history =
        historyOf(
            plan,
            scheduled(0, 0, "a"),
            completed(0, "a", 0L),
            scheduled(1, 1, "b"),
            completed(1, "b", 0L));
    final io.reliabilityai.gateway.dataplane.agent.api.Run run =
        new io.reliabilityai.gateway.dataplane.agent.api.Run(
            RUN, plan, ReplayEngine.fold(plan, history));
    assertThat(run.planExhausted()).isTrue();
    assertThat(run.nextStep()).isEmpty();
  }

  @Test
  void handingARunTheWrongPlanVersionIsRefusedWhereItIsCheapToSee() {
    final Plan pinned = plan(model("a"));
    final Plan other = plan(2, pinned.bounds(), pinned.restartPolicy(), model("a"));
    final RunSnapshot snapshot = ReplayEngine.fold(pinned, historyOf(pinned));
    assertThatThrownBy(
            () -> new io.reliabilityai.gateway.dataplane.agent.api.Run(RUN, other, snapshot))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("pinned");
  }

  @Test
  void aSnapshotBelongingToAnotherRunIsRefused() {
    final Plan plan = plan(model("a"));
    final RunSnapshot snapshot = ReplayEngine.fold(plan, historyOf(plan));
    assertThatThrownBy(
            () ->
                new io.reliabilityai.gateway.dataplane.agent.api.Run(
                    RunId.of("someone-else"), plan, snapshot))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void aParkedRunIsNotClaimableUntilItsWakeInstant() {
    final Plan plan = plan(model("a"));
    final Instant wake = T0.plusSeconds(600);
    final RunSnapshot snapshot =
        ReplayEngine.fold(plan, historyOf(plan, new RunEvent.RunParked(wake, T0)));
    assertThat(snapshot.claimableAt(T0)).isFalse();
    assertThat(snapshot.claimableAt(wake)).isTrue();
    assertThat(snapshot.claimableAt(wake.plusSeconds(1))).isTrue();
  }

  @Test
  void aSnapshotProducesACheckpointAtItsOwnOffset() {
    final Plan plan = plan(model("a"));
    final RunHistory history = historyOf(plan, scheduled(0, 0, "a"), completed(0, "a", 0L));
    final RunSnapshot snapshot = ReplayEngine.fold(plan, history);
    assertThat(snapshot.checkpoint(T0).historyOffset()).isEqualTo(history.offset());
    assertThat(snapshot.checkpoint(T0).resumable()).isTrue();
  }
}

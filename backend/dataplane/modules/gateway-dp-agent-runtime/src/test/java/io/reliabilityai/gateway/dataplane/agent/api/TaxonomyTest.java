package io.reliabilityai.gateway.dataplane.agent.api;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.EnumSet;
import java.util.HashSet;
import java.util.Set;
import org.junit.jupiter.api.DisplayNameGeneration;
import org.junit.jupiter.api.DisplayNameGenerator;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/**
 * The closed failure taxonomy and the closed set of terminal reasons.
 *
 * <p>Both are closed on purpose (AD-025 §67.2). These tests are what makes "closed" mean something:
 * a new constant added without a mapping, or a retryability decision changed by accident, fails
 * here rather than in production six weeks later.
 */
@DisplayNameGeneration(DisplayNameGenerator.ReplaceUnderscores.class)
class TaxonomyTest {

  @ParameterizedTest
  @EnumSource(FailureClass.class)
  void everyFailureClassMapsToExactlyOneTerminalReason(final FailureClass failure) {
    assertThat(TerminalReason.forFailure(failure)).isNotNull();
  }

  @ParameterizedTest
  @EnumSource(FailureClass.class)
  void theMappingIsStableAcrossCalls(final FailureClass failure) {
    assertThat(TerminalReason.forFailure(failure)).isEqualTo(TerminalReason.forFailure(failure));
  }

  @ParameterizedTest
  @EnumSource(TerminalReason.class)
  void everyTerminalReasonResolvesToAnAbsorbingState(final TerminalReason reason) {
    assertThat(reason.state().terminal()).isTrue();
  }

  @ParameterizedTest
  @EnumSource(TerminalReason.class)
  void noTerminalReasonResolvesToARunningOrWaitingState(final TerminalReason reason) {
    assertThat(reason.state())
        .isNotIn(
            RunState.CREATED,
            RunState.QUEUED,
            RunState.RUNNING,
            RunState.WAITING,
            RunState.RETRYING);
  }

  @Test
  void onlyTheTwoCompletionReasonsCountAsSuccess() {
    final Set<TerminalReason> successful = EnumSet.noneOf(TerminalReason.class);
    for (final TerminalReason reason : TerminalReason.values()) {
      if (reason.successful()) {
        successful.add(reason);
      }
    }
    assertThat(successful)
        .containsExactlyInAnyOrder(
            TerminalReason.COMPLETED_SUCCESS, TerminalReason.COMPLETED_PARTIAL);
  }

  @Test
  void theIntegrityFailuresAreNeverRetryable() {
    // Retrying into a known-corrupt run is retrying into a lie about what happened.
    assertThat(FailureClass.TRIFECTA_VIOLATION.retryable()).isFalse();
    assertThat(FailureClass.REPLAY_DIVERGENCE.retryable()).isFalse();
    assertThat(FailureClass.TRIFECTA_VIOLATION.integrity()).isTrue();
    assertThat(FailureClass.REPLAY_DIVERGENCE.integrity()).isTrue();
  }

  @Test
  void integrityFailuresAreParkedRatherThanMerelyFailed() {
    // DEAD_LETTER, not FAILED: an integrity breach wants a human, and a failed run looks routine.
    assertThat(TerminalReason.forFailure(FailureClass.TRIFECTA_VIOLATION).state())
        .isEqualTo(RunState.DEAD_LETTER);
    assertThat(TerminalReason.forFailure(FailureClass.REPLAY_DIVERGENCE).state())
        .isEqualTo(RunState.DEAD_LETTER);
  }

  @Test
  void aCrashLoopIsDeadLetteredRatherThanRetriedForever() {
    assertThat(TerminalReason.forFailure(FailureClass.RESTART_INTENSITY_EXCEEDED))
        .isEqualTo(TerminalReason.FAILED_RESTART_INTENSITY);
    assertThat(TerminalReason.FAILED_RESTART_INTENSITY.state()).isEqualTo(RunState.DEAD_LETTER);
  }

  @ParameterizedTest
  @EnumSource(
      value = FailureClass.class,
      names = {"STEP_TRANSIENT", "STEP_TIMEOUT", "TOOL_FAILURE", "STORE_UNAVAILABLE"})
  void theTransientClassesAreTheOnlyRetryableOnes(final FailureClass failure) {
    assertThat(failure.retryable()).isTrue();
  }

  @ParameterizedTest
  @EnumSource(
      value = FailureClass.class,
      names = {"STEP_TRANSIENT", "STEP_TIMEOUT", "TOOL_FAILURE", "STORE_UNAVAILABLE"},
      mode = EnumSource.Mode.EXCLUDE)
  void everyOtherFailureClassIsNotRetryable(final FailureClass failure) {
    assertThat(failure.retryable()).isFalse();
  }

  @Test
  void aGovernanceDenialIsNotRetryableBecauseItWouldBeDeniedIdentically() {
    assertThat(FailureClass.STEP_DENIED.retryable()).isFalse();
    assertThat(FailureClass.ADMISSION_DENIED.retryable()).isFalse();
  }

  @Test
  void anExhaustedBoundIsNotRetryableBecauseNothingReplenishesIt() {
    assertThat(FailureClass.BOUND_EXCEEDED.retryable()).isFalse();
    assertThat(FailureClass.BUDGET_EXHAUSTED.retryable()).isFalse();
  }

  @Test
  void anUnansweredApprovalIsNeverTreatedAsConsent() {
    assertThat(FailureClass.APPROVAL_EXPIRED.retryable()).isFalse();
    assertThat(TerminalReason.forFailure(FailureClass.APPROVAL_EXPIRED))
        .isEqualTo(TerminalReason.TERMINATED_APPROVAL);
  }

  @Test
  void everyTerminalReasonIsReachableFromAtLeastOneFailureOrIsACompletion() {
    final Set<TerminalReason> mapped = new HashSet<>();
    for (final FailureClass failure : FailureClass.values()) {
      mapped.add(TerminalReason.forFailure(failure));
    }
    mapped.add(TerminalReason.COMPLETED_SUCCESS);
    mapped.add(TerminalReason.COMPLETED_PARTIAL);
    // CANCELLED_PARENT and CANCELLED_OPERATOR arrive through CancellationCause rather than a
    // failure.
    mapped.add(TerminalReason.CANCELLED_PARENT);
    mapped.add(TerminalReason.CANCELLED_OPERATOR);
    assertThat(mapped).containsAll(EnumSet.allOf(TerminalReason.class));
  }

  @ParameterizedTest
  @EnumSource(CancellationCause.class)
  void everyCancellationCauseMapsToATerminalReason(final CancellationCause cause) {
    assertThat(cause.terminalReason()).isNotNull();
    assertThat(cause.terminalReason().state().terminal()).isTrue();
  }

  @Test
  void theExternalCausesAreTheOnesSomebodyOutsideTheRuntimeChose() {
    assertThat(CancellationCause.USER.external()).isTrue();
    assertThat(CancellationCause.PARENT.external()).isTrue();
    assertThat(CancellationCause.OPERATOR.external()).isTrue();
    assertThat(CancellationCause.TENANT.external()).isTrue();
    assertThat(CancellationCause.TIMEOUT.external()).isFalse();
    assertThat(CancellationCause.SUPERVISOR.external()).isFalse();
    assertThat(CancellationCause.POLICY.external()).isFalse();
  }

  @Test
  void aCancellationNeverAuthorisesBillingForWorkAfterIt() {
    final RunCancellation cancellation =
        RunCancellation.of(CancellationCause.USER, "user asked", java.time.Instant.EPOCH);
    assertThat(cancellation.billsSubsequentWork()).isFalse();
    assertThat(cancellation.terminalReason()).isEqualTo(TerminalReason.CANCELLED_CALLER);
  }

  @ParameterizedTest
  @EnumSource(StepStatus.class)
  void aStepStatusIsEitherTerminalOrOneOfTheThreeLiveOnes(final StepStatus status) {
    final boolean live =
        status == StepStatus.PENDING
            || status == StepStatus.SCHEDULED
            || status == StepStatus.RUNNING;
    assertThat(status.terminal()).isEqualTo(!live);
  }

  @ParameterizedTest
  @EnumSource(StepKind.class)
  void onlyTheKindsThatReachOutsideTheRuntimeAreNonDeterministic(final StepKind kind) {
    final boolean reachesOut = kind == StepKind.PIPELINE || kind == StepKind.PLUGIN;
    assertThat(kind.nonDeterministic()).isEqualTo(reachesOut);
  }

  @Test
  void thePureKindsAreTheOnesSafeToRecomputeDuringRecovery() {
    assertThat(StepKind.WAIT.nonDeterministic()).isFalse();
    assertThat(StepKind.CONDITION.nonDeterministic()).isFalse();
    assertThat(StepKind.BRANCH.nonDeterministic()).isFalse();
  }

  @ParameterizedTest
  @EnumSource(SupervisionStrategy.class)
  void onlyTheStrategiesThatCanProduceAnotherStepAreSurvivable(final SupervisionStrategy strategy) {
    final boolean canContinue =
        strategy == SupervisionStrategy.RETRY_STEP
            || strategy == SupervisionStrategy.SKIP_STEP
            || strategy == SupervisionStrategy.RESTART_FROM;
    assertThat(strategy.survivable()).isEqualTo(canContinue);
  }

  @Test
  void thereIsNoOneForAllStrategyBecauseCompletedStepsHaveAlreadyBeenPaidFor() {
    final Set<String> names = new HashSet<>();
    for (final SupervisionStrategy strategy : SupervisionStrategy.values()) {
      names.add(strategy.name());
    }
    assertThat(names)
        .containsExactlyInAnyOrder(
            "FAIL_RUN", "RETRY_STEP", "SKIP_STEP", "RESTART_FROM", "ESCALATE", "COMPENSATE");
  }
}

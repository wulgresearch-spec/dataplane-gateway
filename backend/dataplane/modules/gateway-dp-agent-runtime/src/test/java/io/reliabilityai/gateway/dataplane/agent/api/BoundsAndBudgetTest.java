package io.reliabilityai.gateway.dataplane.agent.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.DisplayNameGeneration;
import org.junit.jupiter.api.DisplayNameGenerator;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * The five bounds, the budget cascade, the retry budget and the restart intensity.
 *
 * <p>Together these are the premises of AD-025 §66's termination argument. Each test here
 * corresponds to a line of that argument; if one of them can be made to fail, the guarantee that
 * every run terminates is not a guarantee.
 */
@DisplayNameGeneration(DisplayNameGenerator.ReplaceUnderscores.class)
class BoundsAndBudgetTest {

  private static final Instant T0 = Instant.parse("2026-01-01T00:00:00Z");

  // ---- RunBounds ---------------------------------------------------------------------------

  @Test
  void theDefaultsAreTheConservativeOnesTheDesignChose() {
    assertThat(RunBounds.DEFAULT.maxSteps()).isEqualTo(50);
    assertThat(RunBounds.DEFAULT.maxDepth()).isEqualTo(3);
    assertThat(RunBounds.DEFAULT.maxFanout()).isEqualTo(5);
    assertThat(RunBounds.DEFAULT.wallClock()).isEqualTo(Duration.ofHours(1));
  }

  @ParameterizedTest
  @ValueSource(ints = {0, -1, -100})
  void aStepBoundBelowOneIsRefusedBecauseSuchARunCouldNeverDoAnything(final int steps) {
    assertThatThrownBy(() -> new RunBounds(steps, 3, 5, Duration.ofHours(1), 1L))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("maxSteps");
  }

  @Test
  void aStepBoundAboveTheAbsoluteCapIsRefusedSoMisconfigurationCannotUnboundARun() {
    assertThatThrownBy(
            () -> new RunBounds(RunBounds.ABSOLUTE_MAX_STEPS + 1, 3, 5, Duration.ofHours(1), 1L))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void aDepthAboveTheAbsoluteCapIsRefusedEvenThoughDepthIsPolicyConfigurable() {
    // A configurable bound with no ceiling is a bound only until somebody sets it to a million.
    assertThatThrownBy(
            () -> new RunBounds(10, RunBounds.ABSOLUTE_MAX_DEPTH + 1, 5, Duration.ofHours(1), 1L))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void aZeroWallClockIsRefusedBecauseAnUnboundedRunHasNoTerminationArgument() {
    assertThatThrownBy(() -> new RunBounds(10, 1, 1, Duration.ZERO, 1L))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("wallClock");
  }

  @Test
  void aNegativeSpendBoundIsRefused() {
    assertThatThrownBy(() -> new RunBounds(10, 1, 1, Duration.ofHours(1), -1L))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void aDepthOfZeroIsLegalAndMeansThisRunMayNotDelegate() {
    final RunBounds leaf = new RunBounds(10, 0, 1, Duration.ofHours(1), 1L);
    assertThat(leaf.mayDelegate()).isFalse();
    assertThatThrownBy(leaf::forChild).isInstanceOf(IllegalStateException.class);
  }

  @Test
  void tighteningTakesTheStricterOfEachDimension() {
    final RunBounds a = new RunBounds(50, 3, 5, Duration.ofHours(2), 900L);
    final RunBounds b = new RunBounds(10, 5, 2, Duration.ofHours(1), 5_000L);
    final RunBounds merged = a.tighten(b);
    assertThat(merged.maxSteps()).isEqualTo(10);
    assertThat(merged.maxDepth()).isEqualTo(3);
    assertThat(merged.maxFanout()).isEqualTo(2);
    assertThat(merged.wallClock()).isEqualTo(Duration.ofHours(1));
    assertThat(merged.spendMicros()).isEqualTo(900L);
  }

  @Test
  void tighteningIsIdempotent() {
    final RunBounds bounds = RunBounds.DEFAULT;
    assertThat(bounds.tighten(bounds)).isEqualTo(bounds);
  }

  @Test
  void tighteningIsCommutative() {
    final RunBounds a = new RunBounds(50, 3, 5, Duration.ofHours(2), 900L);
    final RunBounds b = new RunBounds(10, 5, 2, Duration.ofHours(1), 5_000L);
    assertThat(a.tighten(b)).isEqualTo(b.tighten(a));
  }

  @Test
  void tighteningNeverProducesLooserBoundsThanEitherInput() {
    final RunBounds a = new RunBounds(50, 3, 5, Duration.ofHours(2), 900L);
    final RunBounds b = new RunBounds(10, 5, 2, Duration.ofHours(1), 5_000L);
    final RunBounds merged = a.tighten(b);
    assertThat(merged.maxSteps())
        .isLessThanOrEqualTo(a.maxSteps())
        .isLessThanOrEqualTo(b.maxSteps());
    assertThat(merged.spendMicros())
        .isLessThanOrEqualTo(a.spendMicros())
        .isLessThanOrEqualTo(b.spendMicros());
  }

  @Test
  void aChildInheritsTheTreeWideBoundsAndOneLessDepth() {
    final RunBounds child = RunBounds.DEFAULT.forChild();
    assertThat(child.maxDepth()).isEqualTo(RunBounds.DEFAULT.maxDepth() - 1);
    assertThat(child.wallClock()).isEqualTo(RunBounds.DEFAULT.wallClock());
    assertThat(child.spendMicros()).isEqualTo(RunBounds.DEFAULT.spendMicros());
  }

  @Test
  void aChildKeepsItsOwnStepAllowanceBecauseATreeWideOneWouldStarveTheParent() {
    assertThat(RunBounds.DEFAULT.forChild().maxSteps()).isEqualTo(RunBounds.DEFAULT.maxSteps());
  }

  @Test
  void repeatedDelegationExhaustsTheDepthBoundInFiniteSteps() {
    RunBounds bounds = RunBounds.DEFAULT;
    int levels = 0;
    while (bounds.mayDelegate()) {
      bounds = bounds.forChild();
      levels++;
    }
    assertThat(levels).isEqualTo(RunBounds.DEFAULT.maxDepth());
  }

  // ---- RunBudget ---------------------------------------------------------------------------

  @Test
  void aFreshBudgetHoldsBackAReserveThatStepsCannotSpend() {
    final RunBudget budget = RunBudget.of(1_000_000L);
    assertThat(budget.reserveMicros()).isPositive();
    assertThat(budget.spendableMicros()).isLessThan(budget.grantMicros());
    assertThat(budget.spendableMicros() + budget.reserveMicros()).isEqualTo(budget.grantMicros());
  }

  @Test
  void theReserveIsWhatLetsAnExhaustedRunAffordToSayItIsExhausted() {
    RunBudget budget = RunBudget.of(1_000L);
    budget = budget.consume(budget.spendableMicros());
    assertThat(budget.exhausted()).isTrue();
    assertThat(budget.remainingMicros()).isEqualTo(budget.reserveMicros());
    assertThat(budget.overspent()).isFalse();
  }

  @Test
  void consumptionIsNotClampedSoAnOverrunStaysVisible() {
    // Recording less than the layer below reported would make the ledger a comfortable fiction.
    final RunBudget budget = RunBudget.of(100L).consume(500L);
    assertThat(budget.consumedMicros()).isEqualTo(500L);
    assertThat(budget.overspent()).isTrue();
    assertThat(budget.remainingMicros()).isZero();
  }

  @ParameterizedTest
  @CsvSource({"1000,0,true", "1000,980,true", "1000,981,false", "1000,5000,false"})
  void aStepIsAdmittedOnlyWhenTheSpendableRemainderCoversIt(
      final long grant, final long projected, final boolean admitted) {
    assertThat(RunBudget.of(grant).admits(projected)).isEqualTo(admitted);
  }

  @Test
  void aChildsGrantIsCarvedFromTheParentsRemainderAndNeverExceedsIt() {
    // AGT-19: delegation cannot create budget.
    final RunBudget parent = RunBudget.of(1_000L);
    final RunBudget child = parent.carveChild(10_000L);
    assertThat(child.grantMicros()).isLessThanOrEqualTo(parent.spendableMicros());
  }

  @Test
  void aChildOfAnExhaustedParentGetsNothing() {
    RunBudget parent = RunBudget.of(1_000L);
    parent = parent.consume(parent.spendableMicros());
    assertThat(parent.carveChild(500L).grantMicros()).isZero();
  }

  @Test
  void aTreeOfChildrenCannotCollectivelyExceedTheRootGrant() {
    // The property that makes depth 3 with fan-out 5 affordable rather than merely bounded.
    RunBudget root = RunBudget.of(1_000L);
    long carved = 0L;
    for (int i = 0; i < 10; i++) {
      final RunBudget child = root.carveChild(300L);
      carved += child.grantMicros();
      root = root.consume(child.grantMicros());
    }
    assertThat(carved).isLessThanOrEqualTo(1_000L);
  }

  @Test
  void aNegativeConsumptionIsRefused() {
    assertThatThrownBy(() -> RunBudget.of(100L).consume(-1L))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void aReserveLargerThanTheGrantIsRefused() {
    assertThatThrownBy(() -> new RunBudget(10L, 0L, 20L))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("reserve");
  }

  // ---- RetryBudget -------------------------------------------------------------------------

  @Test
  void aSingleAttemptBudgetIsExhaustedAfterOneUse() {
    assertThat(RetryBudget.SINGLE_ATTEMPT.exhausted()).isFalse();
    assertThat(RetryBudget.SINGLE_ATTEMPT.consume().exhausted()).isTrue();
  }

  @Test
  void consumingAnExhaustedBudgetThrowsRatherThanSilentlyAllowingAnotherAttempt() {
    final RetryBudget spent = RetryBudget.of(1).consume();
    assertThatThrownBy(spent::consume).isInstanceOf(IllegalStateException.class);
  }

  @ParameterizedTest
  @CsvSource({"3,0,3,1", "3,1,2,2", "3,2,1,3", "3,3,0,4"})
  void remainingAndNextAttemptTrackConsumption(
      final int max, final int consumed, final int remaining, final int next) {
    final RetryBudget budget = new RetryBudget(max, consumed);
    assertThat(budget.remaining()).isEqualTo(remaining);
    assertThat(budget.nextAttempt()).isEqualTo(next);
  }

  @Test
  void aBudgetConsumedBeyondItsMaximumIsUnrepresentable() {
    assertThatThrownBy(() -> new RetryBudget(2, 3)).isInstanceOf(IllegalArgumentException.class);
  }

  // ---- RestartIntensity --------------------------------------------------------------------

  @Test
  void anIntensityOfZeroRefusesTheFirstRestart() {
    assertThat(RestartIntensity.NONE.wouldExceed(List.of(), T0)).isTrue();
  }

  @Test
  void restartsInsideTheWindowCountAndOnesOutsideItDoNot() {
    final RestartIntensity intensity = new RestartIntensity(2, Duration.ofMinutes(1));
    final List<Instant> old = List.of(T0.minusSeconds(120), T0.minusSeconds(90));
    assertThat(intensity.countWithin(old, T0)).isZero();
    assertThat(intensity.wouldExceed(old, T0)).isFalse();
  }

  @Test
  void theWindowIsHalfOpenSoARestartExactlyOnePeriodOldHasAgedOut() {
    final RestartIntensity intensity = new RestartIntensity(1, Duration.ofMinutes(1));
    assertThat(intensity.countWithin(List.of(T0.minus(Duration.ofMinutes(1))), T0)).isZero();
  }

  @Test
  void aCrashLoopExceedsTheIntensityAndTerminatesRatherThanRestartingForever() {
    // The whole point of the bound: a per-step counter resets each loop iteration, this does not.
    final RestartIntensity intensity = new RestartIntensity(3, Duration.ofMinutes(1));
    final List<Instant> burst = List.of(T0.minusSeconds(3), T0.minusSeconds(2), T0.minusSeconds(1));
    assertThat(intensity.wouldExceed(burst, T0)).isTrue();
  }

  @Test
  void aSlowTrickleOfRestartsNeverExceedsTheIntensity() {
    final RestartIntensity intensity = new RestartIntensity(2, Duration.ofMinutes(1));
    Instant now = T0;
    final List<Instant> restarts = new java.util.ArrayList<>();
    for (int i = 0; i < 20; i++) {
      assertThat(intensity.wouldExceed(restarts, now)).isFalse();
      restarts.add(now);
      now = now.plus(Duration.ofMinutes(2));
    }
  }

  @Test
  void aZeroPeriodIsRefusedBecauseNoRestartCouldEverFallInsideIt() {
    assertThatThrownBy(() -> new RestartIntensity(1, Duration.ZERO))
        .isInstanceOf(IllegalArgumentException.class);
  }

  // ---- RunTimeout --------------------------------------------------------------------------

  @Test
  void elapsedTimeCountsAgainstTheDeadline() {
    final RunTimeout timeout = RunTimeout.starting(T0, Duration.ofMinutes(10));
    assertThat(timeout.expired(T0.plus(Duration.ofMinutes(9)))).isFalse();
    assertThat(timeout.expired(T0.plus(Duration.ofMinutes(10)))).isTrue();
  }

  @Test
  void recordedWaitingDoesNotCountAgainstTheDeadline() {
    // Without this carve-out, every plan with a pause needs a deadline longer than the slowest
    // human.
    final RunTimeout parked =
        RunTimeout.starting(T0, Duration.ofMinutes(10)).plusWaited(Duration.ofHours(6));
    assertThat(parked.expired(T0.plus(Duration.ofHours(6).plusMinutes(9)))).isFalse();
    assertThat(parked.consumedAt(T0.plus(Duration.ofHours(6)))).isEqualTo(Duration.ZERO);
  }

  @Test
  void theAbsoluteDeadlineMovesLaterAsARunParks() {
    final RunTimeout timeout = RunTimeout.starting(T0, Duration.ofMinutes(10));
    final RunTimeout parked = timeout.plusWaited(Duration.ofMinutes(30));
    assertThat(parked.deadline()).isAfter(timeout.deadline());
  }

  @Test
  void remainingIsFlooredAtZeroRatherThanGoingNegative() {
    final RunTimeout timeout = RunTimeout.starting(T0, Duration.ofMinutes(1));
    assertThat(timeout.remainingAt(T0.plus(Duration.ofHours(1)))).isEqualTo(Duration.ZERO);
  }

  @Test
  void consumedIsFlooredAtZeroForAClockThatWentBackwards() {
    final RunTimeout timeout = RunTimeout.starting(T0, Duration.ofMinutes(1));
    assertThat(timeout.consumedAt(T0.minusSeconds(30))).isEqualTo(Duration.ZERO);
  }

  // ---- ExecutionCursor ---------------------------------------------------------------------

  @Test
  void advancingMovesBothIndicesAndResetsTheAttempt() {
    final ExecutionCursor advanced = new ExecutionCursor(2, 7, 3, 12L).advance();
    assertThat(advanced.planStepIndex()).isEqualTo(3);
    assertThat(advanced.executionIndex()).isEqualTo(8);
    assertThat(advanced.attempt()).isEqualTo(1);
  }

  @Test
  void retryingKeepsThePlanPositionButTakesANewExecutionIndex() {
    // The retry is its own node in the execution graph, so it gets its own step id and its own row
    // in
    // the history rather than overwriting the attempt that failed.
    final ExecutionCursor retried = new ExecutionCursor(2, 7, 1, 12L).retry();
    assertThat(retried.planStepIndex()).isEqualTo(2);
    assertThat(retried.executionIndex()).isEqualTo(8);
    assertThat(retried.attempt()).isEqualTo(2);
  }

  @Test
  void jumpingMovesToTheTargetAndStartsAFreshAttempt() {
    final ExecutionCursor jumped = new ExecutionCursor(9, 20, 4, 30L).jumpTo(1);
    assertThat(jumped.planStepIndex()).isEqualTo(1);
    assertThat(jumped.attempt()).isEqualTo(1);
    assertThat(jumped.executionIndex()).isEqualTo(21);
  }

  @Test
  void theHistoryOffsetMayNotGoBackwards() {
    assertThatThrownBy(() -> new ExecutionCursor(0, 0, 1, 10L).atOffset(9L))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("backwards");
  }

  @Test
  void aZeroAttemptIsUnrepresentableBecauseAttemptsAreOneBased() {
    assertThatThrownBy(() -> new ExecutionCursor(0, 0, 0, 0L))
        .isInstanceOf(IllegalArgumentException.class);
  }

  // ---- RunSecurityContext --------------------------------------------------------------------

  @Test
  void aStepReceivesTheIntersectionOfTheCeilingAndItsRequest() {
    final RunSecurityContext security =
        new RunSecurityContext(
            new io.reliabilityai.gateway.canonical.identity.PrincipalId("p"),
            io.reliabilityai.gateway.canonical.identity.TenantScope.of("o", "t"),
            new io.reliabilityai.gateway.canonical.identity.CorrelationId("c"),
            Set.of("chat", "search"));
    assertThat(security.grantFor(Set.of("search", "email"))).containsExactly("search");
  }

  @Test
  void narrowingProducesASubsetAndNeverASuperset() {
    // P-2: a parent cannot escape its own restrictions by delegating to a broader child.
    final RunSecurityContext parent =
        new RunSecurityContext(
            new io.reliabilityai.gateway.canonical.identity.PrincipalId("p"),
            io.reliabilityai.gateway.canonical.identity.TenantScope.of("o", "t"),
            new io.reliabilityai.gateway.canonical.identity.CorrelationId("c"),
            Set.of("chat"));
    final RunSecurityContext child = parent.narrowTo(Set.of("chat", "email", "delete"));
    assertThat(child.capabilities()).isSubsetOf(parent.capabilities());
    assertThat(child.permits("email")).isFalse();
  }

  @Test
  void capabilitiesAreHeldInADeterministicOrderSoDigestsAreStableAcrossNodes() {
    final RunSecurityContext one =
        new RunSecurityContext(
            new io.reliabilityai.gateway.canonical.identity.PrincipalId("p"),
            io.reliabilityai.gateway.canonical.identity.TenantScope.of("o", "t"),
            new io.reliabilityai.gateway.canonical.identity.CorrelationId("c"),
            Set.of("zeta", "alpha", "mid"));
    assertThat(one.capabilities()).containsExactly("alpha", "mid", "zeta");
  }

  @Test
  void twoContextsInDifferentTenantsAreNotTheSameTenant() {
    final RunSecurityContext a =
        new RunSecurityContext(
            new io.reliabilityai.gateway.canonical.identity.PrincipalId("p"),
            io.reliabilityai.gateway.canonical.identity.TenantScope.of("o", "t1"),
            new io.reliabilityai.gateway.canonical.identity.CorrelationId("c"),
            Set.of());
    final RunSecurityContext b =
        new RunSecurityContext(
            new io.reliabilityai.gateway.canonical.identity.PrincipalId("p"),
            io.reliabilityai.gateway.canonical.identity.TenantScope.of("o", "t2"),
            new io.reliabilityai.gateway.canonical.identity.CorrelationId("c"),
            Set.of());
    assertThat(a.sameTenantAs(b)).isFalse();
    assertThat(a.sameTenantAs(a)).isTrue();
  }
}

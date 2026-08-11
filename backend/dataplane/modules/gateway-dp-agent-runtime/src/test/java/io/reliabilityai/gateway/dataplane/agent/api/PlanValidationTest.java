package io.reliabilityai.gateway.dataplane.agent.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.DisplayNameGeneration;
import org.junit.jupiter.api.DisplayNameGenerator;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Plan validation, which happens at construction and therefore at publication.
 *
 * <p>The point of validating here rather than at run time is that an invalid plan can never start a
 * run — so no run ever fails at step thirty for a mistake somebody could have been told about days
 * earlier, after the first twenty-nine steps have been paid for.
 */
@DisplayNameGeneration(DisplayNameGenerator.ReplaceUnderscores.class)
class PlanValidationTest {

  private static Step.Pipeline model(final String name, final String... inputs) {
    return new Step.Pipeline(
        name, "i", List.of(inputs), Set.of("chat"), 10L, 5L, Duration.ofSeconds(30), true);
  }

  private static Plan planOf(final Step... steps) {
    return new Plan(PlanId.of("p"), 1, List.of(steps), RunBounds.DEFAULT, RestartPolicy.STRICT);
  }

  @Test
  void aPlanWithNoStepsIsRefused() {
    assertThatThrownBy(
            () -> new Plan(PlanId.of("p"), 1, List.of(), RunBounds.DEFAULT, RestartPolicy.STRICT))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("at least one step");
  }

  @ParameterizedTest
  @ValueSource(ints = {0, -1})
  void aPlanVersionBelowOneIsRefused(final int version) {
    assertThatThrownBy(
            () ->
                new Plan(
                    PlanId.of("p"),
                    version,
                    List.of(model("a")),
                    RunBounds.DEFAULT,
                    RestartPolicy.STRICT))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("version");
  }

  @Test
  void duplicateStepNamesAreRefusedBecauseABranchTargetWouldBeAmbiguous() {
    assertThatThrownBy(() -> planOf(model("a"), model("a")))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("duplicate step name");
  }

  @Test
  void aDanglingInputReferenceIsRefusedAtPublicationRatherThanAtStepThirty() {
    assertThatThrownBy(() -> planOf(model("a"), model("b", "nowhere")))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("unknown step 'nowhere'");
  }

  @Test
  void aDanglingBranchTargetIsRefused() {
    final Step branch = new Step.Branch("b", "a", ConditionOperator.EXISTS, "", "a", "missing");
    assertThatThrownBy(() -> planOf(model("a"), branch))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("targets unknown step 'missing'");
  }

  @Test
  void aDanglingConditionSubjectIsRefused() {
    final Step condition = new Step.Condition("c", "ghost", ConditionOperator.EXISTS, "");
    assertThatThrownBy(() -> planOf(model("a"), condition))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("unknown step 'ghost'");
  }

  @Test
  void aPlanLongerThanItsStepBoundIsRefusedBecauseItCouldNeverComplete() {
    final RunBounds tiny = new RunBounds(1, 1, 1, Duration.ofMinutes(1), 100L);
    assertThatThrownBy(
            () ->
                new Plan(
                    PlanId.of("p"), 1, List.of(model("a"), model("b")), tiny, RestartPolicy.STRICT))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("step bound");
  }

  @Test
  void aRestartFromPolicyNamingNoStepOfThisPlanIsRefused() {
    final RestartPolicy rewind =
        new RestartPolicy(
            SupervisionStrategy.RESTART_FROM,
            RetryMode.NONE,
            1,
            Duration.ZERO,
            Duration.ZERO,
            RestartIntensity.NONE,
            "elsewhere",
            "");
    assertThatThrownBy(
            () -> new Plan(PlanId.of("p"), 1, List.of(model("a")), RunBounds.DEFAULT, rewind))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("restartFromStep");
  }

  @Test
  void aCompensatePolicyNamingNoStepOfThisPlanIsRefused() {
    final RestartPolicy compensate =
        new RestartPolicy(
            SupervisionStrategy.COMPENSATE,
            RetryMode.NONE,
            1,
            Duration.ZERO,
            Duration.ZERO,
            RestartIntensity.NONE,
            "",
            "undo");
    assertThatThrownBy(
            () -> new Plan(PlanId.of("p"), 1, List.of(model("a")), RunBounds.DEFAULT, compensate))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("compensationStep");
  }

  @Test
  void aWaitStepNeedsNoReferencesAndIsAlwaysValid() {
    final Plan plan = planOf(new Step.Wait("pause", Duration.ofSeconds(5)));
    assertThat(plan.size()).isEqualTo(1);
  }

  @Test
  void aPlanSListOfStepsIsUnmodifiableSoNothingCanMutateAPublishedVersion() {
    final Plan plan = planOf(model("a"));
    assertThatThrownBy(() -> plan.steps().add(model("b")))
        .isInstanceOf(UnsupportedOperationException.class);
  }

  @Test
  void lookupByNameFindsAStepAndMissesAnAbsentOne() {
    final Plan plan = planOf(model("a"), model("b"));
    assertThat(plan.stepNamed("b")).isPresent();
    assertThat(plan.stepNamed("z")).isEmpty();
    assertThat(plan.indexOf("b")).isEqualTo(1);
    assertThat(plan.indexOf("z")).isEqualTo(-1);
  }

  @Test
  void theDeclaredCapabilitiesAreTheUnionAcrossEveryStep() {
    final Step tool =
        new Step.Plugin("t", "search", "{}", List.of(), 1L, Duration.ofSeconds(5), true);
    final Plan plan = planOf(model("a"), tool);
    assertThat(plan.declaredCapabilities()).containsExactly("chat", "search");
  }

  @Test
  void theDeclaredBudgetIsTheSumOfTheStepsCeilings() {
    assertThat(planOf(model("a"), model("b")).declaredBudgetMicros()).isEqualTo(10L);
  }

  @Test
  void aPlanKnowsWhetherAnyOfItsStepsIsIrreversible() {
    final Step irreversible =
        new Step.Pipeline(
            "x", "i", List.of(), Set.of("chat"), 1L, 1L, Duration.ofSeconds(5), false);
    assertThat(planOf(model("a")).hasIrreversibleSteps()).isFalse();
    assertThat(planOf(model("a"), irreversible).hasIrreversibleSteps()).isTrue();
  }

  @Test
  void everyStepKindReportsItsOwnKind() {
    assertThat(model("a").kind()).isEqualTo(StepKind.PIPELINE);
    assertThat(new Step.Plugin("t", "c", "{}", List.of(), 0L, Duration.ofSeconds(1), true).kind())
        .isEqualTo(StepKind.PLUGIN);
    assertThat(new Step.Wait("w", Duration.ZERO).kind()).isEqualTo(StepKind.WAIT);
    assertThat(new Step.Condition("c", "a", ConditionOperator.EXISTS, "").kind())
        .isEqualTo(StepKind.CONDITION);
    assertThat(new Step.Branch("b", "a", ConditionOperator.EXISTS, "", "a", "a").kind())
        .isEqualTo(StepKind.BRANCH);
  }

  @Test
  void thePureKindsCostNothingAndAreAlwaysIdempotent() {
    final Step wait = new Step.Wait("w", Duration.ZERO);
    final Step condition = new Step.Condition("c", "a", ConditionOperator.EXISTS, "");
    final Step branch = new Step.Branch("b", "a", ConditionOperator.EXISTS, "", "a", "a");
    for (final Step step : List.of(wait, condition, branch)) {
      assertThat(step.budgetMicros()).isZero();
      assertThat(step.idempotent()).isTrue();
      assertThat(step.requiredCapabilities()).isEmpty();
    }
  }

  @Test
  void aToolStepDeclaresExactlyItsOneCapability() {
    final Step.Plugin tool =
        new Step.Plugin("t", "search", "{}", List.of(), 1L, Duration.ofSeconds(5), true);
    assertThat(tool.requiredCapabilities()).containsExactly("search");
  }

  @Test
  void aModelStepWithAZeroDeadlineIsRefused() {
    assertThatThrownBy(
            () -> new Step.Pipeline("a", "i", List.of(), Set.of(), 1L, 1L, Duration.ZERO, true))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("deadline");
  }

  @Test
  void aToolStepWithANegativeDeadlineIsRefused() {
    assertThatThrownBy(
            () -> new Step.Plugin("t", "c", "{}", List.of(), 1L, Duration.ofSeconds(-1), true))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void aWaitOfNegativeDurationIsRefused() {
    assertThatThrownBy(() -> new Step.Wait("w", Duration.ofSeconds(-1)))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void aBlankStepNameIsRefusedForEveryKind() {
    assertThatThrownBy(
            () ->
                new Step.Pipeline(
                    "", "i", List.of(), Set.of(), 1L, 1L, Duration.ofSeconds(1), true))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new Step.Wait("  ", Duration.ZERO))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void aBranchMustDeclareBothTargetsSoControlFlowNeverDependsOnListOrder() {
    assertThatThrownBy(() -> new Step.Branch("b", "a", ConditionOperator.EXISTS, "", "a", ""))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("whenFalse");
  }

  @Test
  void aStepsCapabilitiesAreHeldInDeterministicOrder() {
    final Step.Pipeline step =
        new Step.Pipeline(
            "a", "i", List.of(), Set.of("zzz", "aaa"), 1L, 1L, Duration.ofSeconds(1), true);
    assertThat(step.requiredCapabilities()).containsExactly("aaa", "zzz");
  }

  @Test
  void aRunVersionPinsThePlanAndTheJournalFormat() {
    final RunVersion pinned = RunVersion.pin(PlanId.of("p"), 7);
    assertThat(pinned.planVersion()).isEqualTo(7);
    assertThat(pinned.journalFormat()).isEqualTo(RunVersion.CURRENT_JOURNAL_FORMAT);
    assertThat(pinned.replayable()).isTrue();
  }

  @Test
  void aRunWrittenByANewerJournalFormatIsNotReplayableByThisBuild() {
    // Resuming it would mean reading bytes under the wrong grammar and silently executing something
    // else, which is worse than refusing to resume.
    assertThat(
            new RunVersion(PlanId.of("p"), 1, RunVersion.CURRENT_JOURNAL_FORMAT + 1).replayable())
        .isFalse();
  }
}

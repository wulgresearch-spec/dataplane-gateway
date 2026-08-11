package io.reliabilityai.gateway.dataplane.agent.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayNameGeneration;
import org.junit.jupiter.api.DisplayNameGenerator;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * The retry schedule, the restart policy's refusals, and the totality of the predicate language.
 */
@DisplayNameGeneration(DisplayNameGenerator.ReplaceUnderscores.class)
class RetryAndConditionTest {

  private static final Duration BASE = Duration.ofMillis(100);
  private static final Duration CEILING = Duration.ofSeconds(10);

  // ---- RetryMode ---------------------------------------------------------------------------

  @ParameterizedTest
  @CsvSource({"1,100", "2,200", "3,400", "4,800", "5,1600"})
  void exponentialDoublesTheDelayEachAttempt(final int attempt, final long expectedMillis) {
    assertThat(RetryMode.EXPONENTIAL.delayFor(attempt, BASE, CEILING).toMillis())
        .isEqualTo(expectedMillis);
  }

  @ParameterizedTest
  @CsvSource({"1,100", "2,200", "3,300", "10,1000"})
  void linearScalesTheDelayWithTheAttempt(final int attempt, final long expectedMillis) {
    assertThat(RetryMode.LINEAR.delayFor(attempt, BASE, CEILING).toMillis())
        .isEqualTo(expectedMillis);
  }

  @ParameterizedTest
  @EnumSource(
      value = RetryMode.class,
      names = {"IMMEDIATE", "NONE"})
  void theZeroDelayModesNeverWait(final RetryMode mode) {
    assertThat(mode.delayFor(9, BASE, CEILING)).isEqualTo(Duration.ZERO);
  }

  @Test
  void anOpenCircuitWaitsTheFullCeiling() {
    assertThat(RetryMode.CIRCUIT_OPEN.delayFor(1, BASE, CEILING)).isEqualTo(CEILING);
  }

  @ParameterizedTest
  @EnumSource(RetryMode.class)
  void noModeEverExceedsTheCeiling(final RetryMode mode) {
    for (int attempt = 1; attempt <= 40; attempt++) {
      assertThat(mode.delayFor(attempt, BASE, CEILING))
          .as("attempt %d", attempt)
          .isLessThanOrEqualTo(CEILING);
    }
  }

  @ParameterizedTest
  @EnumSource(RetryMode.class)
  void noModeEverProducesANegativeDelayEvenAtAbsurdAttemptNumbers(final RetryMode mode) {
    // The exponent is clamped before the shift precisely so a misconfigured budget cannot overflow
    // a
    // long into a negative duration and turn a backoff into an immediate retry.
    assertThat(mode.delayFor(1_000_000, BASE, CEILING)).isGreaterThanOrEqualTo(Duration.ZERO);
  }

  @Test
  void anAttemptNumberBelowOneIsRefused() {
    assertThatThrownBy(() -> RetryMode.EXPONENTIAL.delayFor(0, BASE, CEILING))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void circuitOpenIsDistinctFromNoRetryBecauseOneWaitsAndTheOtherEnds() {
    assertThat(RetryMode.CIRCUIT_OPEN.retries()).isTrue();
    assertThat(RetryMode.NONE.retries()).isFalse();
  }

  // ---- RestartPolicy -----------------------------------------------------------------------

  @Test
  void theStrictDefaultRetriesNothingAndFailsTheRun() {
    assertThat(RestartPolicy.STRICT.strategy()).isEqualTo(SupervisionStrategy.FAIL_RUN);
    assertThat(RestartPolicy.STRICT.retryMode()).isEqualTo(RetryMode.NONE);
    assertThat(RestartPolicy.STRICT.intensity().maxRestarts()).isZero();
  }

  @Test
  void theResilientPolicyRetriesWithBackoffUnderTheOtpDefaultIntensity() {
    assertThat(RestartPolicy.RESILIENT.strategy()).isEqualTo(SupervisionStrategy.RETRY_STEP);
    assertThat(RestartPolicy.RESILIENT.delayFor(3)).isEqualTo(Duration.ofMillis(800));
    assertThat(RestartPolicy.RESILIENT.newRetryBudget().maxAttempts()).isEqualTo(3);
  }

  @Test
  void aRetryStrategyPairedWithNoRetryModeIsRefusedAsIncoherent() {
    assertThatThrownBy(
            () ->
                new RestartPolicy(
                    SupervisionStrategy.RETRY_STEP,
                    RetryMode.NONE,
                    3,
                    Duration.ZERO,
                    Duration.ZERO,
                    RestartIntensity.DEFAULT,
                    "",
                    ""))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("retryMode that retries");
  }

  @Test
  void aRestartFromStrategyWithoutATargetIsRefusedRatherThanFailingDuringFailureHandling() {
    assertThatThrownBy(
            () ->
                new RestartPolicy(
                    SupervisionStrategy.RESTART_FROM,
                    RetryMode.NONE,
                    1,
                    Duration.ZERO,
                    Duration.ZERO,
                    RestartIntensity.NONE,
                    "",
                    ""))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("RESTART_FROM requires");
  }

  @Test
  void aCompensateStrategyWithoutATargetIsRefused() {
    assertThatThrownBy(
            () ->
                new RestartPolicy(
                    SupervisionStrategy.COMPENSATE,
                    RetryMode.NONE,
                    1,
                    Duration.ZERO,
                    Duration.ZERO,
                    RestartIntensity.NONE,
                    "",
                    ""))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("COMPENSATE requires");
  }

  @Test
  void aCeilingBelowTheBaseDelayIsRefused() {
    assertThatThrownBy(
            () ->
                new RestartPolicy(
                    SupervisionStrategy.FAIL_RUN,
                    RetryMode.NONE,
                    1,
                    Duration.ofSeconds(5),
                    Duration.ofSeconds(1),
                    RestartIntensity.NONE,
                    "",
                    ""))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("maxDelay");
  }

  @Test
  void zeroAttemptsPerStepIsRefusedBecauseEveryStepGetsAtLeastOne() {
    assertThatThrownBy(
            () ->
                new RestartPolicy(
                    SupervisionStrategy.FAIL_RUN,
                    RetryMode.NONE,
                    0,
                    Duration.ZERO,
                    Duration.ZERO,
                    RestartIntensity.NONE,
                    "",
                    ""))
        .isInstanceOf(IllegalArgumentException.class);
  }

  // ---- ConditionOperator -------------------------------------------------------------------

  static Stream<Arguments> everyOperatorAgainstAnAbsentSubject() {
    return Stream.of(ConditionOperator.values()).map(Arguments::of);
  }

  @ParameterizedTest
  @MethodSource("everyOperatorAgainstAnAbsentSubject")
  void everyOperatorIsTotalOverAnAbsentSubject(final ConditionOperator operator) {
    // A predicate that could throw would turn a plan-authoring mistake into a mid-run crash during
    // failure handling, which is the worst place to discover it.
    assertThatCode(() -> operator.test(null, "x")).doesNotThrowAnyException();
  }

  @ParameterizedTest
  @EnumSource(ConditionOperator.class)
  void everyOperatorIsTotalOverAnEmptyOperand(final ConditionOperator operator) {
    assertThatCode(() -> operator.test("value", "")).doesNotThrowAnyException();
  }

  @ParameterizedTest
  @CsvSource({
    "EQUALS,abc,abc,true",
    "EQUALS,abc,abd,false",
    "NOT_EQUALS,abc,abd,true",
    "NOT_EQUALS,abc,abc,false",
    "CONTAINS,abcdef,cde,true",
    "CONTAINS,abcdef,xyz,false",
    "NOT_CONTAINS,abcdef,xyz,true",
    "NOT_CONTAINS,abcdef,cde,false",
    "EXISTS,anything,ignored,true",
    "ABSENT,anything,ignored,false",
    "GREATER_THAN,10,5,true",
    "GREATER_THAN,5,10,false",
    "LESS_THAN,5,10,true",
    "LESS_THAN,10,5,false"
  })
  void thePredicatesEvaluateAsDeclared(
      final ConditionOperator operator,
      final String subject,
      final String operand,
      final boolean expected) {
    assertThat(operator.test(subject, operand)).isEqualTo(expected);
  }

  @Test
  void anAbsentSubjectExistsNowhereAndIsAbsentEverywhere() {
    assertThat(ConditionOperator.EXISTS.test(null, "")).isFalse();
    assertThat(ConditionOperator.ABSENT.test(null, "")).isTrue();
  }

  @Test
  void anAbsentSubjectNeverEqualsAnythingAndAlwaysDiffersFromIt() {
    assertThat(ConditionOperator.EQUALS.test(null, "x")).isFalse();
    assertThat(ConditionOperator.NOT_EQUALS.test(null, "x")).isTrue();
  }

  @Test
  void anAbsentSubjectContainsNothingAndTriviallyDoesNotContain() {
    assertThat(ConditionOperator.CONTAINS.test(null, "x")).isFalse();
    assertThat(ConditionOperator.NOT_CONTAINS.test(null, "x")).isTrue();
  }

  @Test
  void aNumericComparisonAgainstANonNumberIsFalseInBothDirections() {
    // Guessing a direction would silently take a branch the plan author never intended.
    assertThat(ConditionOperator.GREATER_THAN.test("banana", "5")).isFalse();
    assertThat(ConditionOperator.LESS_THAN.test("banana", "5")).isFalse();
  }

  @Test
  void numericComparisonToleratesSurroundingWhitespace() {
    assertThat(ConditionOperator.GREATER_THAN.test("  10 ", " 5 ")).isTrue();
  }

  @Test
  void equalNumbersAreNeitherGreaterNorLess() {
    assertThat(ConditionOperator.GREATER_THAN.test("7", "7")).isFalse();
    assertThat(ConditionOperator.LESS_THAN.test("7", "7")).isFalse();
  }

  @ParameterizedTest
  @EnumSource(ConditionOperator.class)
  void aNullOperandIsRefusedBecauseAPlanCannotDeclareOne(final ConditionOperator operator) {
    assertThatThrownBy(() -> operator.test("x", null)).isInstanceOf(NullPointerException.class);
  }
}

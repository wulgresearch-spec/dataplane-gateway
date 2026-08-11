package io.reliabilityai.gateway.dataplane.embedding;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.reliabilityai.gateway.dataplane.embedding.api.EmbeddingFailure;
import io.reliabilityai.gateway.dataplane.embedding.api.EmbeddingHealth;
import io.reliabilityai.gateway.dataplane.embedding.api.EmbeddingModel;
import io.reliabilityai.gateway.dataplane.embedding.api.EmbeddingTransportException;
import io.reliabilityai.gateway.dataplane.embedding.domain.EmbeddingCostEstimator;
import io.reliabilityai.gateway.dataplane.embedding.domain.EmbeddingFailurePolicy;
import io.reliabilityai.gateway.dataplane.embedding.domain.EmbeddingNormalizer;
import io.reliabilityai.gateway.dataplane.embedding.domain.EmbeddingRetryPolicy;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;

/**
 * The four pure policies: what a vector must look like, when to try again, what it costs, and when
 * a provider has stopped being worth calling.
 *
 * <p>All four are deliberately free of I/O, threads and clocks-you-cannot-set, which is why they
 * can be tested exhaustively here rather than inferred from pipeline behaviour. Every rule the
 * mission states about retry — which statuses are repeated and which never are — is asserted
 * directly against the policy that decides it, not against a provider that happens to agree with it
 * today.
 */
@DisplayName("embedding domain policies")
final class EmbeddingDomainTest {

  private static final EmbeddingModel MODEL_8 = new EmbeddingModel("m8", 8, 100, 20_000L);

  @Nested
  @DisplayName("normalization")
  final class Normalization {

    @Test
    @DisplayName("scales a vector to unit length")
    void scalesAVectorToUnitLength() {
      final float[] normalized = EmbeddingNormalizer.normalize(new float[] {3.0f, 4.0f});

      assertThat(normalized).containsExactly(0.6f, 0.8f);
      assertThat(EmbeddingNormalizer.isUnitLength(normalized)).isTrue();
    }

    @Test
    @DisplayName("leaves an already-unit vector unit")
    void leavesAnAlreadyUnitVectorUnit() {
      assertThat(
              EmbeddingNormalizer.isUnitLength(
                  EmbeddingNormalizer.normalize(new float[] {1.0f, 0.0f})))
          .isTrue();
    }

    @Test
    @DisplayName("does not modify the array it was given")
    void doesNotModifyTheArrayItWasGiven() {
      final float[] original = {3.0f, 4.0f};

      EmbeddingNormalizer.normalize(original);

      assertThat(original).containsExactly(3.0f, 4.0f);
    }

    @Test
    @DisplayName("refuses a zero-magnitude vector rather than dividing by nothing")
    void refusesAZeroMagnitudeVectorRatherThanDividingByNothing() {
      // Without this, every component becomes NaN and the failure surfaces much later as a query
      // that matches nothing, in a component that did nothing wrong.
      assertThatThrownBy(() -> EmbeddingNormalizer.normalize(new float[] {0.0f, 0.0f}))
          .isInstanceOf(EmbeddingTransportException.class)
          .hasMessageContaining("zero-magnitude");
    }

    @Test
    @DisplayName("refuses a vector carrying NaN or infinity")
    void refusesAVectorCarryingNanOrInfinity() {
      assertThatThrownBy(() -> EmbeddingNormalizer.normalize(new float[] {1.0f, Float.NaN}))
          .isInstanceOf(EmbeddingTransportException.class);
      assertThatThrownBy(
              () -> EmbeddingNormalizer.normalize(new float[] {1.0f, Float.POSITIVE_INFINITY}))
          .isInstanceOf(EmbeddingTransportException.class);
    }

    @Test
    @DisplayName("conforms a vector of the declared width")
    void conformsAVectorOfTheDeclaredWidth() {
      final float[] conformed =
          EmbeddingNormalizer.conform(EmbeddingFixtures.flat(8, 2.0f), MODEL_8, false);

      assertThat(conformed).hasSize(8);
      assertThat(EmbeddingNormalizer.isUnitLength(conformed)).isTrue();
    }

    @Test
    @DisplayName("refuses a wider vector when the provider does not declare truncation")
    void refusesAWiderVectorWhenTheProviderDoesNotDeclareTruncation() {
      // Padding or truncating silently produces an index that looks healthy and retrieves nonsense.
      // A refused write is recoverable; a corrupt index is found months later by a user.
      assertThatThrownBy(
              () -> EmbeddingNormalizer.conform(EmbeddingFixtures.flat(16, 2.0f), MODEL_8, false))
          .isInstanceOf(EmbeddingTransportException.class)
          .satisfies(
              thrown ->
                  assertThat(((EmbeddingTransportException) thrown).reason())
                      .isEqualTo(EmbeddingFailure.DIMENSION_MISMATCH));
    }

    @Test
    @DisplayName("refuses a narrower vector even when truncation is declared")
    void refusesANarrowerVectorEvenWhenTruncationIsDeclared() {
      // Truncation can shorten a vector; nothing can lengthen one. Zero-padding would be inventing
      // components, and a vector with invented components is a wrong answer with a right shape.
      assertThatThrownBy(
              () -> EmbeddingNormalizer.conform(EmbeddingFixtures.flat(4, 2.0f), MODEL_8, true))
          .isInstanceOf(EmbeddingTransportException.class)
          .satisfies(
              thrown ->
                  assertThat(((EmbeddingTransportException) thrown).reason())
                      .isEqualTo(EmbeddingFailure.DIMENSION_MISMATCH));
    }

    @Test
    @DisplayName("truncates and renormalizes when the provider declares truncation")
    void truncatesAndRenormalizesWhenTheProviderDeclaresTruncation() {
      final float[] wide = new float[16];
      for (int i = 0; i < 16; i++) {
        wide[i] = i + 1.0f;
      }

      final float[] conformed = EmbeddingNormalizer.conform(wide, MODEL_8, true);

      assertThat(conformed).hasSize(8);
      // A truncated unit vector is not a unit vector, so the second normalization is not redundant.
      assertThat(EmbeddingNormalizer.isUnitLength(conformed)).isTrue();
    }

    @Test
    @DisplayName("names the widths it could not reconcile")
    void namesTheWidthsItCouldNotReconcile() {
      assertThatThrownBy(
              () -> EmbeddingNormalizer.conform(EmbeddingFixtures.flat(1536, 1.0f), MODEL_8, false))
          .hasMessageContaining("8")
          .hasMessageContaining("1536");
    }
  }

  @Nested
  @DisplayName("retry")
  final class Retry {

    @ParameterizedTest(name = "{0} is retried")
    @CsvSource({"RATE_LIMITED", "TIMEOUT", "UNAVAILABLE", "NETWORK"})
    @DisplayName("the four transient conditions are retried")
    void theFourTransientConditionsAreRetried(final EmbeddingFailure reason) {
      // The mission's retry list, asserted against the policy rather than against a provider: 429,
      // 503, timeouts and network resets, and those only.
      assertThat(EmbeddingRetryPolicy.immediate(3).shouldRetry(reason, 1)).isTrue();
    }

    @ParameterizedTest(name = "{0} is never retried")
    @CsvSource({
      "REJECTED",
      "AUTH_FAILED",
      "TOO_LARGE",
      "DIMENSION_MISMATCH",
      "BUDGET_EXCEEDED",
      "INTERNAL"
    })
    @DisplayName("permanent conditions are never retried")
    void permanentConditionsAreNeverRetried(final EmbeddingFailure reason) {
      // 400, 401, 403 and 404 all land here. Repeating a rejected credential is an authentication
      // attack against your own provider; repeating a 400 repeats the same mistake at the same
      // cost.
      assertThat(EmbeddingRetryPolicy.immediate(10).shouldRetry(reason, 1)).isFalse();
    }

    @ParameterizedTest
    @EnumSource(EmbeddingFailure.class)
    @DisplayName("every failure kind has an explicit retry verdict")
    void everyFailureKindHasAnExplicitRetryVerdict(final EmbeddingFailure reason) {
      // Not a tautology: it fails the day someone adds a failure kind and the policy is asked about
      // it before anyone decided what the answer should be.
      assertThat(EmbeddingRetryPolicy.immediate(2).shouldRetry(reason, 1))
          .isEqualTo(reason.retryable());
    }

    @Test
    @DisplayName("stops once the attempt budget is spent")
    void stopsOnceTheAttemptBudgetIsSpent() {
      final EmbeddingRetryPolicy policy = EmbeddingRetryPolicy.immediate(3);

      assertThat(policy.shouldRetry(EmbeddingFailure.TIMEOUT, 2)).isTrue();
      assertThat(policy.shouldRetry(EmbeddingFailure.TIMEOUT, 3)).isFalse();
      assertThat(policy.maxAttempts()).isEqualTo(3);
    }

    @Test
    @DisplayName("backs off exponentially up to a ceiling")
    void backsOffExponentiallyUpToACeiling() {
      final EmbeddingRetryPolicy policy = new EmbeddingRetryPolicy(10, 100L, 2_000L, () -> 1.0);

      assertThat(policy.delayMillis(1)).isEqualTo(100L);
      assertThat(policy.delayMillis(2)).isEqualTo(200L);
      assertThat(policy.delayMillis(3)).isEqualTo(400L);
      assertThat(policy.delayMillis(4)).isEqualTo(800L);
      // Capped, and capped without ever shifting past the width of a long: a naive
      // baseDelay << attempts overflows to a negative delay somewhere around the 57th attempt.
      assertThat(policy.delayMillis(20)).isEqualTo(2_000L);
      assertThat(policy.delayMillis(1_000)).isEqualTo(2_000L);
    }

    @Test
    @DisplayName("jitter spreads across the whole window, not the top of it")
    void jitterSpreadsAcrossTheWholeWindowNotTheTopOfIt() {
      // Full jitter, not equal jitter. Retries that all wait the same computed delay reconverge on
      // one instant and turn a single rate limit into a standing wave of them.
      assertThat(new EmbeddingRetryPolicy(5, 100L, 2_000L, () -> 0.0).delayMillis(3)).isZero();
      assertThat(new EmbeddingRetryPolicy(5, 100L, 2_000L, () -> 0.5).delayMillis(3))
          .isEqualTo(200L);
      assertThat(new EmbeddingRetryPolicy(5, 100L, 2_000L, () -> 1.0).delayMillis(3))
          .isEqualTo(400L);
    }

    @Test
    @DisplayName(
        "a jitter source outside zero and one cannot produce a negative or oversized delay")
    void aJitterSourceOutsideZeroAndOneCannotProduceANegativeOrOversizedDelay() {
      assertThat(new EmbeddingRetryPolicy(5, 100L, 2_000L, () -> -3.0).delayMillis(2)).isZero();
      assertThat(new EmbeddingRetryPolicy(5, 100L, 2_000L, () -> 7.0).delayMillis(2))
          .isEqualTo(200L);
    }

    @Test
    @DisplayName("rejects a policy that could never attempt anything")
    void rejectsAPolicyThatCouldNeverAttemptAnything() {
      assertThatThrownBy(() -> new EmbeddingRetryPolicy(0, 100L, 200L, () -> 0.0))
          .isInstanceOf(IllegalArgumentException.class);
      assertThatThrownBy(() -> new EmbeddingRetryPolicy(3, 500L, 200L, () -> 0.0))
          .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("the shipped default is three attempts")
    void theShippedDefaultIsThreeAttempts() {
      assertThat(EmbeddingRetryPolicy.defaults().maxAttempts()).isEqualTo(3);
    }
  }

  @Nested
  @DisplayName("cost estimation")
  final class CostEstimation {

    @Test
    @DisplayName("estimates roughly four characters to the token")
    void estimatesRoughlyFourCharactersToTheToken() {
      assertThat(EmbeddingCostEstimator.estimateTokens("a".repeat(400))).isEqualTo(100L);
    }

    @Test
    @DisplayName("never estimates an input as free")
    void neverEstimatesAnInputAsFree() {
      // Providers charge per request as well as per token. Counting a short string as zero tokens
      // makes a million short writes look free right up until the invoice.
      assertThat(EmbeddingCostEstimator.estimateTokens("")).isEqualTo(1L);
      assertThat(EmbeddingCostEstimator.estimateTokens("a")).isEqualTo(1L);
      assertThat(EmbeddingCostEstimator.estimateTokens((String) null)).isEqualTo(1L);
    }

    @Test
    @DisplayName("grows with the input")
    void growsWithTheInput() {
      assertThat(EmbeddingCostEstimator.estimateTokens("x".repeat(4_000)))
          .isGreaterThan(EmbeddingCostEstimator.estimateTokens("x".repeat(4)));
    }

    @Test
    @DisplayName("sums a batch")
    void sumsABatch() {
      assertThat(EmbeddingCostEstimator.estimateTokens(List.of("a".repeat(400), "b".repeat(400))))
          .isEqualTo(200L);
    }

    @Test
    @DisplayName("prices a token count in whole micros, rounding up")
    void pricesATokenCountInWholeMicrosRoundingUp() {
      // 20_000 micros per million tokens is $0.02 per million. A million tokens is therefore 20_000
      // micros, and half a million is 10_000.
      assertThat(EmbeddingCostEstimator.costMicros(1_000_000L, MODEL_8)).isEqualTo(20_000L);
      assertThat(EmbeddingCostEstimator.costMicros(500_000L, MODEL_8)).isEqualTo(10_000L);
    }

    @Test
    @DisplayName("never prices a chargeable call at zero")
    void neverPricesAChargeableCallAtZero() {
      // Rounding down is how a large number of small requests each cost nothing, which is the shape
      // of every metering argument.
      assertThat(EmbeddingCostEstimator.costMicros(1L, MODEL_8)).isEqualTo(1L);
    }

    @Test
    @DisplayName("prices nothing at nothing")
    void pricesNothingAtNothing() {
      assertThat(EmbeddingCostEstimator.costMicros(0L, MODEL_8)).isZero();
      assertThat(EmbeddingCostEstimator.costMicros(1_000L, new EmbeddingModel("free", 8, 100, 0L)))
          .isZero();
    }

    @Test
    @DisplayName("flags an input the model cannot accept")
    void flagsAnInputTheModelCannotAccept() {
      assertThat(EmbeddingCostEstimator.exceedsModelLimit("x".repeat(400), MODEL_8)).isFalse();
      assertThat(EmbeddingCostEstimator.exceedsModelLimit("x".repeat(4_000), MODEL_8)).isTrue();
    }

    @Test
    @DisplayName("under-counts non-Latin script, which is the estimator's known weakness")
    void underCountsNonLatinScriptWhichIsTheEstimatorsKnownWeakness() {
      // Asserted rather than merely documented, so the limitation stays visible. CJK text runs at
      // roughly one character per token; four-to-one under-counts it about fourfold, and the
      // consequence is a budget check that passes when the true spend would have exceeded it. The
      // honest fix is a per-adapter token-count port, which is B58 and is not built.
      final String cjk = "以下は日本語のテキストです".repeat(20);

      assertThat(EmbeddingCostEstimator.estimateTokens(cjk))
          .isLessThan(cjk.length())
          .isCloseTo(cjk.length() / 4L, org.assertj.core.data.Offset.offset(2L));
    }
  }

  @Nested
  @DisplayName("failure policy")
  final class FailurePolicy {

    private final EmbeddingFailurePolicy policy = EmbeddingFailurePolicy.defaults();

    @Test
    @DisplayName("a fresh provider is healthy")
    void aFreshProviderIsHealthy() {
      final EmbeddingHealth health = EmbeddingHealth.healthy(Instant.EPOCH);

      assertThat(health.state()).isEqualTo(EmbeddingHealth.State.HEALTHY);
      assertThat(health.consecutiveFailures()).isZero();
      assertThat(health.usable()).isTrue();
      assertThat(health.lastFailureReason()).isEmpty();
    }

    @Test
    @DisplayName("failures accumulate into degraded and then unavailable")
    void failuresAccumulateIntoDegradedAndThenUnavailable() {
      EmbeddingHealth health = EmbeddingHealth.healthy(Instant.EPOCH);

      for (int i = 0; i < 2; i++) {
        health = policy.onFailure(health, EmbeddingFailure.TIMEOUT);
      }
      assertThat(health.state()).isEqualTo(EmbeddingHealth.State.HEALTHY);

      health = policy.onFailure(health, EmbeddingFailure.TIMEOUT);
      assertThat(health.state()).isEqualTo(EmbeddingHealth.State.DEGRADED);
      assertThat(health.usable()).isTrue();

      for (int i = 0; i < 7; i++) {
        health = policy.onFailure(health, EmbeddingFailure.TIMEOUT);
      }
      assertThat(health.state()).isEqualTo(EmbeddingHealth.State.UNAVAILABLE);
      assertThat(health.usable()).isFalse();
      assertThat(health.consecutiveFailures()).isEqualTo(10);
    }

    @Test
    @DisplayName("one success clears the streak completely")
    void oneSuccessClearsTheStreakCompletely() {
      EmbeddingHealth health = EmbeddingHealth.healthy(Instant.EPOCH);
      for (int i = 0; i < 20; i++) {
        health = policy.onFailure(health, EmbeddingFailure.UNAVAILABLE);
      }

      health = policy.onSuccess(Instant.EPOCH.plusSeconds(60));

      // Recovery is immediate rather than gradual because there is no probe to recover *with*: a
      // synthetic health check against a paid endpoint bills whether or not anyone is using it.
      assertThat(health.state()).isEqualTo(EmbeddingHealth.State.HEALTHY);
      assertThat(health.consecutiveFailures()).isZero();
    }

    @Test
    @DisplayName("the last failure reason is carried for the operator")
    void theLastFailureReasonIsCarriedForTheOperator() {
      final EmbeddingHealth health =
          policy.onFailure(EmbeddingHealth.healthy(Instant.EPOCH), EmbeddingFailure.AUTH_FAILED);

      assertThat(health.lastFailureReason()).contains(EmbeddingFailure.AUTH_FAILED);
    }

    @Test
    @DisplayName("the last success time survives a failure")
    void theLastSuccessTimeSurvivesAFailure() {
      final Instant lastGood = Instant.EPOCH.plusSeconds(500);

      final EmbeddingHealth health =
          policy.onFailure(EmbeddingHealth.healthy(lastGood), EmbeddingFailure.NETWORK);

      // "Failing since" is answerable only if the last success is not overwritten by the failure.
      assertThat(health.lastSuccess()).isEqualTo(lastGood);
    }

    @Test
    @DisplayName("rejects thresholds that are not ordered")
    void rejectsThresholdsThatAreNotOrdered() {
      assertThatThrownBy(() -> new EmbeddingFailurePolicy(5, 2))
          .isInstanceOf(IllegalArgumentException.class);
      assertThatThrownBy(() -> new EmbeddingFailurePolicy(0, 5))
          .isInstanceOf(IllegalArgumentException.class);
    }
  }
}

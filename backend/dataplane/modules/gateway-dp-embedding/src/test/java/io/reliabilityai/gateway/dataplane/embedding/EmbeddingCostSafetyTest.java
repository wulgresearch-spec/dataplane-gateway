package io.reliabilityai.gateway.dataplane.embedding;

import static org.assertj.core.api.Assertions.assertThat;

import io.reliabilityai.gateway.dataplane.embedding.api.EmbeddingModel;
import io.reliabilityai.gateway.dataplane.embedding.domain.EmbeddingCostEstimator;
import java.math.BigInteger;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Cost arithmetic treated as a security control rather than as reporting.
 *
 * <p>Cost is the number a budget decision is taken on and the number a customer is billed from, so
 * it has one asymmetry that ordinary arithmetic does not: <b>overstating is recoverable and
 * understating is not</b>. An overstatement refuses a write somebody could afford — visible,
 * complainable, fixable. An understatement admits spend nobody authorised and shows up on an
 * invoice weeks later, with the audit trail agreeing that everything was fine.
 *
 * <p>So these tests do not check that the arithmetic is right in the ordinary range. They check
 * that it cannot be made to come out <em>low</em>.
 */
@DisplayName("cost safety")
final class EmbeddingCostSafetyTest {

  /** $0.02 per million tokens, in micros. */
  private static final EmbeddingModel SMALL = new EmbeddingModel("small", 1536, 8191, 20_000L);

  /**
   * The exact answer, computed without overflow.
   *
   * @param tokens the token count
   * @param model the model whose price applies
   * @return the true cost in micros
   */
  private static BigInteger exact(final long tokens, final EmbeddingModel model) {
    if (tokens <= 0L || model.costPerMillionInputTokensMicros() == 0L) {
      return BigInteger.ZERO;
    }
    return BigInteger.valueOf(tokens)
        .multiply(BigInteger.valueOf(model.costPerMillionInputTokensMicros()))
        .add(BigInteger.valueOf(999_999L))
        .divide(BigInteger.valueOf(1_000_000L))
        .max(BigInteger.ONE);
  }

  @ParameterizedTest(name = "{0} tokens is never understated")
  @ValueSource(
      longs = {
        1L,
        1_000L,
        1_000_000L,
        Integer.MAX_VALUE,
        1_000_000_000_000L,
        461_168_601_842_738L,
        461_168_601_842_739L,
        1_000_000_000_000_000L,
        4_611_686_018_427_387_903L,
        Long.MAX_VALUE
      })
  @DisplayName("cost is never lower than the true cost, at any token count")
  void costIsNeverLowerThanTheTrueCostAtAnyTokenCount(final long tokens) {
    final BigInteger reported =
        BigInteger.valueOf(EmbeddingCostEstimator.costMicros(tokens, SMALL));

    // The whole point. Before multiplyExact, 461_168_601_842_739 tokens reported ONE MICRO — the
    // product wrapped negative and the Math.max floor turned a nine-million-dollar call into a
    // millionth of a dollar, in metering and in the audit trail.
    assertThat(reported)
        .as("cost for %d tokens must not be understated", tokens)
        .isGreaterThanOrEqualTo(exact(tokens, SMALL).min(BigInteger.valueOf(Long.MAX_VALUE)));
  }

  @Test
  @DisplayName("an overflowing product saturates rather than wrapping")
  void anOverflowingProductSaturatesRatherThanWrapping() {
    assertThat(EmbeddingCostEstimator.costMicros(Long.MAX_VALUE, SMALL)).isEqualTo(Long.MAX_VALUE);
    assertThat(EmbeddingCostEstimator.costMicros(461_168_601_842_739L, SMALL))
        .isGreaterThan(9_000_000_000_000L);
  }

  @Test
  @DisplayName("the ordinary range is still exact")
  void theOrdinaryRangeIsStillExact() {
    assertThat(EmbeddingCostEstimator.costMicros(1_000_000L, SMALL)).isEqualTo(20_000L);
    assertThat(EmbeddingCostEstimator.costMicros(500_000L, SMALL)).isEqualTo(10_000L);
    assertThat(EmbeddingCostEstimator.costMicros(1_000L, SMALL)).isEqualTo(20L);
  }

  @Test
  @DisplayName("a chargeable call never costs nothing, and a free model never costs anything")
  void aChargeableCallNeverCostsNothingAndAFreeModelNeverCostsAnything() {
    assertThat(EmbeddingCostEstimator.costMicros(1L, SMALL)).isEqualTo(1L);
    assertThat(EmbeddingCostEstimator.costMicros(1_000_000L, new EmbeddingModel("free", 8, 10, 0L)))
        .isZero();
  }

  @Test
  @DisplayName("a negative token count is treated as free rather than as a credit")
  void aNegativeTokenCountIsTreatedAsFreeRatherThanAsACredit() {
    // A negative product would otherwise round to a negative charge — a provider response that pays
    // the tenant. Zero is the only defensible reading of a count that cannot exist.
    assertThat(EmbeddingCostEstimator.costMicros(-5L, SMALL)).isZero();
    assertThat(EmbeddingCostEstimator.costMicros(Long.MIN_VALUE, SMALL)).isZero();
  }

  @Test
  @DisplayName("the estimator still refuses to call any input free")
  void theEstimatorStillRefusesToCallAnyInputFree() {
    assertThat(EmbeddingCostEstimator.estimateTokens("")).isEqualTo(1L);
    assertThat(EmbeddingCostEstimator.estimateTokens((String) null)).isEqualTo(1L);
  }
}

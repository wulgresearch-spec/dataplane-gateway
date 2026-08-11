package io.reliabilityai.gateway.dataplane.embedding.domain;

import io.reliabilityai.gateway.dataplane.embedding.api.EmbeddingModel;
import java.util.List;

/**
 * What a call will probably cost, worked out before it is made (AD-030 §8).
 *
 * <p><b>This is an estimate, and the estimate is not very good.</b> Real tokenisation is
 * model-specific, and running a provider tokeniser here would mean shipping a vendor vocabulary
 * into a module whose entire purpose is not knowing which vendor is behind it. So the estimate is a
 * characters-per-token heuristic.
 *
 * <p>Four characters per token is close for English prose and materially wrong elsewhere: code and
 * punctuation-heavy text run nearer two, and CJK text is roughly one character per token, where
 * this will under-count by about four times. §8.2 records the consequence — a budget check that
 * passes when the true spend would have exceeded it. The estimate is deliberately biased upward
 * with a floor per input so that short strings are not counted as free.
 *
 * <p>The honest fix is a token-count port implemented per adapter. It is B58, and it is not built.
 */
public final class EmbeddingCostEstimator {

  /**
   * Characters per token for Latin-script prose. Wrong for other scripts; see the class comment.
   */
  private static final double CHARACTERS_PER_TOKEN = 4.0;

  /** No input costs nothing: providers charge per request as well as per token. */
  private static final long MINIMUM_TOKENS_PER_INPUT = 1L;

  private EmbeddingCostEstimator() {}

  /**
   * Estimates the tokens one text will be charged as.
   *
   * @param text the input
   * @return the estimated token count, never below one
   */
  public static long estimateTokens(final String text) {
    if (text == null || text.isEmpty()) {
      return MINIMUM_TOKENS_PER_INPUT;
    }
    return Math.max(
        MINIMUM_TOKENS_PER_INPUT, (long) Math.ceil(text.length() / CHARACTERS_PER_TOKEN));
  }

  /**
   * Estimates the tokens a batch will be charged as.
   *
   * @param texts the inputs
   * @return the estimated total
   */
  public static long estimateTokens(final List<String> texts) {
    long total = 0L;
    for (final String text : texts) {
      total += estimateTokens(text);
    }
    return total;
  }

  /**
   * Converts a token count to a charge.
   *
   * <p>Integer arithmetic throughout, rounding up. Rounding down would let a large number of small
   * requests each cost nothing, which is the shape of every metering bug that ends in an argument.
   *
   * @param tokens the token count
   * @param model the model whose price applies
   * @return the charge in millionths of a currency unit
   */
  public static long costMicros(final long tokens, final EmbeddingModel model) {
    final long perMillion = model.costPerMillionInputTokensMicros();
    if (perMillion == 0L || tokens <= 0L) {
      return 0L;
    }
    // multiplyExact, not bare multiplication. A plain `tokens * perMillion` wraps silently: at
    // 20_000 micros per million tokens the product overflows past ~4.6e14 tokens, and because the
    // wrapped value is negative the Math.max floor turns it into ONE MICRO. A call that should cost
    // nine million dollars is then recorded as costing a millionth of one, in metering and in the
    // audit trail, with no error anywhere.
    //
    // Input length cannot reach that frontier, but this is also called with the token count the
    // PROVIDER reports, and a self-hosted endpoint speaking a vendor-compatible protocol is a
    // normal
    // deployment shape — so the number arrives over the network and is not trusted input.
    // Saturating
    // to Long.MAX_VALUE is the only safe direction: an absurd overstatement is visible and
    // refusable,
    // an understatement is silent.
    final long product;
    try {
      product = Math.multiplyExact(tokens, perMillion);
    } catch (final ArithmeticException overflow) {
      return Long.MAX_VALUE;
    }
    return Math.max(1L, Math.ceilDiv(product, 1_000_000L));
  }

  /**
   * Whether an input is too long for a model, using the same estimate.
   *
   * <p>Because the estimate under-counts for non-Latin scripts, an input this passes may still be
   * rejected by the provider. That refusal is reported as a per-input failure rather than being
   * retried, so the cost is one wasted call and not a loop.
   *
   * @param text the input
   * @param model the model
   * @return true when the estimate exceeds what the model accepts
   */
  public static boolean exceedsModelLimit(final String text, final EmbeddingModel model) {
    return estimateTokens(text) > model.maxInputTokens();
  }
}

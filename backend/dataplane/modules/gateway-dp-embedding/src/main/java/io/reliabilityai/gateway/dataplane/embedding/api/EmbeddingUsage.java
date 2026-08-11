package io.reliabilityai.gateway.dataplane.embedding.api;

/**
 * What a call consumed (AD-030 §8).
 *
 * <p>Integer micros throughout. Cost accumulated as a double across a million records drifts, and a
 * billing figure that drifts is a billing figure that gets disputed.
 *
 * @param inputTokens tokens the provider charged for
 * @param providerCalls how many network calls it took, retries included
 * @param costMicros the charge in millionths of a currency unit
 */
public record EmbeddingUsage(long inputTokens, int providerCalls, long costMicros) {

  /** Usage for something that cost nothing, such as a cache hit. */
  public static final EmbeddingUsage FREE = new EmbeddingUsage(0L, 0, 0L);

  /**
   * Adds two usage records.
   *
   * @param other the record to add
   * @return the sum
   */
  public EmbeddingUsage plus(final EmbeddingUsage other) {
    return new EmbeddingUsage(
        inputTokens + other.inputTokens,
        providerCalls + other.providerCalls,
        costMicros + other.costMicros);
  }
}

package io.reliabilityai.gateway.dataplane.embedding.api;

import io.reliabilityai.gateway.common.Preconditions;

/**
 * A model a provider can embed with (AD-030 §4).
 *
 * <p>The model <em>id</em> is a neutral label chosen by the deployment, not a vendor product name.
 * Mapping it onto whatever a provider calls the thing happens in the adapter and nowhere else —
 * which is what lets a deployment repoint {@code text-default-1536} from one vendor to another
 * without a line changing above the adapter layer.
 *
 * @param id the neutral model identifier
 * @param dimension how many components its vectors have
 * @param maxInputTokens the longest single input it accepts
 * @param costPerMillionInputTokensMicros the price of one million input tokens, in millionths of a
 *     currency unit. Integral so cost arithmetic never drifts across a million records. Both halves
 *     of the unit matter: a published price of $0.02 per million tokens is {@code 20_000}, not
 *     {@code 20}, and getting that wrong understates every budget check by a factor of a thousand
 */
public record EmbeddingModel(
    String id, int dimension, int maxInputTokens, long costPerMillionInputTokensMicros) {

  /**
   * Validates the model.
   *
   * @param id the identifier
   * @param dimension the vector width
   * @param maxInputTokens the input limit
   * @param costPerMillionInputTokensMicros the price
   */
  public EmbeddingModel {
    Preconditions.requireNonBlank(id, "id");
    if (dimension < 1) {
      throw new IllegalArgumentException("dimension must be positive");
    }
    if (maxInputTokens < 1) {
      throw new IllegalArgumentException("maxInputTokens must be positive");
    }
    if (costPerMillionInputTokensMicros < 0) {
      throw new IllegalArgumentException("cost must not be negative");
    }
  }
}

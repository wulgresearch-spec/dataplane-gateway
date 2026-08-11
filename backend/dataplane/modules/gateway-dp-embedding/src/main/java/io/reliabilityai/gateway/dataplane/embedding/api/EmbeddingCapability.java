package io.reliabilityai.gateway.dataplane.embedding.api;

import io.reliabilityai.gateway.common.Preconditions;
import java.util.Optional;
import java.util.Set;

/**
 * What one provider can actually do (AD-030 §4).
 *
 * <p>Declared rather than discovered, because the alternative is finding out in production. The
 * batcher reads these limits and splits accordingly; nothing in the pipeline guesses at them.
 *
 * @param models the models this provider serves
 * @param maxBatchSize how many inputs one call accepts
 * @param maxPayloadBytes how large one encoded body may be
 * @param returnsNormalized whether vectors already have unit length
 * @param supportsDimensionReduction whether a shorter vector can be requested
 */
public record EmbeddingCapability(
    Set<EmbeddingModel> models,
    int maxBatchSize,
    int maxPayloadBytes,
    boolean returnsNormalized,
    boolean supportsDimensionReduction) {

  /**
   * Validates and freezes the capability.
   *
   * @param models the served models
   * @param maxBatchSize the batch limit
   * @param maxPayloadBytes the payload limit
   * @param returnsNormalized whether vectors arrive normalized
   * @param supportsDimensionReduction whether truncation is offered
   */
  public EmbeddingCapability {
    models = Set.copyOf(Preconditions.requireNonNull(models, "models"));
    if (models.isEmpty()) {
      throw new IllegalArgumentException("a provider must serve at least one model");
    }
    if (maxBatchSize < 1) {
      throw new IllegalArgumentException("maxBatchSize must be positive");
    }
    if (maxPayloadBytes < 1) {
      throw new IllegalArgumentException("maxPayloadBytes must be positive");
    }
  }

  /**
   * Finds a served model by neutral id.
   *
   * @param modelId the neutral identifier
   * @return the model, or empty when this provider does not serve it
   */
  public Optional<EmbeddingModel> model(final String modelId) {
    return models.stream().filter(model -> model.id().equals(modelId)).findFirst();
  }
}

package io.reliabilityai.gateway.dataplane.embedding.api;

import io.reliabilityai.gateway.common.Preconditions;
import io.reliabilityai.gateway.dataplane.provider.api.ProviderId;
import java.time.Instant;
import java.util.Map;

/**
 * The only embedding shape anything above the adapter layer ever sees (AD-030 §5).
 *
 * <p>Every provider returns something different: a different dimension, a different envelope,
 * normalized or not, usage reported somewhere else or not at all. This is where all of that stops.
 *
 * <p><b>The vector is copied in and copied out.</b> A record that hands out its own array is a
 * record whose contents any caller can rewrite, and an embedding mutated after the cache stored it
 * would poison every later hit. The copy costs a few hundred nanoseconds and removes the whole
 * class of bug — measured in AD-030 §11.
 *
 * @param providerId which provider produced it
 * @param modelId the neutral model identifier
 * @param dimension the vector width, always equal to the vector length
 * @param createdAt when it was produced
 * @param usage what it consumed
 * @param normalized whether the vector has unit length
 * @param metadata neutral key-value context, never provider-native fields
 * @param vector the components
 */
public record CanonicalEmbedding(
    ProviderId providerId,
    String modelId,
    int dimension,
    Instant createdAt,
    EmbeddingUsage usage,
    boolean normalized,
    Map<String, String> metadata,
    float[] vector) {

  /**
   * Validates and defensively copies.
   *
   * @param providerId the producing provider
   * @param modelId the neutral model id
   * @param dimension the vector width
   * @param createdAt the creation instant
   * @param usage the consumption record
   * @param normalized whether unit length
   * @param metadata neutral context
   * @param vector the components
   */
  public CanonicalEmbedding {
    Preconditions.requireNonNull(providerId, "providerId");
    Preconditions.requireNonBlank(modelId, "modelId");
    Preconditions.requireNonNull(createdAt, "createdAt");
    Preconditions.requireNonNull(usage, "usage");
    metadata = Map.copyOf(Preconditions.requireNonNull(metadata, "metadata"));
    Preconditions.requireNonNull(vector, "vector");
    if (dimension < 1) {
      throw new IllegalArgumentException("dimension must be positive");
    }
    if (vector.length != dimension) {
      // A provider that returns a different width than its declared model is the failure that
      // corrupts an index silently rather than loudly, so it is refused at construction.
      throw new IllegalArgumentException(
          "declared dimension " + dimension + " does not match vector length " + vector.length);
    }
    vector = vector.clone();
  }

  /**
   * The vector components.
   *
   * @return a copy, so a caller cannot alter what a cache is holding
   */
  @Override
  public float[] vector() {
    return vector.clone();
  }

  /**
   * Renders the embedding without rendering the vector.
   *
   * <p>The generated record {@code toString} would print every component. Three thousand floats in
   * a log line are useless to read and are a copy of derived content in a place nobody audited.
   *
   * @return a description carrying no components
   */
  @Override
  public String toString() {
    return "CanonicalEmbedding[provider="
        + providerId.value()
        + ", model="
        + modelId
        + ", dimension="
        + dimension
        + ", normalized="
        + normalized
        + "]";
  }
}

package io.reliabilityai.gateway.dataplane.embedding.domain;

import io.reliabilityai.gateway.dataplane.embedding.api.EmbeddingFailure;
import io.reliabilityai.gateway.dataplane.embedding.api.EmbeddingModel;
import io.reliabilityai.gateway.dataplane.embedding.api.EmbeddingTransportException;

/**
 * Puts every provider vector into one shape (AD-030 §5).
 *
 * <p>Two jobs, and the second is the one that matters.
 *
 * <p><b>Unit length.</b> Some providers normalize, some do not. Cosine similarity over unnormalized
 * vectors is simply wrong, and the error is silent: results still come back ranked, just ranked
 * badly. Normalizing here means the index in B26 can assume it.
 *
 * <p><b>Width.</b> A provider returning a different dimension than the model declares is refused,
 * not padded and not truncated. Silently reshaping a vector produces an index that looks healthy
 * and retrieves nonsense, which is far worse than a write that fails loudly. Truncation happens
 * only where a provider declares it supported, and the result is renormalized, because a truncated
 * unit vector is no longer a unit vector.
 */
public final class EmbeddingNormalizer {

  /** Below this, a vector is treated as having no direction at all. */
  private static final double MINIMUM_NORM = 1e-12;

  private EmbeddingNormalizer() {}

  /**
   * Scales a vector to unit length.
   *
   * @param vector the components
   * @return a new unit-length array
   * @throws EmbeddingTransportException when the vector has no usable direction
   */
  public static float[] normalize(final float[] vector) {
    double sumOfSquares = 0.0;
    for (final float component : vector) {
      if (!Float.isFinite(component)) {
        throw new EmbeddingTransportException(
            EmbeddingFailure.INTERNAL, "provider returned a non-finite vector component");
      }
      sumOfSquares += (double) component * component;
    }
    final double norm = Math.sqrt(sumOfSquares);
    if (norm < MINIMUM_NORM) {
      throw new EmbeddingTransportException(
          EmbeddingFailure.INTERNAL, "provider returned a zero-magnitude vector");
    }
    // Multiply by the reciprocal rather than divide per component. Every embedding the gateway
    // produces passes through this loop once, and a float divide is several times the cost of a
    // float
    // multiply on every mainstream CPU; at 1536 components that was the dominant term in the whole
    // normalization, measured in AD-030 §11. The reciprocal is computed once in double precision,
    // so
    // the result differs from a per-component divide by at most an ulp — far inside the
    // one-part-in-
    // ten-thousand tolerance {@link #isUnitLength} applies.
    final float scale = (float) (1.0 / norm);
    final float[] scaled = new float[vector.length];
    for (int i = 0; i < vector.length; i++) {
      scaled[i] = vector[i] * scale;
    }
    return scaled;
  }

  /**
   * Reports whether a vector is already unit length within tolerance.
   *
   * @param vector the components
   * @return true when the norm is within one part in ten thousand of one
   */
  public static boolean isUnitLength(final float[] vector) {
    double sumOfSquares = 0.0;
    for (final float component : vector) {
      sumOfSquares += (double) component * component;
    }
    return Math.abs(Math.sqrt(sumOfSquares) - 1.0) < 1e-4;
  }

  /**
   * Brings a provider vector to the width the model declares.
   *
   * @param vector what the provider returned
   * @param model the model that was requested
   * @param reductionSupported whether the provider declares truncation as supported
   * @return a vector of exactly the declared width, unit length
   * @throws EmbeddingTransportException when the width cannot be reconciled
   */
  public static float[] conform(
      final float[] vector, final EmbeddingModel model, final boolean reductionSupported) {
    if (vector.length == model.dimension()) {
      return normalize(vector);
    }
    if (vector.length > model.dimension() && reductionSupported) {
      // Leading-prefix truncation, meaningful only for models trained so that a prefix is itself a
      // usable embedding. Renormalized afterwards for the reason in the class comment.
      final float[] shortened = new float[model.dimension()];
      System.arraycopy(vector, 0, shortened, 0, model.dimension());
      return normalize(shortened);
    }
    throw new EmbeddingTransportException(
        EmbeddingFailure.DIMENSION_MISMATCH,
        "model "
            + model.id()
            + " declares "
            + model.dimension()
            + " dimensions but the provider returned "
            + vector.length);
  }
}

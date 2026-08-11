package io.reliabilityai.gateway.dataplane.memory.api;

import io.reliabilityai.gateway.common.Preconditions;
import java.util.EnumMap;
import java.util.Map;

/**
 * The six signals a result is ranked by (AD-026 §7.2).
 *
 * <p>Each contributes a value in {@code [0,1]}, so weights are comparable and a weight change means
 * what an operator expects. A signal that could exceed one would let a single dimension swamp the
 * sum no matter how it was weighted.
 */
public enum RankingSignal {

  /** Semantic closeness, from the vector index. */
  SIMILARITY,

  /** Lexical overlap, from the keyword store. */
  KEYWORD_MATCH,

  /** How recently the memory was written. */
  FRESHNESS,

  /** How recently it was read. */
  RECENCY_OF_USE,

  /** Declared significance, clamped by policy so a caller cannot pin its own records to the top. */
  IMPORTANCE,

  /** How often it has been read, damped so a hot record cannot dominate outright. */
  USAGE_FREQUENCY;

  /**
   * The weights applied to each signal.
   *
   * <p>Immutable, normalised, and complete: every signal has a weight, so adding a seventh signal
   * cannot silently default to zero and disappear.
   *
   * @param weights the per-signal weights, each non-negative
   */
  public record Weights(Map<RankingSignal, Double> weights) {

    /**
     * Balanced defaults: relevance dominates, freshness and importance shape the tail.
     *
     * <p>Chosen so that a semantically perfect but ancient match still beats a fresh irrelevant
     * one. The reverse ordering is the classic failure of a freshness-heavy memory: it retrieves
     * what happened most recently rather than what was asked about.
     */
    public static final Weights DEFAULT =
        of(
            Map.of(
                SIMILARITY, 0.35d,
                KEYWORD_MATCH, 0.25d,
                FRESHNESS, 0.15d,
                RECENCY_OF_USE, 0.05d,
                IMPORTANCE, 0.15d,
                USAGE_FREQUENCY, 0.05d));

    /**
     * Validates and completes the weight map.
     *
     * @param weights the per-signal weights
     */
    public Weights {
      Preconditions.requireNonNull(weights, "weights");
      final EnumMap<RankingSignal, Double> complete = new EnumMap<>(RankingSignal.class);
      for (final RankingSignal signal : values()) {
        final double weight = weights.getOrDefault(signal, 0.0d);
        if (weight < 0.0d || Double.isNaN(weight)) {
          throw new IllegalArgumentException("weight for " + signal + " must be non-negative");
        }
        complete.put(signal, weight);
      }
      weights = java.util.Collections.unmodifiableMap(complete);
    }

    /**
     * Creates a weight set, filling any absent signal with zero.
     *
     * @param weights the stated weights
     * @return the completed weights
     */
    public static Weights of(final Map<RankingSignal, Double> weights) {
      return new Weights(weights);
    }

    /**
     * Returns the weight for a signal.
     *
     * @param signal the signal
     * @return its weight, never negative
     */
    public double weightOf(final RankingSignal signal) {
      Preconditions.requireNonNull(signal, "signal");
      return weights.get(signal);
    }

    /**
     * Returns the sum of every weight.
     *
     * @return the total, used to normalise a score back into {@code [0,1]}
     */
    public double total() {
      double total = 0.0d;
      for (final double weight : weights.values()) {
        total += weight;
      }
      return total;
    }
  }
}

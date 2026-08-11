package io.reliabilityai.gateway.dataplane.memory.api;

import io.reliabilityai.gateway.common.Preconditions;
import java.util.EnumMap;
import java.util.Map;

/**
 * One ranked result, with the signals that produced its position.
 *
 * <p>The per-signal breakdown is carried deliberately. A ranking that returns only a final number
 * is unexplainable, and an unexplainable ranking in a memory plane is a support burden — "why did
 * it surface that?" is the first question anyone asks, and it should be answerable without a
 * rebuild.
 *
 * @param record the record itself
 * @param score the weighted, normalised score in {@code [0,1]}
 * @param signals the per-signal contributions before weighting
 * @param redacted whether personal spans were removed on the way out
 */
public record MemoryHit(
    MemoryRecord record, double score, Map<RankingSignal, Double> signals, boolean redacted) {

  /**
   * Validates and freezes the hit.
   *
   * @param record the record
   * @param score the final score
   * @param signals the signal breakdown
   * @param redacted whether the content was redacted
   */
  public MemoryHit {
    Preconditions.requireNonNull(record, "record");
    Preconditions.requireNonNull(signals, "signals");
    if (score < 0.0d || score > 1.0d || Double.isNaN(score)) {
      throw new IllegalArgumentException("score must be within [0,1], was " + score);
    }
    final EnumMap<RankingSignal, Double> copy = new EnumMap<>(RankingSignal.class);
    copy.putAll(signals);
    signals = java.util.Collections.unmodifiableMap(copy);
  }

  /**
   * Returns the contribution of one signal before weighting.
   *
   * @param signal the signal
   * @return its value, or zero when the signal did not apply to this hit
   */
  public double signalValue(final RankingSignal signal) {
    Preconditions.requireNonNull(signal, "signal");
    return signals.getOrDefault(signal, 0.0d);
  }

  /**
   * Reports whether this hit carries untrusted content.
   *
   * <p>Surfaced on the hit as well as on the record so a caller cannot consume the content without
   * the flag being in the same object (AD-026 §10.4).
   *
   * @return true when the underlying record is tainted
   */
  public boolean tainted() {
    return record.tainted();
  }
}

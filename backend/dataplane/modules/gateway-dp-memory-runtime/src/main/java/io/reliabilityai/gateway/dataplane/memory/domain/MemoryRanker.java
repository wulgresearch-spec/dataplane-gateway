package io.reliabilityai.gateway.dataplane.memory.domain;

import io.reliabilityai.gateway.common.Preconditions;
import io.reliabilityai.gateway.dataplane.memory.api.MemoryHit;
import io.reliabilityai.gateway.dataplane.memory.api.MemoryRecord;
import io.reliabilityai.gateway.dataplane.memory.api.MemoryStorePort.ScoredRecord;
import io.reliabilityai.gateway.dataplane.memory.api.RankingSignal;
import io.reliabilityai.gateway.dataplane.memory.api.VectorIndexPort.Similarity;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Turns retrieved records into an order (AD-026 §7.2).
 *
 * <p><b>Deterministic and total.</b> The same inputs give the same order, every time, and ties
 * break on the record id. A ranker whose order varied between two identical calls would make every
 * cursor unsound — a caller paging through results would see records twice or not at all — so
 * stability is a correctness property here, not a nicety.
 *
 * <p><b>Stateless and unlearned.</b> No history, no feedback, no adaptation. A ranker with state
 * would be a model, and a model in the memory plane would need its own governance, versioning and
 * audit — a second inference surface hiding behind a store (AD-026 §7.4).
 */
public final class MemoryRanker {

  /** How quickly freshness decays. At one half-life a record scores 0.5 on that signal. */
  private static final Duration FRESHNESS_HALF_LIFE = Duration.ofDays(7);

  /**
   * How quickly recency-of-use decays. Shorter than freshness: being read is a stronger recent
   * signal.
   */
  private static final Duration USE_HALF_LIFE = Duration.ofDays(2);

  /**
   * The access count at which the frequency signal saturates. Beyond this, more reads do not help.
   */
  private static final double FREQUENCY_SATURATION = 50.0d;

  /** The constant in reciprocal rank fusion. 60 is the value the IR literature settled on. */
  private static final double RRF_K = 60.0d;

  private final RankingSignal.Weights weights;

  /**
   * Creates a ranker.
   *
   * @param weights the per-signal weights
   */
  public MemoryRanker(final RankingSignal.Weights weights) {
    this.weights = Preconditions.requireNonNull(weights, "weights");
  }

  /**
   * Ranks keyword results.
   *
   * @param scored the records with their adapter-native relevance figures
   * @param now the instant to evaluate time-based signals at
   * @param limit the most hits to return
   * @return the ranked hits, best first
   */
  public List<MemoryHit> rankKeyword(
      final List<ScoredRecord> scored, final Instant now, final int limit) {
    Preconditions.requireNonNull(scored, "scored");
    Preconditions.requireNonNull(now, "now");

    final double maxScore = maxOf(scored);
    final List<MemoryHit> hits = new ArrayList<>(scored.size());
    for (final ScoredRecord candidate : scored) {
      final EnumMap<RankingSignal, Double> signals = baseSignals(candidate.record(), now);
      signals.put(RankingSignal.KEYWORD_MATCH, normalise(candidate.score(), maxScore));
      signals.put(RankingSignal.SIMILARITY, 0.0d);
      hits.add(new MemoryHit(candidate.record(), combine(signals), signals, false));
    }
    return sortAndTruncate(hits, limit);
  }

  /**
   * Ranks semantic results.
   *
   * @param records the records, by identity
   * @param similarities the index's matches
   * @param now the instant to evaluate time-based signals at
   * @param limit the most hits to return
   * @return the ranked hits, best first
   */
  public List<MemoryHit> rankSemantic(
      final Map<String, MemoryRecord> records,
      final List<Similarity> similarities,
      final Instant now,
      final int limit) {
    Preconditions.requireNonNull(records, "records");
    Preconditions.requireNonNull(similarities, "similarities");
    Preconditions.requireNonNull(now, "now");

    double maxScore = 0.0d;
    for (final Similarity similarity : similarities) {
      maxScore = Math.max(maxScore, similarity.score());
    }

    final List<MemoryHit> hits = new ArrayList<>(similarities.size());
    for (final Similarity similarity : similarities) {
      final MemoryRecord record = records.get(similarity.id().value());
      if (record == null) {
        // An index entry that dereferences to nothing. Dropped rather than served: the alternative
        // is
        // a hit the caller cannot open (AD-026 §11.2).
        continue;
      }
      final EnumMap<RankingSignal, Double> signals = baseSignals(record, now);
      signals.put(RankingSignal.SIMILARITY, normalise(similarity.score(), maxScore));
      signals.put(RankingSignal.KEYWORD_MATCH, 0.0d);
      hits.add(new MemoryHit(record, combine(signals), signals, false));
    }
    return sortAndTruncate(hits, limit);
  }

  /**
   * Fuses keyword and semantic results by reciprocal rank (AD-026 §7.3).
   *
   * <p><b>Fused on rank, not on score.</b> A keyword engine's figure is an unbounded BM25-family
   * number and a vector index's is a bounded cosine; averaging them means whichever adapter happens
   * to emit larger numbers wins, and swapping an adapter silently changes relevance. Rank is
   * scale-free, so it survives an adapter change.
   *
   * @param keyword the keyword results, in the adapter's order
   * @param semantic the semantic results, in the index's order
   * @param records every retrieved record, by identity
   * @param now the instant to evaluate time-based signals at
   * @param limit the most hits to return
   * @return the fused, ranked hits, best first
   */
  public List<MemoryHit> rankHybrid(
      final List<ScoredRecord> keyword,
      final List<Similarity> semantic,
      final Map<String, MemoryRecord> records,
      final Instant now,
      final int limit) {

    Preconditions.requireNonNull(keyword, "keyword");
    Preconditions.requireNonNull(semantic, "semantic");
    Preconditions.requireNonNull(records, "records");
    Preconditions.requireNonNull(now, "now");

    final Map<String, Double> keywordRrf = new HashMap<>();
    for (int rank = 0; rank < keyword.size(); rank++) {
      keywordRrf.put(keyword.get(rank).record().id().value(), 1.0d / (RRF_K + rank + 1));
    }
    final Map<String, Double> semanticRrf = new HashMap<>();
    for (int rank = 0; rank < semantic.size(); rank++) {
      semanticRrf.put(semantic.get(rank).id().value(), 1.0d / (RRF_K + rank + 1));
    }

    // The best possible fused contribution, used to bring the pair back into [0,1] so the fused
    // signals
    // remain comparable with the time and importance signals they are weighted against.
    final double bestPossible = 2.0d / (RRF_K + 1);

    final java.util.Set<String> union = new java.util.LinkedHashSet<>(keywordRrf.keySet());
    union.addAll(semanticRrf.keySet());

    final List<MemoryHit> hits = new ArrayList<>(union.size());
    for (final String id : union) {
      final MemoryRecord record = records.get(id);
      if (record == null) {
        continue;
      }
      final EnumMap<RankingSignal, Double> signals = baseSignals(record, now);
      final double keywordPart = keywordRrf.getOrDefault(id, 0.0d);
      final double semanticPart = semanticRrf.getOrDefault(id, 0.0d);
      signals.put(RankingSignal.KEYWORD_MATCH, clamp(keywordPart / bestPossible * 2.0d));
      signals.put(RankingSignal.SIMILARITY, clamp(semanticPart / bestPossible * 2.0d));
      hits.add(new MemoryHit(record, combine(signals), signals, false));
    }
    return sortAndTruncate(hits, limit);
  }

  /** The four signals that depend only on the record, not on how it was found. */
  private EnumMap<RankingSignal, Double> baseSignals(final MemoryRecord record, final Instant now) {
    final EnumMap<RankingSignal, Double> signals = new EnumMap<>(RankingSignal.class);
    signals.put(RankingSignal.FRESHNESS, decay(record.ageAt(now), FRESHNESS_HALF_LIFE));
    signals.put(
        RankingSignal.RECENCY_OF_USE,
        record
            .lastAccessedAt()
            .map(last -> decay(nonNegative(Duration.between(last, now)), USE_HALF_LIFE))
            .orElse(0.0d));
    signals.put(RankingSignal.IMPORTANCE, clamp(record.importance()));
    signals.put(RankingSignal.USAGE_FREQUENCY, frequency(record.accessCount()));
    return signals;
  }

  /**
   * Combines the weighted signals into a score in {@code [0,1]}.
   *
   * <p>Normalised by the total weight rather than assuming the weights sum to one, so an operator
   * who sets weights that happen to sum to 1.7 gets a proportionally scaled score rather than one
   * that silently saturates at the top of the range.
   */
  private double combine(final Map<RankingSignal, Double> signals) {
    final double total = weights.total();
    if (total <= 0.0d) {
      // Every weight zero. Every record scores the same, and the stable tie-break gives a defined
      // order rather than an arbitrary one.
      return 0.0d;
    }
    double sum = 0.0d;
    for (final Map.Entry<RankingSignal, Double> entry : signals.entrySet()) {
      sum += weights.weightOf(entry.getKey()) * entry.getValue();
    }
    return clamp(sum / total);
  }

  /**
   * Sorts by score descending, breaking ties on record id.
   *
   * <p>The tie-break is what makes paging sound. Without it two records with identical scores could
   * swap places between calls, and a caller paging by offset would see one twice and miss the
   * other.
   */
  private static List<MemoryHit> sortAndTruncate(final List<MemoryHit> hits, final int limit) {
    hits.sort(
        Comparator.comparingDouble(MemoryHit::score)
            .reversed()
            .thenComparing(hit -> hit.record().id().value()));
    return hits.size() <= limit ? List.copyOf(hits) : List.copyOf(hits.subList(0, limit));
  }

  /** Exponential decay to a half-life, so a signal falls off smoothly rather than at a cliff. */
  private static double decay(final Duration age, final Duration halfLife) {
    final double ratio = (double) age.toMillis() / halfLife.toMillis();
    return clamp(Math.pow(0.5d, ratio));
  }

  /** Damped so a much-read record cannot swamp the other signals outright. */
  private static double frequency(final long accessCount) {
    if (accessCount <= 0L) {
      return 0.0d;
    }
    return clamp(Math.log1p(accessCount) / Math.log1p(FREQUENCY_SATURATION));
  }

  private static double normalise(final double score, final double max) {
    if (max <= 0.0d) {
      return 0.0d;
    }
    return clamp(score / max);
  }

  private static double maxOf(final List<ScoredRecord> scored) {
    double max = 0.0d;
    for (final ScoredRecord candidate : scored) {
      max = Math.max(max, candidate.score());
    }
    return max;
  }

  private static Duration nonNegative(final Duration duration) {
    return duration.isNegative() ? Duration.ZERO : duration;
  }

  private static double clamp(final double value) {
    if (Double.isNaN(value)) {
      return 0.0d;
    }
    return Math.max(0.0d, Math.min(1.0d, value));
  }
}

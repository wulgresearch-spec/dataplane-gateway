package io.reliabilityai.gateway.dataplane.memory.domain;

import static io.reliabilityai.gateway.dataplane.memory.MemoryFixtures.T0;
import static io.reliabilityai.gateway.dataplane.memory.MemoryFixtures.TENANT;
import static org.assertj.core.api.Assertions.assertThat;

import io.reliabilityai.gateway.dataplane.memory.api.DataClassification;
import io.reliabilityai.gateway.dataplane.memory.api.MemoryContent;
import io.reliabilityai.gateway.dataplane.memory.api.MemoryHit;
import io.reliabilityai.gateway.dataplane.memory.api.MemoryRecord;
import io.reliabilityai.gateway.dataplane.memory.api.MemoryRecordId;
import io.reliabilityai.gateway.dataplane.memory.api.MemoryScope;
import io.reliabilityai.gateway.dataplane.memory.api.MemoryStorePort.ScoredRecord;
import io.reliabilityai.gateway.dataplane.memory.api.MemoryType;
import io.reliabilityai.gateway.dataplane.memory.api.RankingSignal;
import io.reliabilityai.gateway.dataplane.memory.api.VectorIndexPort.Similarity;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.DisplayNameGeneration;
import org.junit.jupiter.api.DisplayNameGenerator;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * The six ranking signals, the weighting, and hybrid fusion.
 *
 * <p>Determinism is the property under test throughout. A ranker whose order varied between two
 * identical calls would make every cursor unsound — a caller paging through results would see
 * records twice or miss them entirely — so stability here is a correctness requirement, not a
 * nicety.
 */
@DisplayNameGeneration(DisplayNameGenerator.ReplaceUnderscores.class)
class MemoryRankerTest {

  private final MemoryRanker ranker = new MemoryRanker(RankingSignal.Weights.DEFAULT);

  private static MemoryRecord record(
      final String id,
      final Instant created,
      final double importance,
      final long accessCount,
      final Instant lastAccessed) {
    return new MemoryRecord(
        MemoryRecordId.of(id),
        MemoryScope.ofTenant(TENANT),
        MemoryType.LONG_TERM,
        MemoryContent.plain("body-" + id, MemoryDigest.of("body-" + id)),
        DataClassification.INTERNAL,
        Map.of(),
        created,
        Optional.empty(),
        1,
        importance,
        false,
        false,
        false,
        false,
        Optional.ofNullable(lastAccessed),
        accessCount);
  }

  private static MemoryRecord plain(final String id) {
    return record(id, T0, 0.5d, 0L, null);
  }

  private static Map<String, MemoryRecord> byId(final MemoryRecord... records) {
    final Map<String, MemoryRecord> map = new HashMap<>();
    for (final MemoryRecord record : records) {
      map.put(record.id().value(), record);
    }
    return map;
  }

  // ---- Determinism -----------------------------------------------------------------------------

  @Test
  void rankingTheSameInputTwiceGivesTheSameOrder() {
    final List<ScoredRecord> scored =
        List.of(
            new ScoredRecord(plain("a"), 3.0d),
            new ScoredRecord(plain("b"), 7.0d),
            new ScoredRecord(plain("c"), 1.0d));

    final List<MemoryHit> first = ranker.rankKeyword(scored, T0, 10);
    final List<MemoryHit> second = ranker.rankKeyword(scored, T0, 10);
    assertThat(ids(first)).isEqualTo(ids(second));
  }

  @Test
  void tiesBreakOnRecordIdSoPagingCannotLoopOrSkip() {
    // Every record identical apart from its id: without a stable tie-break the order would be
    // whatever the sort happened to produce, and a caller paging by offset would see duplicates.
    final List<ScoredRecord> tied =
        List.of(
            new ScoredRecord(plain("zebra"), 5.0d),
            new ScoredRecord(plain("alpha"), 5.0d),
            new ScoredRecord(plain("mango"), 5.0d));
    assertThat(ids(ranker.rankKeyword(tied, T0, 10))).containsExactly("alpha", "mango", "zebra");
  }

  @Test
  void theOrderIsIndependentOfTheInputOrder() {
    final ScoredRecord a = new ScoredRecord(plain("a"), 5.0d);
    final ScoredRecord b = new ScoredRecord(plain("b"), 5.0d);
    assertThat(ids(ranker.rankKeyword(List.of(a, b), T0, 10)))
        .isEqualTo(ids(ranker.rankKeyword(List.of(b, a), T0, 10)));
  }

  @Test
  void rankingIsStatelessSoItCannotLearnFromWhatItHasSeen() {
    // A ranker with state would be a model, and a model in the memory plane would need its own
    // governance, versioning and audit (AD-026 §7.4).
    final List<ScoredRecord> scored = List.of(new ScoredRecord(plain("a"), 1.0d));
    final List<MemoryHit> before = ranker.rankKeyword(scored, T0, 10);
    for (int i = 0; i < 100; i++) {
      ranker.rankKeyword(scored, T0, 10);
    }
    assertThat(ranker.rankKeyword(scored, T0, 10).get(0).score()).isEqualTo(before.get(0).score());
  }

  // ---- Bounds ----------------------------------------------------------------------------------

  @ParameterizedTest
  @ValueSource(doubles = {0.0d, 1.0d, 42.0d, 1_000_000.0d})
  void everyScoreStaysWithinZeroAndOneWhateverTheAdapterReports(final double adapterScore) {
    // An adapter's relevance figure is unbounded. If the ranker did not normalise, one adapter's
    // larger numbers would dominate every other signal (AD-026 §7.3).
    final List<MemoryHit> hits =
        ranker.rankKeyword(List.of(new ScoredRecord(plain("a"), adapterScore)), T0, 10);
    assertThat(hits.get(0).score()).isBetween(0.0d, 1.0d);
  }

  @ParameterizedTest
  @EnumSource(RankingSignal.class)
  void everySignalContributesAValueWithinZeroAndOne(final RankingSignal signal) {
    final MemoryRecord busy = record("a", T0.minus(Duration.ofDays(3)), 0.9d, 500L, T0);
    final MemoryHit hit = ranker.rankKeyword(List.of(new ScoredRecord(busy, 10.0d)), T0, 10).get(0);
    assertThat(hit.signalValue(signal)).isBetween(0.0d, 1.0d);
  }

  @Test
  void aLimitIsHonoured() {
    final List<ScoredRecord> many = new ArrayList<>();
    for (int i = 0; i < 50; i++) {
      many.add(new ScoredRecord(plain("r" + i), i));
    }
    assertThat(ranker.rankKeyword(many, T0, 5)).hasSize(5);
  }

  @Test
  void anEmptyInputGivesAnEmptyOrder() {
    assertThat(ranker.rankKeyword(List.of(), T0, 10)).isEmpty();
  }

  // ---- Individual signals
  // ------------------------------------------------------------------------

  @Test
  void afresherRecordOutranksAnOlderOneWhenAllElseIsEqual() {
    final MemoryRecord fresh = record("fresh", T0, 0.5d, 0L, null);
    final MemoryRecord stale = record("stale", T0.minus(Duration.ofDays(60)), 0.5d, 0L, null);
    final List<MemoryHit> hits =
        ranker.rankKeyword(
            List.of(new ScoredRecord(stale, 5.0d), new ScoredRecord(fresh, 5.0d)), T0, 10);
    assertThat(hits.get(0).record().id().value()).isEqualTo("fresh");
  }

  @Test
  void freshnessDecaysToHalfAtItsHalfLife() {
    final MemoryRecord aged = record("a", T0.minus(Duration.ofDays(7)), 0.0d, 0L, null);
    final MemoryHit hit = ranker.rankKeyword(List.of(new ScoredRecord(aged, 0.0d)), T0, 10).get(0);
    assertThat(hit.signalValue(RankingSignal.FRESHNESS)).isCloseTo(0.5d, within(0.01d));
  }

  @Test
  void aMoreImportantRecordOutranksALessImportantOne() {
    final MemoryRecord important = record("important", T0, 1.0d, 0L, null);
    final MemoryRecord trivial = record("trivial", T0, 0.0d, 0L, null);
    final List<MemoryHit> hits =
        ranker.rankKeyword(
            List.of(new ScoredRecord(trivial, 5.0d), new ScoredRecord(important, 5.0d)), T0, 10);
    assertThat(hits.get(0).record().id().value()).isEqualTo("important");
  }

  @Test
  void aFrequentlyReadRecordScoresHigherOnUsageThanAnUnreadOne() {
    final MemoryRecord read = record("read", T0, 0.5d, 40L, T0);
    final MemoryRecord unread = record("unread", T0, 0.5d, 0L, null);
    final List<MemoryHit> hits =
        ranker.rankKeyword(
            List.of(new ScoredRecord(unread, 5.0d), new ScoredRecord(read, 5.0d)), T0, 10);
    assertThat(hits.get(0).record().id().value()).isEqualTo("read");
  }

  @Test
  void usageFrequencyIsDampedSoAHotRecordCannotDominateOutright() {
    final MemoryRecord hot = record("hot", T0, 0.5d, 1_000_000L, T0);
    final MemoryHit hit = ranker.rankKeyword(List.of(new ScoredRecord(hot, 0.0d)), T0, 10).get(0);
    assertThat(hit.signalValue(RankingSignal.USAGE_FREQUENCY)).isLessThanOrEqualTo(1.0d);
  }

  @Test
  void aNeverReadRecordScoresZeroOnBothUsageSignals() {
    final MemoryHit hit =
        ranker.rankKeyword(List.of(new ScoredRecord(plain("a"), 1.0d)), T0, 10).get(0);
    assertThat(hit.signalValue(RankingSignal.USAGE_FREQUENCY)).isZero();
    assertThat(hit.signalValue(RankingSignal.RECENCY_OF_USE)).isZero();
  }

  @Test
  void aSemanticallyPerfectButAncientMatchStillBeatsAFreshIrrelevantOne() {
    // The failure mode a freshness-heavy memory has: it retrieves what happened most recently
    // rather
    // than what was asked about.
    final MemoryRecord ancientRelevant =
        record("relevant", T0.minus(Duration.ofDays(90)), 0.5d, 0L, null);
    final MemoryRecord freshIrrelevant = record("irrelevant", T0, 0.5d, 0L, null);

    final List<MemoryHit> hits =
        ranker.rankSemantic(
            byId(ancientRelevant, freshIrrelevant),
            List.of(
                new Similarity(ancientRelevant.id(), 1.0d),
                new Similarity(freshIrrelevant.id(), 0.05d)),
            T0,
            10);
    assertThat(hits.get(0).record().id().value()).isEqualTo("relevant");
  }

  // ---- Semantic and hybrid
  // ------------------------------------------------------------------------

  @Test
  void aSemanticHitWhoseRecordIsMissingIsDroppedRatherThanServed() {
    // An index entry that dereferences to nothing. Serving it would give the caller a hit they
    // cannot
    // open (AD-026 §11.2).
    final MemoryRecord present = plain("present");
    final List<MemoryHit> hits =
        ranker.rankSemantic(
            byId(present),
            List.of(
                new Similarity(present.id(), 0.9d),
                new Similarity(MemoryRecordId.of("ghost"), 1.0d)),
            T0,
            10);
    assertThat(ids(hits)).containsExactly("present");
  }

  @Test
  void hybridFusesOnRankSoAnAdaptersScaleCannotDecideRelevance() {
    // The keyword adapter reports enormous numbers and the index reports small ones. Fusing on raw
    // score would let the keyword side win everything; fusing on rank is scale-free.
    final MemoryRecord a = plain("a");
    final MemoryRecord b = plain("b");

    final List<MemoryHit> hits =
        ranker.rankHybrid(
            List.of(new ScoredRecord(b, 1_000_000.0d), new ScoredRecord(a, 999_999.0d)),
            List.of(new Similarity(a.id(), 0.9d), new Similarity(b.id(), 0.1d)),
            byId(a, b),
            T0,
            10);
    // Each is first on one side and second on the other, so neither runs away with it.
    assertThat(hits).hasSize(2);
    assertThat(Math.abs(hits.get(0).score() - hits.get(1).score())).isLessThan(0.2d);
  }

  @Test
  void aRecordFoundByBothPathsOutranksOneFoundByOnlyOne() {
    final MemoryRecord both = plain("both");
    final MemoryRecord keywordOnly = plain("keyword");
    final MemoryRecord semanticOnly = plain("semantic");

    final List<MemoryHit> hits =
        ranker.rankHybrid(
            List.of(new ScoredRecord(both, 5.0d), new ScoredRecord(keywordOnly, 5.0d)),
            List.of(new Similarity(both.id(), 0.9d), new Similarity(semanticOnly.id(), 0.9d)),
            byId(both, keywordOnly, semanticOnly),
            T0,
            10);
    assertThat(hits.get(0).record().id().value()).isEqualTo("both");
  }

  @Test
  void hybridReturnsTheUnionOfBothResultLists() {
    final MemoryRecord a = plain("a");
    final MemoryRecord b = plain("b");
    final List<MemoryHit> hits =
        ranker.rankHybrid(
            List.of(new ScoredRecord(a, 1.0d)),
            List.of(new Similarity(b.id(), 1.0d)),
            byId(a, b),
            T0,
            10);
    assertThat(ids(hits)).containsExactlyInAnyOrder("a", "b");
  }

  // ---- Weights
  // ------------------------------------------------------------------------------------

  @Test
  void theDefaultWeightsPutRelevanceAheadOfFreshness() {
    final RankingSignal.Weights weights = RankingSignal.Weights.DEFAULT;
    assertThat(weights.weightOf(RankingSignal.SIMILARITY))
        .isGreaterThan(weights.weightOf(RankingSignal.FRESHNESS));
    assertThat(weights.weightOf(RankingSignal.KEYWORD_MATCH))
        .isGreaterThan(weights.weightOf(RankingSignal.RECENCY_OF_USE));
  }

  @Test
  void anAbsentSignalWeightDefaultsToZeroRatherThanBeingMissing() {
    final RankingSignal.Weights partial =
        RankingSignal.Weights.of(Map.of(RankingSignal.SIMILARITY, 1.0d));
    for (final RankingSignal signal : RankingSignal.values()) {
      // weightOf returns a primitive double, so the previous isNotNull() here could never fail —
      // it asserted that an autoboxed value was non-null. ErrorProne caught it the first time it
      // was able to run. What the test name actually claims is that an unlisted signal yields a
      // real, usable number rather than being absent, which is this.
      assertThat(partial.weightOf(signal)).isFinite().isGreaterThanOrEqualTo(0.0d);
    }
    assertThat(partial.weightOf(RankingSignal.FRESHNESS)).isZero();
  }

  @Test
  void allZeroWeightsProduceAStableOrderRatherThanAnArbitraryOne() {
    final MemoryRanker flat = new MemoryRanker(RankingSignal.Weights.of(Map.of()));
    final List<MemoryHit> hits =
        flat.rankKeyword(
            List.of(new ScoredRecord(plain("b"), 9.0d), new ScoredRecord(plain("a"), 1.0d)),
            T0,
            10);
    assertThat(ids(hits)).containsExactly("a", "b");
  }

  @Test
  void weightsThatDoNotSumToOneStillProduceAScoreWithinRange() {
    final MemoryRanker heavy =
        new MemoryRanker(
            RankingSignal.Weights.of(
                Map.of(RankingSignal.SIMILARITY, 5.0d, RankingSignal.FRESHNESS, 3.0d)));
    final MemoryHit hit =
        heavy.rankKeyword(List.of(new ScoredRecord(plain("a"), 1.0d)), T0, 10).get(0);
    assertThat(hit.score()).isBetween(0.0d, 1.0d);
  }

  @Test
  void aNegativeWeightIsUnrepresentable() {
    org.assertj.core.api.Assertions.assertThatThrownBy(
            () -> RankingSignal.Weights.of(Map.of(RankingSignal.SIMILARITY, -1.0d)))
        .isInstanceOf(IllegalArgumentException.class);
  }

  private static List<String> ids(final List<MemoryHit> hits) {
    return hits.stream().map(hit -> hit.record().id().value()).toList();
  }

  private static org.assertj.core.data.Offset<Double> within(final double tolerance) {
    return org.assertj.core.data.Offset.offset(tolerance);
  }
}

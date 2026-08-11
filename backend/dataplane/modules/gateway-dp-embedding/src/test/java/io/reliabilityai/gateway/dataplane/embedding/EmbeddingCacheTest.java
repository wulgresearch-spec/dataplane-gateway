package io.reliabilityai.gateway.dataplane.embedding;

import static io.reliabilityai.gateway.dataplane.embedding.EmbeddingFixtures.ACME;
import static io.reliabilityai.gateway.dataplane.embedding.EmbeddingFixtures.DIMENSION;
import static io.reliabilityai.gateway.dataplane.embedding.EmbeddingFixtures.GLOBEX;
import static io.reliabilityai.gateway.dataplane.embedding.EmbeddingFixtures.MODEL;
import static org.assertj.core.api.Assertions.assertThat;

import io.reliabilityai.gateway.canonical.identity.TenantScope;
import io.reliabilityai.gateway.dataplane.embedding.api.CanonicalEmbedding;
import io.reliabilityai.gateway.dataplane.embedding.api.EmbeddingFailure;
import io.reliabilityai.gateway.dataplane.embedding.api.EmbeddingUsage;
import io.reliabilityai.gateway.dataplane.embedding.application.EmbeddingCache;
import io.reliabilityai.gateway.dataplane.embedding.internal.InProcessEmbeddingMetrics;
import io.reliabilityai.gateway.dataplane.provider.api.ProviderId;
import java.time.Duration;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * The deterministic embedding cache: its key, its expiry, and the isolation it is there to
 * preserve.
 *
 * <p>The cache is the component that decides what the bill is. It is also, because it is keyed by a
 * hash of tenant content, the component where an isolation mistake would be least visible: a
 * cross-tenant hit returns a perfectly valid vector, computed from the right text, for the wrong
 * tenant. Nothing downstream can detect it. That is why so much of this file is about the key.
 */
@DisplayName("embedding cache")
final class EmbeddingCacheTest {

  private EmbeddingFixtures.TestClock clock;
  private InProcessEmbeddingMetrics metrics;
  private EmbeddingCache cache;

  @BeforeEach
  void setUp() {
    clock = new EmbeddingFixtures.TestClock();
    metrics = new InProcessEmbeddingMetrics();
    cache = new EmbeddingCache(clock, metrics, Duration.ofHours(1), Duration.ofMinutes(10), 100);
  }

  private CanonicalEmbedding embedding(final float value) {
    return new CanonicalEmbedding(
        ProviderId.of("p"),
        MODEL,
        DIMENSION,
        clock.now(),
        EmbeddingUsage.FREE,
        true,
        Map.of(),
        EmbeddingFixtures.flat(DIMENSION, value));
  }

  @Nested
  @DisplayName("the key")
  final class TheKey {

    @Test
    @DisplayName("is a hex SHA-256 digest")
    void isAHexSha256Digest() {
      final String key = EmbeddingFixtures.key(ACME, "hello");

      assertThat(key).hasSize(64).matches("[0-9a-f]{64}");
    }

    @Test
    @DisplayName("is the same for the same tenant, model and text")
    void isTheSameForTheSameTenantModelAndText() {
      assertThat(EmbeddingFixtures.key(ACME, "hello"))
          .isEqualTo(EmbeddingFixtures.key(ACME, "hello"));
    }

    @Test
    @DisplayName("differs when any one of tenant, model or text differs")
    void differsWhenAnyOneOfTenantModelOrTextDiffers() {
      final String base = EmbeddingFixtures.key(ACME, "hello");

      assertThat(base)
          .isNotEqualTo(EmbeddingFixtures.key(GLOBEX, "hello"))
          .isNotEqualTo(
              EmbeddingCache.keyFor(
                  ACME,
                  EmbeddingFixtures.modelNamed("text-large-3072"),
                  EmbeddingFixtures.PROVIDER,
                  "hello"))
          .isNotEqualTo(EmbeddingFixtures.key(ACME, "hellp"));
    }

    @Test
    @DisplayName("cannot be collided by moving a character across a field boundary")
    void cannotBeCollidedByMovingACharacterAcrossAFieldBoundary() {
      // The reason every field is length-prefixed rather than concatenated with a separator.
      // Without
      // the prefix these two would hash identical bytes, and a tenant able to choose its own
      // identifier could then read another tenant's cache entries by construction. Same argument as
      // AD-028: a chosen-name collision is an isolation bypass that needs no bug to exploit.
      final String left = EmbeddingFixtures.key(TenantScope.of("ac", "me"), "x");
      final String right = EmbeddingFixtures.key(TenantScope.of("a", "cme"), "x");

      assertThat(left).isNotEqualTo(right);
    }

    @Test
    @DisplayName("cannot be collided by moving the model boundary into the text")
    void cannotBeCollidedByMovingTheModelBoundaryIntoTheText() {
      assertThat(
              EmbeddingCache.keyFor(
                  ACME, EmbeddingFixtures.modelNamed("abc"), EmbeddingFixtures.PROVIDER, "def"))
          .isNotEqualTo(
              EmbeddingCache.keyFor(
                  ACME, EmbeddingFixtures.modelNamed("ab"), EmbeddingFixtures.PROVIDER, "cdef"));
    }

    @Test
    @DisplayName("handles an empty text without collapsing onto a neighbour")
    void handlesAnEmptyTextWithoutCollapsingOntoANeighbour() {
      assertThat(EmbeddingFixtures.key(ACME, ""))
          .isNotEqualTo(EmbeddingFixtures.key(ACME, " "))
          .hasSize(64);
    }
  }

  @Nested
  @DisplayName("the positive cache")
  final class ThePositiveCache {

    @Test
    @DisplayName("returns nothing and counts a miss for an unknown key")
    void returnsNothingAndCountsAMissForAnUnknownKey() {
      assertThat(cache.get("nope")).isEmpty();

      assertThat(metrics.count("cache.miss")).isEqualTo(1L);
      assertThat(metrics.count("cache.hit")).isZero();
    }

    @Test
    @DisplayName("returns what was stored and counts a hit")
    void returnsWhatWasStoredAndCountsAHit() {
      final String key = EmbeddingFixtures.key(ACME, "hello");
      cache.put(key, embedding(0.5f));

      assertThat(cache.get(key)).isPresent();
      assertThat(metrics.count("cache.hit")).isEqualTo(1L);
      assertThat(metrics.cacheHitRatio()).isEqualTo(1.0);
    }

    @Test
    @DisplayName("stops returning an entry once its TTL has passed")
    void stopsReturningAnEntryOnceItsTtlHasPassed() {
      final String key = EmbeddingFixtures.key(ACME, "hello");
      cache.put(key, embedding(0.5f));

      clock.advance(Duration.ofMinutes(59));
      assertThat(cache.get(key)).isPresent();

      clock.advance(Duration.ofMinutes(2));
      assertThat(cache.get(key)).isEmpty();
    }

    @Test
    @DisplayName("drops an expired entry rather than holding it for ever")
    void dropsAnExpiredEntryRatherThanHoldingItForEver() {
      cache.put(EmbeddingFixtures.key(ACME, "hello"), embedding(0.5f));
      clock.advance(Duration.ofHours(2));

      cache.get(EmbeddingFixtures.key(ACME, "hello"));

      // An expiring read that leaves the entry in place turns the TTL into a lie about freshness
      // while keeping every byte of the memory cost.
      assertThat(cache.size()).isZero();
    }

    @Test
    @DisplayName("hands out a copy, so a caller cannot rewrite what the next caller reads")
    void handsOutACopySoACallerCannotRewriteWhatTheNextCallerReads() {
      final String key = EmbeddingFixtures.key(ACME, "hello");
      cache.put(key, embedding(0.5f));

      final float[] borrowed = cache.get(key).orElseThrow().vector();
      borrowed[0] = 99.0f;

      assertThat(cache.get(key).orElseThrow().vector()[0]).isEqualTo(0.5f);
    }
  }

  @Nested
  @DisplayName("the negative cache")
  final class TheNegativeCache {

    @Test
    @DisplayName("remembers a permanent failure so the input is not paid for twice")
    void remembersAPermanentFailureSoTheInputIsNotPaidForTwice() {
      final String key = EmbeddingFixtures.key(ACME, "too long");
      cache.putNegative(key, EmbeddingFailure.TOO_LARGE);

      assertThat(cache.getNegative(key)).contains(EmbeddingFailure.TOO_LARGE);
      assertThat(metrics.count("cache.negativeHit")).isEqualTo(1L);
    }

    @Test
    @DisplayName("refuses to remember a transient failure")
    void refusesToRememberATransientFailure() {
      final String key = EmbeddingFixtures.key(ACME, "throttled");

      cache.putNegative(key, EmbeddingFailure.RATE_LIMITED);
      cache.putNegative(key, EmbeddingFailure.TIMEOUT);
      cache.putNegative(key, EmbeddingFailure.UNAVAILABLE);
      cache.putNegative(key, EmbeddingFailure.NETWORK);

      // Caching a rate limit turns a momentary throttle into a lasting refusal — the exact opposite
      // of the correct response to back-pressure, and self-inflicted rather than provider-imposed.
      assertThat(cache.getNegative(key)).isEmpty();
      assertThat(cache.negativeSize()).isZero();
    }

    @Test
    @DisplayName("forgets a permanent failure once its own TTL has passed")
    void forgetsAPermanentFailureOnceItsOwnTtlHasPassed() {
      final String key = EmbeddingFixtures.key(ACME, "rejected");
      cache.putNegative(key, EmbeddingFailure.REJECTED);

      clock.advance(Duration.ofMinutes(11));

      // The negative TTL is short on purpose: "permanent" here means the provider will not change
      // its mind about this input, not that the deployment will never change model or limits.
      assertThat(cache.getNegative(key)).isEmpty();
      assertThat(cache.negativeSize()).isZero();
    }

    @Test
    @DisplayName("is keyed the same way, so one tenant's refusal is not another's")
    void isKeyedTheSameWaySoOneTenantsRefusalIsNotAnothers() {
      cache.putNegative(EmbeddingFixtures.key(ACME, "x"), EmbeddingFailure.TOO_LARGE);

      assertThat(cache.getNegative(EmbeddingFixtures.key(GLOBEX, "x"))).isEmpty();
    }
  }

  @Nested
  @DisplayName("capacity")
  final class Capacity {

    @Test
    @DisplayName("holds at the ceiling rather than growing without bound")
    void holdsAtTheCeilingRatherThanGrowingWithoutBound() {
      for (int i = 0; i < 1_000; i++) {
        cache.put(EmbeddingFixtures.key(ACME, "entry-" + i), embedding(0.5f));
      }

      // Ten times the ceiling written in. A cache that kept them all is not a cache, it is a leak
      // with a hit ratio.
      assertThat(cache.size()).isLessThanOrEqualTo(100);
      assertThat(cache.evictions()).isPositive();
    }

    @Test
    @DisplayName("evicts in batches, so a store at capacity is not a full scan")
    void evictsInBatchesSoAStoreAtCapacityIsNotAFullScan() {
      for (int i = 0; i < 100; i++) {
        cache.put(EmbeddingFixtures.key(ACME, "fill-" + i), embedding(0.5f));
      }
      final long afterFill = cache.evictions();

      cache.put(EmbeddingFixtures.key(ACME, "one-more"), embedding(0.5f));

      // One store past the ceiling frees a tenth of it. Freeing exactly one slot instead would make
      // every subsequent store walk the whole map to remove a single entry, for ever.
      assertThat(cache.evictions() - afterFill).isGreaterThan(1L);
      assertThat(cache.size()).isLessThanOrEqualTo(100);
    }

    @Test
    @DisplayName("recovers its ceiling after an overshoot")
    void recoversItsCeilingAfterAnOvershoot() {
      for (int i = 0; i < 500; i++) {
        cache.put(EmbeddingFixtures.key(ACME, "burst-" + i), embedding(0.5f));
      }

      assertThat(cache.size()).isLessThanOrEqualTo(100);
    }
  }

  @Nested
  @DisplayName("invalidation")
  final class Invalidation {

    @Test
    @DisplayName("clearing a tenant clears everything, which is blunt and is recorded as B60")
    void clearingATenantClearsEverything() {
      cache.put(EmbeddingFixtures.key(ACME, "a"), embedding(0.5f));
      cache.put(EmbeddingFixtures.key(GLOBEX, "b"), embedding(0.5f));
      cache.putNegative(EmbeddingFixtures.key(ACME, "c"), EmbeddingFailure.TOO_LARGE);

      cache.invalidateTenant(ACME);

      // Asserted as it behaves, not as it reads. Keys are hashes, so entries cannot be selected by
      // tenant without a reverse index; the other tenant loses its entries too. Correct — nothing
      // stale survives — but over-broad, and the test says so rather than implying precision.
      assertThat(cache.size()).isZero();
      assertThat(cache.negativeSize()).isZero();
      assertThat(cache.get(EmbeddingFixtures.key(GLOBEX, "b"))).isEmpty();
    }
  }
}

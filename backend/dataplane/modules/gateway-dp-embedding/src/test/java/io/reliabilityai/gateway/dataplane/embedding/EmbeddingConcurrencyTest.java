package io.reliabilityai.gateway.dataplane.embedding;

import static io.reliabilityai.gateway.dataplane.embedding.EmbeddingFixtures.ACME;
import static io.reliabilityai.gateway.dataplane.embedding.EmbeddingFixtures.DIMENSION;
import static io.reliabilityai.gateway.dataplane.embedding.EmbeddingFixtures.GLOBEX;
import static io.reliabilityai.gateway.dataplane.embedding.EmbeddingFixtures.MODEL;
import static org.assertj.core.api.Assertions.assertThat;

import io.reliabilityai.gateway.canonical.identity.TenantScope;
import io.reliabilityai.gateway.dataplane.embedding.api.CanonicalEmbedding;
import io.reliabilityai.gateway.dataplane.embedding.api.EmbeddingAuditPort;
import io.reliabilityai.gateway.dataplane.embedding.api.EmbeddingGovernancePort;
import io.reliabilityai.gateway.dataplane.embedding.api.EmbeddingRequest;
import io.reliabilityai.gateway.dataplane.embedding.api.EmbeddingResponse;
import io.reliabilityai.gateway.dataplane.embedding.application.EmbeddingBatcher;
import io.reliabilityai.gateway.dataplane.embedding.application.EmbeddingCache;
import io.reliabilityai.gateway.dataplane.embedding.application.EmbeddingPipeline;
import io.reliabilityai.gateway.dataplane.embedding.application.EmbeddingProviderRegistry;
import io.reliabilityai.gateway.dataplane.embedding.domain.EmbeddingFailurePolicy;
import io.reliabilityai.gateway.dataplane.embedding.domain.EmbeddingNormalizer;
import io.reliabilityai.gateway.dataplane.embedding.domain.EmbeddingRetryPolicy;
import io.reliabilityai.gateway.dataplane.embedding.internal.DeterministicEmbeddingProvider;
import io.reliabilityai.gateway.dataplane.embedding.internal.InProcessEmbeddingMetrics;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * What the pipeline does when many callers arrive at once, and whether it still agrees with itself.
 *
 * <p>Concurrency here is not incidental. The Memory Runtime write path is the intended caller,
 * writes arrive from every request thread in the process, and the two components that make
 * embedding affordable — the cache and the batcher — are both shared mutable state on that path. A
 * cache that races returns one tenant's vector to another; a batcher that races completes the wrong
 * caller's future with the wrong vector. Neither failure throws. Both would be found in production,
 * by a user, as bad retrieval.
 *
 * <p>So the assertions are on <em>identity</em> rather than on counts wherever possible: every
 * caller must receive the vector for the text it asked about. A test that only counted calls would
 * pass against an implementation that handed back correctly-shaped nonsense.
 *
 * <p>Every test here is bounded by an explicit timeout. A concurrency bug that deadlocks should
 * fail the suite, not hang it.
 */
@DisplayName("concurrency and determinism")
final class EmbeddingConcurrencyTest {

  /** The mission's figure, used literally. */
  private static final int CONCURRENT_REQUESTS = 100;

  private EmbeddingFixtures.TestClock clock;
  private InProcessEmbeddingMetrics metrics;
  private EmbeddingCache cache;
  private EmbeddingProviderRegistry registry;
  private ExecutorService pool;

  @BeforeEach
  void setUp() {
    clock = new EmbeddingFixtures.TestClock();
    metrics = new InProcessEmbeddingMetrics();
    cache = new EmbeddingCache(clock, metrics, Duration.ofHours(1), Duration.ofMinutes(10), 10_000);
    registry = new EmbeddingProviderRegistry(EmbeddingFailurePolicy.defaults(), clock);
    pool = Executors.newFixedThreadPool(16);
  }

  @AfterEach
  void tearDown() throws InterruptedException {
    pool.shutdownNow();
    assertThat(pool.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
  }

  private EmbeddingPipeline pipelineOver(final EmbeddingFixtures.ConcurrentProvider provider) {
    registry.register(provider);
    return new EmbeddingPipeline(
        registry,
        cache,
        EmbeddingGovernancePort.PERMISSIVE,
        EmbeddingRetryPolicy.immediate(3),
        metrics,
        EmbeddingAuditPort.NOOP);
  }

  private static EmbeddingRequest request(final TenantScope tenant, final String text) {
    return EmbeddingRequest.of(tenant, MODEL, text, EmbeddingRequest.EmbeddingPurpose.WRITE);
  }

  /**
   * Releases every task at once and waits for all of them.
   *
   * <p>Submitting a hundred tasks to a pool does not make them concurrent — the first few finish
   * before the last are scheduled. A start latch every worker blocks on is what turns a hundred
   * submissions into a hundred simultaneous callers, which is the condition under test.
   *
   * @param count how many workers
   * @param work what each worker does, given its index
   * @return each worker's result, in index order
   * @throws Exception when a worker fails or the run does not finish in time
   */
  private <T> List<T> allAtOnce(final int count, final IndexedWork<T> work) throws Exception {
    final CountDownLatch start = new CountDownLatch(1);
    final List<CompletableFuture<T>> futures = new ArrayList<>(count);
    for (int i = 0; i < count; i++) {
      final int index = i;
      final CompletableFuture<T> future = new CompletableFuture<>();
      futures.add(future);
      pool.execute(
          () -> {
            try {
              start.await();
              future.complete(work.apply(index));
            } catch (final Throwable failure) {
              future.completeExceptionally(failure);
            }
          });
    }
    start.countDown();
    final List<T> results = new ArrayList<>(count);
    for (final CompletableFuture<T> future : futures) {
      results.add(future.get(30, TimeUnit.SECONDS));
    }
    return results;
  }

  /** Work parameterised by a worker index. */
  @FunctionalInterface
  private interface IndexedWork<T> {

    /**
     * Performs the work.
     *
     * @param index the worker index
     * @return the result
     * @throws Exception when the work fails
     */
    T apply(int index) throws Exception;
  }

  @Test
  @Timeout(60)
  @DisplayName("100 concurrent requests for distinct texts all succeed, each with its own vector")
  void oneHundredConcurrentRequestsForDistinctTextsAllSucceed() throws Exception {
    final EmbeddingFixtures.ConcurrentProvider provider =
        new EmbeddingFixtures.ConcurrentProvider("p", clock, 64);
    final EmbeddingPipeline pipeline = pipelineOver(provider);

    final List<EmbeddingResponse> responses =
        allAtOnce(CONCURRENT_REQUESTS, i -> pipeline.embed(request(ACME, "text-" + i)));

    assertThat(responses).hasSize(CONCURRENT_REQUESTS);
    final Map<String, CanonicalEmbedding> byText = new ConcurrentHashMap<>();
    for (int i = 0; i < CONCURRENT_REQUESTS; i++) {
      final EmbeddingResponse response = responses.get(i);
      assertThat(response.complete()).isTrue();
      byText.put("text-" + i, response.at(0).orElseThrow());
    }

    // A hundred distinct inputs must produce a hundred distinct vectors. Collapsing two of them is
    // exactly what a shared mutable buffer in the normalizer or the cache would do, and it is
    // invisible in a count-based assertion.
    assertThat(byText.values().stream().map(e -> List.of(boxed(e.vector()))).distinct().count())
        .isEqualTo(CONCURRENT_REQUESTS);
    assertThat(provider.inputs()).isEqualTo(CONCURRENT_REQUESTS);
  }

  @Test
  @Timeout(60)
  @DisplayName("100 concurrent requests for the same text agree on one vector")
  void oneHundredConcurrentRequestsForTheSameTextAgreeOnOneVector() throws Exception {
    final EmbeddingFixtures.ConcurrentProvider provider =
        new EmbeddingFixtures.ConcurrentProvider("p", clock, 64);
    final EmbeddingPipeline pipeline = pipelineOver(provider);

    final List<EmbeddingResponse> responses =
        allAtOnce(CONCURRENT_REQUESTS, i -> pipeline.embed(request(ACME, "the same text")));

    final List<Float> first = List.of(boxed(responses.get(0).at(0).orElseThrow().vector()));
    for (final EmbeddingResponse response : responses) {
      assertThat(List.of(boxed(response.at(0).orElseThrow().vector()))).isEqualTo(first);
    }

    // The cache is racy by construction — a hundred threads can all miss before any of them stores
    // —
    // so this deliberately does NOT assert one provider call. What must hold is that whichever path
    // each caller took, they all got the same answer, and that some deduplication happened at all.
    assertThat(provider.calls()).isLessThan(CONCURRENT_REQUESTS);
    assertThat(cache.size()).isEqualTo(1);
  }

  @Test
  @Timeout(60)
  @DisplayName("concurrent tenants never receive each other's vectors")
  void concurrentTenantsNeverReceiveEachOthersVectors() throws Exception {
    final EmbeddingFixtures.ConcurrentProvider provider =
        new EmbeddingFixtures.ConcurrentProvider("p", clock, 64);
    final EmbeddingPipeline pipeline = pipelineOver(provider);

    // Both tenants embed the identical string. The vectors are arithmetically identical, so the
    // only
    // thing that can go wrong is provenance — and provenance is the whole point of keying by
    // tenant.
    final List<EmbeddingResponse> responses =
        allAtOnce(
            CONCURRENT_REQUESTS,
            i -> pipeline.embed(request(i % 2 == 0 ? ACME : GLOBEX, "shared string")));

    for (final EmbeddingResponse response : responses) {
      assertThat(response.complete()).isTrue();
    }
    // One entry per tenant, never one shared entry.
    assertThat(cache.size()).isEqualTo(2);
    assertThat(EmbeddingFixtures.key(ACME, "shared string"))
        .isNotEqualTo(EmbeddingFixtures.key(GLOBEX, "shared string"));
  }

  @Test
  @Timeout(60)
  @DisplayName("100 concurrent callers through the batcher each receive their own text's vector")
  void oneHundredConcurrentCallersThroughTheBatcherEachReceiveTheirOwnVector() throws Exception {
    final EmbeddingFixtures.ConcurrentProvider provider =
        new EmbeddingFixtures.ConcurrentProvider("p", clock, 32);
    final EmbeddingPipeline pipeline = pipelineOver(provider);

    try (EmbeddingBatcher batcher =
        new EmbeddingBatcher(
            32, 1_000_000, Duration.ofMillis(50), 1_000, metrics, pipeline::embed)) {

      final List<EmbeddingResponse.Outcome> outcomes =
          allAtOnce(
              CONCURRENT_REQUESTS,
              i ->
                  batcher
                      .submit(ACME, MODEL, "batched-" + i, EmbeddingRequest.EmbeddingPurpose.WRITE)
                      .get(30, TimeUnit.SECONDS));

      // The batcher completes futures by position within a drained batch. An off-by-one there gives
      // every caller a plausible vector belonging to its neighbour — the single most damaging bug
      // this component can have, and one no count-based assertion can see.
      for (int i = 0; i < CONCURRENT_REQUESTS; i++) {
        final EmbeddingResponse.Outcome outcome = outcomes.get(i);
        assertThat(outcome.ok()).isTrue();
        final float[] expected =
            EmbeddingNormalizer.normalize(
                EmbeddingFixtures.ConcurrentProvider.deterministic("batched-" + i));
        assertThat(((EmbeddingResponse.Outcome.Embedded) outcome).embedding().vector())
            .containsExactly(expected);
      }
    }
  }

  @Test
  @Timeout(60)
  @DisplayName("concurrent callers are genuinely coalesced into fewer, larger provider calls")
  void concurrentCallersAreGenuinelyCoalescedIntoFewerLargerProviderCalls() throws Exception {
    final EmbeddingFixtures.ConcurrentProvider provider =
        new EmbeddingFixtures.ConcurrentProvider("p", clock, 64);
    final EmbeddingPipeline pipeline = pipelineOver(provider);

    try (EmbeddingBatcher batcher =
        new EmbeddingBatcher(
            64, 1_000_000, Duration.ofMillis(100), 1_000, metrics, pipeline::embed)) {

      allAtOnce(
          CONCURRENT_REQUESTS,
          i ->
              batcher
                  .submit(ACME, MODEL, "coalesce-" + i, EmbeddingRequest.EmbeddingPurpose.WRITE)
                  .get(30, TimeUnit.SECONDS));

      // A hundred inputs through a batcher that never coalesced would be a hundred provider calls.
      // The exact figure depends on thread scheduling, so the assertion is on the property that
      // batching exists at all rather than on a number that would be flaky.
      assertThat(provider.inputs()).isEqualTo(CONCURRENT_REQUESTS);
      assertThat(provider.calls()).isLessThan(CONCURRENT_REQUESTS);
      assertThat(metrics.meanBatchSize()).isGreaterThan(1.0);
    }
  }

  @Test
  @Timeout(60)
  @DisplayName("the queue depth returns to zero after a concurrent burst")
  void theQueueDepthReturnsToZeroAfterAConcurrentBurst() throws Exception {
    final EmbeddingFixtures.ConcurrentProvider provider =
        new EmbeddingFixtures.ConcurrentProvider("p", clock, 16);
    final EmbeddingPipeline pipeline = pipelineOver(provider);

    try (EmbeddingBatcher batcher =
        new EmbeddingBatcher(
            16, 1_000_000, Duration.ofMillis(20), 1_000, metrics, pipeline::embed)) {

      allAtOnce(
          CONCURRENT_REQUESTS,
          i ->
              batcher
                  .submit(ACME, MODEL, "depth-" + i, EmbeddingRequest.EmbeddingPurpose.WRITE)
                  .get(30, TimeUnit.SECONDS));

      // A depth that does not return to zero is a leak: the counter is decremented on a path the
      // batch did not take, and the queue reads as permanently full some hours into production.
      assertThat(batcher.queueDepth()).isZero();
      assertThat(metrics.peakQueueDepth()).isGreaterThan(0);
    }
  }

  @Test
  @Timeout(60)
  @DisplayName("a concurrent burst holds no more provider calls open than the pool allows")
  void aConcurrentBurstHoldsNoMoreProviderCallsOpenThanThePoolAllows() throws Exception {
    final EmbeddingFixtures.ConcurrentProvider provider =
        new EmbeddingFixtures.ConcurrentProvider("p", clock, 64, 5L);
    final EmbeddingPipeline pipeline = pipelineOver(provider);

    allAtOnce(CONCURRENT_REQUESTS, i -> pipeline.embed(request(ACME, "slow-" + i)));

    // The pipeline adds no concurrency of its own: it calls the provider on the caller's thread. If
    // this ever exceeded the caller pool, something in the pipeline had started threads nobody
    // configured, and a fixed provider connection limit would be silently exceeded in production.
    assertThat(provider.peakConcurrent()).isLessThanOrEqualTo(16);
  }

  @Test
  @Timeout(60)
  @DisplayName("the same text embeds to the same vector across independent pipelines")
  void theSameTextEmbedsToTheSameVectorAcrossIndependentPipelines() {
    final EmbeddingPipeline first =
        pipelineOver(new EmbeddingFixtures.ConcurrentProvider("p", clock, 64));
    final EmbeddingProviderRegistry other =
        new EmbeddingProviderRegistry(EmbeddingFailurePolicy.defaults(), clock);
    other.register(new EmbeddingFixtures.ConcurrentProvider("p", clock, 64));
    final EmbeddingPipeline second =
        new EmbeddingPipeline(
            other,
            new EmbeddingCache(clock, metrics, Duration.ofHours(1), Duration.ofMinutes(10), 1_000),
            EmbeddingGovernancePort.PERMISSIVE,
            EmbeddingRetryPolicy.immediate(3),
            metrics,
            EmbeddingAuditPort.NOOP);

    // Determinism across processes is what makes the cache safe to share and re-embedding after a
    // restart cheap. A pipeline that mixed in a clock, a nonce or an iteration order would break it
    // here and nowhere else until a re-index produced vectors no existing query matched.
    assertThat(first.embed(request(ACME, "stable")).at(0).orElseThrow().vector())
        .containsExactly(second.embed(request(ACME, "stable")).at(0).orElseThrow().vector());
  }

  @Test
  @Timeout(60)
  @DisplayName("the local reference provider is deterministic under concurrent load")
  void theLocalReferenceProviderIsDeterministicUnderConcurrentLoad() throws Exception {
    final DeterministicEmbeddingProvider provider =
        new DeterministicEmbeddingProvider(clock, DIMENSION);

    final List<EmbeddingResponse> responses =
        allAtOnce(
            CONCURRENT_REQUESTS,
            i ->
                provider.embed(
                    EmbeddingRequest.of(
                        ACME, MODEL, "seed", EmbeddingRequest.EmbeddingPurpose.QUERY)));

    final float[] first = responses.get(0).at(0).orElseThrow().vector();
    for (final EmbeddingResponse response : responses) {
      assertThat(response.at(0).orElseThrow().vector()).containsExactly(first);
    }
    assertThat(EmbeddingNormalizer.isUnitLength(first)).isTrue();
  }

  @Test
  @Timeout(60)
  @DisplayName(
      "concurrent cache writes never exceed the entry ceiling by more than the writer count")
  void concurrentCacheWritesNeverExceedTheEntryCeiling() throws Exception {
    final int ceiling = 50;
    final EmbeddingCache bounded =
        new EmbeddingCache(clock, metrics, Duration.ofHours(1), Duration.ofMinutes(10), ceiling);
    final AtomicInteger sequence = new AtomicInteger();

    allAtOnce(
        CONCURRENT_REQUESTS,
        i -> {
          for (int n = 0; n < 20; n++) {
            final String text = "entry-" + sequence.incrementAndGet();
            bounded.put(
                EmbeddingFixtures.key(ACME, text),
                new CanonicalEmbedding(
                    io.reliabilityai.gateway.dataplane.provider.api.ProviderId.of("p"),
                    MODEL,
                    DIMENSION,
                    clock.now(),
                    io.reliabilityai.gateway.dataplane.embedding.api.EmbeddingUsage.FREE,
                    true,
                    Map.of(),
                    EmbeddingFixtures.flat(DIMENSION, 0.5f)));
          }
          return null;
        });

    // Eviction is best-effort and lock-free, so the bound is soft: concurrent writers can each pass
    // the capacity check before any of them stores. What must not happen is unbounded growth, which
    // is the failure that ends as an out-of-memory kill rather than a lower hit ratio.
    assertThat(bounded.size()).isLessThanOrEqualTo(ceiling + CONCURRENT_REQUESTS);
    assertThat(bounded.evictions()).isPositive();
  }

  /**
   * Boxes a vector so two of them can be compared by value.
   *
   * @param vector the components
   * @return the boxed components
   */
  private static Float[] boxed(final float[] vector) {
    final Float[] boxed = new Float[vector.length];
    for (int i = 0; i < vector.length; i++) {
      boxed[i] = vector[i];
    }
    return boxed;
  }
}

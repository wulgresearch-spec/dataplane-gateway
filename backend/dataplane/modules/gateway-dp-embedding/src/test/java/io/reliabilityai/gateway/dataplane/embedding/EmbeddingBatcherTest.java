package io.reliabilityai.gateway.dataplane.embedding;

import static io.reliabilityai.gateway.dataplane.embedding.EmbeddingFixtures.ACME;
import static io.reliabilityai.gateway.dataplane.embedding.EmbeddingFixtures.MODEL;
import static org.assertj.core.api.Assertions.assertThat;

import io.reliabilityai.gateway.dataplane.embedding.api.CanonicalEmbedding;
import io.reliabilityai.gateway.dataplane.embedding.api.EmbeddingCapability;
import io.reliabilityai.gateway.dataplane.embedding.api.EmbeddingFailure;
import io.reliabilityai.gateway.dataplane.embedding.api.EmbeddingMetricsPort;
import io.reliabilityai.gateway.dataplane.embedding.api.EmbeddingModel;
import io.reliabilityai.gateway.dataplane.embedding.api.EmbeddingRequest;
import io.reliabilityai.gateway.dataplane.embedding.api.EmbeddingResponse;
import io.reliabilityai.gateway.dataplane.embedding.api.EmbeddingUsage;
import io.reliabilityai.gateway.dataplane.embedding.application.EmbeddingBatcher;
import io.reliabilityai.gateway.dataplane.provider.api.ProviderId;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/** Splitting is a pure function; coalescing needs a clock. Both are tested here. */
@DisplayName("batching")
final class EmbeddingBatcherTest {

  private static final EmbeddingCapability SMALL =
      new EmbeddingCapability(Set.of(new EmbeddingModel(MODEL, 8, 8191, 10L)), 4, 40, false, false);

  private static EmbeddingRequest request(final int count, final int textLength) {
    final List<String> texts = new ArrayList<>(count);
    for (int i = 0; i < count; i++) {
      texts.add("x".repeat(textLength));
    }
    return new EmbeddingRequest(ACME, MODEL, texts, EmbeddingRequest.EmbeddingPurpose.WRITE);
  }

  @Test
  @DisplayName("a request within the limits is not split")
  void aRequestWithinTheLimitsIsNotSplit() {
    assertThat(EmbeddingBatcher.split(request(3, 4), SMALL)).hasSize(1);
  }

  @Test
  @DisplayName("a request over the batch size is split by count")
  void aRequestOverTheBatchSizeIsSplitByCount() {
    final List<EmbeddingRequest> pieces = EmbeddingBatcher.split(request(10, 1), SMALL);

    assertThat(pieces).hasSize(3);
    assertThat(pieces.get(0).size()).isEqualTo(4);
    assertThat(pieces.get(2).size()).isEqualTo(2);
  }

  @Test
  @DisplayName("a request over the payload size is split by bytes")
  void aRequestOverThePayloadSizeIsSplitByBytes() {
    // Four inputs of fifteen bytes exceed the forty-byte payload limit before the count limit does.
    final List<EmbeddingRequest> pieces = EmbeddingBatcher.split(request(4, 15), SMALL);

    assertThat(pieces).hasSizeGreaterThan(1);
    for (final EmbeddingRequest piece : pieces) {
      assertThat(piece.size()).isLessThanOrEqualTo(SMALL.maxBatchSize());
    }
  }

  @Test
  @DisplayName("splitting covers every input exactly once and preserves order")
  void splittingCoversEveryInputExactlyOnceAndPreservesOrder() {
    final List<String> texts = new ArrayList<>();
    for (int i = 0; i < 37; i++) {
      texts.add("input-" + i);
    }
    final EmbeddingRequest whole =
        new EmbeddingRequest(ACME, MODEL, texts, EmbeddingRequest.EmbeddingPurpose.WRITE);

    final List<String> rejoined = new ArrayList<>();
    for (final EmbeddingRequest piece : EmbeddingBatcher.split(whole, SMALL)) {
      rejoined.addAll(piece.texts());
    }

    // Losing or reordering an input during splitting would attach a vector to the wrong record, and
    // nothing downstream could detect it.
    assertThat(rejoined).isEqualTo(texts);
  }

  @Test
  @DisplayName("an input larger than the payload limit still gets its own piece")
  void anInputLargerThanThePayloadLimitStillGetsItsOwnPiece() {
    final EmbeddingRequest oversized =
        new EmbeddingRequest(
            ACME, MODEL, List.of("x".repeat(500)), EmbeddingRequest.EmbeddingPurpose.WRITE);

    // Refusing it here would mean two places deciding what is too large. The provider answers.
    assertThat(EmbeddingBatcher.split(oversized, SMALL)).hasSize(1);
  }

  @Test
  @DisplayName("concurrent submissions are gathered into one provider call")
  void concurrentSubmissionsAreGatheredIntoOneProviderCall() throws Exception {
    final AtomicInteger calls = new AtomicInteger();
    final AtomicInteger largest = new AtomicInteger();
    try (EmbeddingBatcher batcher =
        new EmbeddingBatcher(
            32,
            1_000_000,
            Duration.ofMillis(50),
            1_000,
            EmbeddingMetricsPort.NOOP,
            req -> {
              calls.incrementAndGet();
              largest.accumulateAndGet(req.size(), Math::max);
              return succeed(req);
            })) {
      final CountDownLatch start = new CountDownLatch(1);
      final ExecutorService pool = Executors.newFixedThreadPool(16);
      final List<CompletableFuture<EmbeddingResponse.Outcome>> futures = new ArrayList<>();
      for (int i = 0; i < 16; i++) {
        final int index = i;
        pool.submit(
            () -> {
              try {
                start.await();
                synchronized (futures) {
                  futures.add(
                      batcher.submit(
                          ACME, MODEL, "text-" + index, EmbeddingRequest.EmbeddingPurpose.WRITE));
                }
              } catch (final InterruptedException interrupted) {
                Thread.currentThread().interrupt();
              }
            });
      }
      start.countDown();
      pool.shutdown();
      assertThat(pool.awaitTermination(30, TimeUnit.SECONDS)).isTrue();
      for (final CompletableFuture<EmbeddingResponse.Outcome> future : futures) {
        assertThat(future.get(30, TimeUnit.SECONDS).ok()).isTrue();
      }

      // Sixteen concurrent single-text calls must not become sixteen provider calls. This is the
      // entire justification for coalescing behind a synchronous one-text port.
      assertThat(calls.get()).isLessThan(16);
      assertThat(largest.get()).isGreaterThan(1);
    }
  }

  @Test
  @DisplayName("a lone submission is sent once the batch timeout elapses")
  void aLoneSubmissionIsSentOnceTheBatchTimeoutElapses() throws Exception {
    try (EmbeddingBatcher batcher =
        new EmbeddingBatcher(
            32,
            1_000_000,
            Duration.ofMillis(40),
            1_000,
            EmbeddingMetricsPort.NOOP,
            EmbeddingBatcherTest::succeed)) {
      final long started = System.nanoTime();
      final EmbeddingResponse.Outcome outcome =
          batcher
              .submit(ACME, MODEL, "alone", EmbeddingRequest.EmbeddingPurpose.WRITE)
              .get(10, TimeUnit.SECONDS);
      final long elapsedMillis = (System.nanoTime() - started) / 1_000_000;

      // A single-threaded writer pays the timeout and gets a batch of one. That is inherent to a
      // synchronous one-text port and is recorded as B56.
      assertThat(outcome.ok()).isTrue();
      assertThat(elapsedMillis).isGreaterThanOrEqualTo(20L);
    }
  }

  @Test
  @DisplayName("a full batch is sent immediately rather than waiting")
  void aFullBatchIsSentImmediatelyRatherThanWaiting() throws Exception {
    try (EmbeddingBatcher batcher =
        new EmbeddingBatcher(
            2,
            1_000_000,
            Duration.ofSeconds(30),
            1_000,
            EmbeddingMetricsPort.NOOP,
            EmbeddingBatcherTest::succeed)) {
      final CompletableFuture<EmbeddingResponse.Outcome> first =
          batcher.submit(ACME, MODEL, "one", EmbeddingRequest.EmbeddingPurpose.WRITE);
      final CompletableFuture<EmbeddingResponse.Outcome> second =
          batcher.submit(ACME, MODEL, "two", EmbeddingRequest.EmbeddingPurpose.WRITE);

      // The timeout is thirty seconds; if reaching the batch size did not flush, this would hang.
      assertThat(first.get(5, TimeUnit.SECONDS).ok()).isTrue();
      assertThat(second.get(5, TimeUnit.SECONDS).ok()).isTrue();
    }
  }

  @Test
  @DisplayName("a full queue refuses rather than growing without bound")
  void aFullQueueRefusesRatherThanGrowingWithoutBound() throws Exception {
    try (EmbeddingBatcher batcher =
        new EmbeddingBatcher(
            1_000,
            1_000_000,
            Duration.ofSeconds(30),
            4,
            EmbeddingMetricsPort.NOOP,
            EmbeddingBatcherTest::succeed)) {
      final List<EmbeddingResponse.Outcome> outcomes = new ArrayList<>();
      for (int i = 0; i < 12; i++) {
        final CompletableFuture<EmbeddingResponse.Outcome> future =
            batcher.submit(ACME, MODEL, "q" + i, EmbeddingRequest.EmbeddingPurpose.WRITE);
        if (future.isDone()) {
          outcomes.add(future.get());
        }
      }

      // Absorbing unbounded work converts a slow provider into an out-of-memory failure, which is a
      // worse outage than a refused write.
      assertThat(outcomes).isNotEmpty();
      assertThat(outcomes)
          .anySatisfy(
              outcome ->
                  assertThat(((EmbeddingResponse.Outcome.Failed) outcome).reason())
                      .isEqualTo(EmbeddingFailure.UNAVAILABLE));
    }
  }

  @Test
  @DisplayName("shutdown completes every waiting future rather than abandoning it")
  void shutdownCompletesEveryWaitingFutureRatherThanAbandoningIt() throws Exception {
    final EmbeddingBatcher batcher =
        new EmbeddingBatcher(
            1_000,
            1_000_000,
            Duration.ofSeconds(30),
            1_000,
            EmbeddingMetricsPort.NOOP,
            EmbeddingBatcherTest::succeed);
    final CompletableFuture<EmbeddingResponse.Outcome> waiting =
        batcher.submit(ACME, MODEL, "orphan", EmbeddingRequest.EmbeddingPurpose.WRITE);

    batcher.close();

    // A caller blocked on a future that never completes is a hang, and a hang on shutdown is the
    // kind that survives to production.
    assertThat(waiting.get(5, TimeUnit.SECONDS).ok()).isFalse();
  }

  @Test
  @DisplayName("an executor that throws fails only its own batch and leaves the flusher alive")
  void anExecutorThatThrowsFailsOnlyItsOwnBatchAndLeavesTheFlusherAlive() throws Exception {
    final AtomicInteger calls = new AtomicInteger();
    try (EmbeddingBatcher batcher =
        new EmbeddingBatcher(
            1,
            1_000_000,
            Duration.ofMillis(20),
            1_000,
            EmbeddingMetricsPort.NOOP,
            req -> {
              if (calls.incrementAndGet() == 1) {
                throw new IllegalStateException("boom");
              }
              return succeed(req);
            })) {
      final EmbeddingResponse.Outcome failed =
          batcher
              .submit(ACME, MODEL, "a", EmbeddingRequest.EmbeddingPurpose.WRITE)
              .get(5, TimeUnit.SECONDS);
      final EmbeddingResponse.Outcome recovered =
          batcher
              .submit(ACME, MODEL, "b", EmbeddingRequest.EmbeddingPurpose.WRITE)
              .get(5, TimeUnit.SECONDS);

      assertThat(failed.ok()).isFalse();
      assertThat(recovered.ok()).isTrue();
    }
  }

  @Test
  @DisplayName("the queue depth falls back to zero once batches drain")
  void theQueueDepthFallsBackToZeroOnceBatchesDrain() throws Exception {
    try (EmbeddingBatcher batcher =
        new EmbeddingBatcher(
            4,
            1_000_000,
            Duration.ofMillis(20),
            1_000,
            EmbeddingMetricsPort.NOOP,
            EmbeddingBatcherTest::succeed)) {
      final List<CompletableFuture<EmbeddingResponse.Outcome>> futures = new ArrayList<>();
      for (int i = 0; i < 9; i++) {
        futures.add(batcher.submit(ACME, MODEL, "d" + i, EmbeddingRequest.EmbeddingPurpose.WRITE));
      }
      for (final CompletableFuture<EmbeddingResponse.Outcome> future : futures) {
        future.get(10, TimeUnit.SECONDS);
      }

      // A depth that never returns to zero means the counter leaks, and a leaking depth eventually
      // trips back-pressure on an idle system.
      assertThat(batcher.queueDepth()).isZero();
    }
  }

  /**
   * A response embedding every input.
   *
   * @param request the batch
   * @return successful outcomes
   */
  private static EmbeddingResponse succeed(final EmbeddingRequest request) {
    final List<EmbeddingResponse.Outcome> outcomes = new ArrayList<>(request.size());
    for (int i = 0; i < request.size(); i++) {
      outcomes.add(
          new EmbeddingResponse.Outcome.Embedded(
              new CanonicalEmbedding(
                  ProviderId.of("test"),
                  request.modelId(),
                  2,
                  Instant.EPOCH,
                  EmbeddingUsage.FREE,
                  true,
                  Map.of(),
                  new float[] {1.0f, 0.0f})));
    }
    return new EmbeddingResponse(outcomes, EmbeddingUsage.FREE);
  }

  @Test
  @Timeout(60)
  @DisplayName("one tenant's slow provider does not stall another tenant's batch")
  void oneTenantsSlowProviderDoesNotStallAnotherTenantsBatch() throws Exception {
    // The bulkhead regression. The flusher notices timeouts; it must not also *perform* the
    // provider
    // call, because then a single slow tenant serialises every other tenant behind its network
    // wait.
    // Measured before the dispatcher existed: tenant B waited 5,887 ms for a 50 ms batch window.
    final java.util.concurrent.CountDownLatch released = new java.util.concurrent.CountDownLatch(1);
    final java.util.concurrent.atomic.AtomicBoolean slowSeen =
        new java.util.concurrent.atomic.AtomicBoolean();

    try (EmbeddingBatcher batcher =
        new EmbeddingBatcher(
            64,
            1_000_000,
            Duration.ofMillis(30),
            1_000,
            EmbeddingMetricsPort.NOOP,
            request -> {
              if (request.tenant().org().equals("acme")) {
                slowSeen.set(true);
                try {
                  released.await(20, TimeUnit.SECONDS);
                } catch (final InterruptedException interrupted) {
                  Thread.currentThread().interrupt();
                }
              }
              return succeed(request);
            })) {

      batcher.submit(
          EmbeddingFixtures.ACME, MODEL, "slow", EmbeddingRequest.EmbeddingPurpose.WRITE);
      // Wait until the slow tenant's call is genuinely in flight, so the test measures blocking
      // rather than scheduling luck.
      for (int i = 0; i < 200 && !slowSeen.get(); i++) {
        Thread.sleep(10);
      }
      assertThat(slowSeen).isTrue();

      final long began = System.nanoTime();
      final EmbeddingResponse.Outcome other =
          batcher
              .submit(
                  EmbeddingFixtures.GLOBEX, MODEL, "fast", EmbeddingRequest.EmbeddingPurpose.WRITE)
              .get(20, TimeUnit.SECONDS);
      final long waitedMillis = (System.nanoTime() - began) / 1_000_000L;
      released.countDown();

      assertThat(other.ok()).isTrue();
      assertThat(waitedMillis)
          .as("the unrelated tenant must not wait behind another tenant's provider call")
          .isLessThan(3_000L);
    }
  }
}

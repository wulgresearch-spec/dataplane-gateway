package io.reliabilityai.gateway.dataplane.embedding;

import io.reliabilityai.gateway.canonical.identity.TenantScope;
import io.reliabilityai.gateway.dataplane.embedding.api.CanonicalEmbedding;
import io.reliabilityai.gateway.dataplane.embedding.api.EmbeddingCapability;
import io.reliabilityai.gateway.dataplane.embedding.api.EmbeddingFailure;
import io.reliabilityai.gateway.dataplane.embedding.api.EmbeddingHealth;
import io.reliabilityai.gateway.dataplane.embedding.api.EmbeddingModel;
import io.reliabilityai.gateway.dataplane.embedding.api.EmbeddingProviderPort;
import io.reliabilityai.gateway.dataplane.embedding.api.EmbeddingRequest;
import io.reliabilityai.gateway.dataplane.embedding.api.EmbeddingResponse;
import io.reliabilityai.gateway.dataplane.embedding.api.EmbeddingTransportException;
import io.reliabilityai.gateway.dataplane.embedding.api.EmbeddingUsage;
import io.reliabilityai.gateway.dataplane.embedding.application.EmbeddingCache;
import io.reliabilityai.gateway.dataplane.provider.api.ProviderId;
import io.reliabilityai.gateway.ports.ClockPort;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/** Shared doubles for the embedding tests. */
final class EmbeddingFixtures {

  static final TenantScope ACME = TenantScope.of("acme", "core");
  static final TenantScope GLOBEX = TenantScope.of("globex", "core");
  static final Instant T0 = Instant.parse("2026-08-07T00:00:00Z");
  static final String MODEL = "text-default-1536";
  static final int DIMENSION = 8;

  /** The model definition the scripted providers serve. */
  static final EmbeddingModel MODEL_DEF = new EmbeddingModel(MODEL, DIMENSION, 8191, 20_000L);

  /** The provider identity most tests key against. */
  static final ProviderId PROVIDER = ProviderId.of("p");

  private EmbeddingFixtures() {}

  /**
   * The cache key for the default model and provider.
   *
   * @param tenant whose input it is
   * @param text the input
   * @return the cache key
   */
  static String key(final TenantScope tenant, final String text) {
    return EmbeddingCache.keyFor(tenant, MODEL_DEF, PROVIDER, text);
  }

  /**
   * A model definition with a chosen id, otherwise identical to the default.
   *
   * @param id the neutral model id
   * @return the model
   */
  static EmbeddingModel modelNamed(final String id) {
    return new EmbeddingModel(id, DIMENSION, 8191, 20_000L);
  }

  /** A clock the test moves by hand. */
  static final class TestClock implements ClockPort {

    private final AtomicReference<Instant> now = new AtomicReference<>(T0);

    @Override
    public Instant now() {
      return now.get();
    }

    void advance(final Duration by) {
      now.updateAndGet(instant -> instant.plus(by));
    }
  }

  /**
   * A provider whose behaviour a test scripts.
   *
   * <p>Counts attempts, so retry tests assert on calls rather than on timing.
   */
  static final class ScriptedProvider implements EmbeddingProviderPort {

    private final ProviderId id;
    private final EmbeddingCapability capability;
    private final ClockPort clock;
    private final Deque<Behaviour> script = new ArrayDeque<>();
    private final AtomicInteger calls = new AtomicInteger();
    private final AtomicInteger largestBatch = new AtomicInteger();
    private volatile int producedDimension;

    /** What one call should do. */
    interface Behaviour {}

    /** Succeed normally. */
    record Succeed() implements Behaviour {}

    /**
     * Throw a transport failure.
     *
     * @param reason the neutral failure kind
     */
    record Fail(EmbeddingFailure reason) implements Behaviour {}

    /**
     * Succeed, but return the wrong vector width.
     *
     * @param dimension what to return instead
     */
    record WrongDimension(int dimension) implements Behaviour {}

    /**
     * Return per-input failures for the given positions.
     *
     * @param failedIndexes which inputs fail
     */
    record PartialFailure(Set<Integer> failedIndexes) implements Behaviour {}

    ScriptedProvider(final String name, final ClockPort clock, final int maxBatchSize) {
      this.id = ProviderId.of(name);
      this.clock = clock;
      this.producedDimension = DIMENSION;
      this.capability =
          new EmbeddingCapability(
              // Priced like a real embedding model — 20_000 micros per million tokens is $0.02 per
              // million. A token price low enough to round to nothing would make every cost
              // assertion below vacuously true.
              Set.of(new EmbeddingModel(MODEL, DIMENSION, 8191, 20_000L)),
              maxBatchSize,
              4096,
              false,
              false);
    }

    ScriptedProvider then(final Behaviour behaviour) {
      script.add(behaviour);
      return this;
    }

    int calls() {
      return calls.get();
    }

    int largestBatch() {
      return largestBatch.get();
    }

    @Override
    public ProviderId id() {
      return id;
    }

    @Override
    public EmbeddingCapability capability() {
      return capability;
    }

    @Override
    public EmbeddingHealth health() {
      return EmbeddingHealth.healthy(clock.now());
    }

    @Override
    public EmbeddingResponse embed(final EmbeddingRequest request) {
      calls.incrementAndGet();
      largestBatch.accumulateAndGet(request.size(), Math::max);
      final Behaviour behaviour = script.isEmpty() ? new Succeed() : script.poll();
      if (behaviour instanceof Fail fail) {
        throw new EmbeddingTransportException(fail.reason(), "scripted " + fail.reason());
      }
      final int width =
          behaviour instanceof WrongDimension wrong ? wrong.dimension() : producedDimension;
      final Set<Integer> failed =
          behaviour instanceof PartialFailure partial ? partial.failedIndexes() : Set.of();
      final List<EmbeddingResponse.Outcome> outcomes = new ArrayList<>(request.size());
      for (int i = 0; i < request.size(); i++) {
        if (failed.contains(i)) {
          outcomes.add(
              new EmbeddingResponse.Outcome.Failed(EmbeddingFailure.TOO_LARGE, "scripted refusal"));
          continue;
        }
        outcomes.add(
            new EmbeddingResponse.Outcome.Embedded(
                new CanonicalEmbedding(
                    id,
                    request.modelId(),
                    width,
                    clock.now(),
                    EmbeddingUsage.FREE,
                    false,
                    Map.of(),
                    unnormalized(request.texts().get(i), width))));
      }
      return new EmbeddingResponse(outcomes, new EmbeddingUsage(request.size() * 4L, 1, 7L));
    }

    /**
     * A deterministic, deliberately non-unit vector, so normalization is observable.
     *
     * @param text the input
     * @param width the vector width
     * @return the components
     */
    private static float[] unnormalized(final String text, final int width) {
      final float[] vector = new float[width];
      for (int i = 0; i < width; i++) {
        vector[i] = 2.0f + ((text.hashCode() >>> (i % 16)) & 0x0f);
      }
      return vector;
    }
  }

  /**
   * A provider safe to call from many threads at once.
   *
   * <p>{@link ScriptedProvider} is not: its script is an {@code ArrayDeque}, and a test that
   * hammered it from a hundred threads would be measuring a data race in the fixture rather than
   * anything about the pipeline. This one holds no mutable state beyond counters, derives every
   * vector from the text alone, and can be told to take its time so that concurrent callers
   * genuinely overlap.
   */
  static final class ConcurrentProvider implements EmbeddingProviderPort {

    private final ProviderId id;
    private final EmbeddingCapability capability;
    private final ClockPort clock;
    private final long latencyMillis;
    private final AtomicInteger calls = new AtomicInteger();
    private final AtomicInteger inputs = new AtomicInteger();
    private final AtomicInteger concurrentNow = new AtomicInteger();
    private final AtomicInteger peakConcurrent = new AtomicInteger();

    ConcurrentProvider(final String name, final ClockPort clock, final int maxBatchSize) {
      this(name, clock, maxBatchSize, 0L);
    }

    ConcurrentProvider(
        final String name,
        final ClockPort clock,
        final int maxBatchSize,
        final long latencyMillis) {
      this.id = ProviderId.of(name);
      this.clock = clock;
      this.latencyMillis = latencyMillis;
      this.capability =
          new EmbeddingCapability(
              Set.of(new EmbeddingModel(MODEL, DIMENSION, 8191, 20_000L)),
              maxBatchSize,
              1_000_000,
              false,
              false);
    }

    int calls() {
      return calls.get();
    }

    int inputs() {
      return inputs.get();
    }

    int peakConcurrent() {
      return peakConcurrent.get();
    }

    @Override
    public ProviderId id() {
      return id;
    }

    @Override
    public EmbeddingCapability capability() {
      return capability;
    }

    @Override
    public EmbeddingHealth health() {
      return EmbeddingHealth.healthy(clock.now());
    }

    @Override
    public EmbeddingResponse embed(final EmbeddingRequest request) {
      calls.incrementAndGet();
      inputs.addAndGet(request.size());
      peakConcurrent.accumulateAndGet(concurrentNow.incrementAndGet(), Math::max);
      try {
        if (latencyMillis > 0L) {
          Thread.sleep(latencyMillis);
        }
      } catch (final InterruptedException interrupted) {
        Thread.currentThread().interrupt();
      } finally {
        concurrentNow.decrementAndGet();
      }
      final List<EmbeddingResponse.Outcome> outcomes = new ArrayList<>(request.size());
      for (final String text : request.texts()) {
        outcomes.add(
            new EmbeddingResponse.Outcome.Embedded(
                new CanonicalEmbedding(
                    id,
                    request.modelId(),
                    DIMENSION,
                    clock.now(),
                    EmbeddingUsage.FREE,
                    false,
                    Map.of(),
                    deterministic(text))));
      }
      return new EmbeddingResponse(outcomes, new EmbeddingUsage(request.size() * 4L, 1, 7L));
    }

    /**
     * A vector determined entirely by the text, and deliberately not unit length.
     *
     * <p>Both properties are load-bearing. Determined by the text alone, so two threads embedding
     * the same string must agree; not unit length, so a test can tell whether the pipeline
     * normalized it.
     *
     * @param text the input
     * @return the components
     */
    static float[] deterministic(final String text) {
      final float[] vector = new float[DIMENSION];
      final int seed = text.hashCode();
      for (int i = 0; i < DIMENSION; i++) {
        vector[i] = 3.0f + (((seed >>> (i % 24)) & 0x1f) / 4.0f);
      }
      return vector;
    }
  }

  /**
   * A vector of the given width with every component set.
   *
   * @param width the width
   * @param value the component value
   * @return the vector
   */
  static float[] flat(final int width, final float value) {
    final float[] vector = new float[width];
    java.util.Arrays.fill(vector, value);
    return vector;
  }
}

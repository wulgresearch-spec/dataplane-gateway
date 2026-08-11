package io.reliabilityai.gateway.dataplane.embedding;

import static io.reliabilityai.gateway.dataplane.embedding.EmbeddingFixtures.ACME;
import static io.reliabilityai.gateway.dataplane.embedding.EmbeddingFixtures.DIMENSION;
import static io.reliabilityai.gateway.dataplane.embedding.EmbeddingFixtures.MODEL;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.reliabilityai.gateway.dataplane.embedding.api.EmbeddingAuditPort;
import io.reliabilityai.gateway.dataplane.embedding.api.EmbeddingFailure;
import io.reliabilityai.gateway.dataplane.embedding.api.EmbeddingGovernancePort;
import io.reliabilityai.gateway.dataplane.embedding.application.EmbeddingBatcher;
import io.reliabilityai.gateway.dataplane.embedding.application.EmbeddingCache;
import io.reliabilityai.gateway.dataplane.embedding.application.EmbeddingPipeline;
import io.reliabilityai.gateway.dataplane.embedding.application.EmbeddingProviderRegistry;
import io.reliabilityai.gateway.dataplane.embedding.domain.EmbeddingFailurePolicy;
import io.reliabilityai.gateway.dataplane.embedding.domain.EmbeddingNormalizer;
import io.reliabilityai.gateway.dataplane.embedding.domain.EmbeddingRetryPolicy;
import io.reliabilityai.gateway.dataplane.embedding.internal.InProcessEmbeddingMetrics;
import io.reliabilityai.gateway.dataplane.embedding.internal.PipelineEmbeddingPort;
import io.reliabilityai.gateway.dataplane.memory.api.EmbeddingPort;
import io.reliabilityai.gateway.dataplane.memory.api.MemoryStoreUnavailableException;
import java.lang.reflect.Method;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * The seam onto the Memory Runtime, and the proof that the Memory Runtime learned nothing from it.
 *
 * <p>The whole milestone is judged here. C17 calls {@code float[] embed(String)} and must continue
 * to know no provider, no model, no dimension and no tenant — MEM-1 and MEM-2 — while everything
 * behind that signature gains batching, caching, retry, budget and audit. If this bridge worked by
 * widening {@code EmbeddingPort}, the milestone would have failed its central constraint no matter
 * how good the pipeline was.
 *
 * <p>So this file asserts two different kinds of thing: that the bridge behaves, and that the port
 * it implements is still as narrow as it was before B25 started.
 */
@DisplayName("memory runtime bridge")
final class MemoryRuntimeBridgeTest {

  private EmbeddingFixtures.TestClock clock;
  private InProcessEmbeddingMetrics metrics;
  private EmbeddingProviderRegistry registry;
  private EmbeddingCache cache;

  @BeforeEach
  void setUp() {
    clock = new EmbeddingFixtures.TestClock();
    metrics = new InProcessEmbeddingMetrics();
    cache = new EmbeddingCache(clock, metrics, Duration.ofHours(1), Duration.ofMinutes(10), 1_000);
    registry = new EmbeddingProviderRegistry(EmbeddingFailurePolicy.defaults(), clock);
  }

  private EmbeddingPipeline pipelineOver(final EmbeddingGovernancePort governance) {
    registry.register(new EmbeddingFixtures.ConcurrentProvider("p", clock, 32));
    return new EmbeddingPipeline(
        registry,
        cache,
        governance,
        EmbeddingRetryPolicy.immediate(2),
        metrics,
        EmbeddingAuditPort.NOOP);
  }

  private EmbeddingBatcher batcherOver(final EmbeddingPipeline pipeline) {
    return new EmbeddingBatcher(
        32, 1_000_000, Duration.ofMillis(20), 100, metrics, pipeline::embed);
  }

  @Test
  @Timeout(30)
  @DisplayName("the port returns a vector of the model's width, normalized")
  void thePortReturnsAVectorOfTheModelsWidthNormalized() {
    try (EmbeddingBatcher batcher = batcherOver(pipelineOver(EmbeddingGovernancePort.PERMISSIVE))) {
      final EmbeddingPort port = new PipelineEmbeddingPort(batcher, ACME, MODEL, 5_000L);

      final float[] vector = port.embed("hello");

      assertThat(vector).hasSize(DIMENSION);
      assertThat(EmbeddingNormalizer.isUnitLength(vector)).isTrue();
    }
  }

  @Test
  @Timeout(30)
  @DisplayName("the same text through the port yields the same vector")
  void theSameTextThroughThePortYieldsTheSameVector() {
    try (EmbeddingBatcher batcher = batcherOver(pipelineOver(EmbeddingGovernancePort.PERMISSIVE))) {
      final EmbeddingPort port = new PipelineEmbeddingPort(batcher, ACME, MODEL, 5_000L);

      assertThat(port.embed("stable")).containsExactly(port.embed("stable"));
    }
  }

  @Test
  @Timeout(30)
  @DisplayName("the port hands out a copy the caller cannot use to poison the cache")
  void thePortHandsOutACopyTheCallerCannotUseToPoisonTheCache() {
    try (EmbeddingBatcher batcher = batcherOver(pipelineOver(EmbeddingGovernancePort.PERMISSIVE))) {
      final EmbeddingPort port = new PipelineEmbeddingPort(batcher, ACME, MODEL, 5_000L);

      final float[] first = port.embed("mutable");
      first[0] = 1_000.0f;

      // C17 treats the array as opaque and is entitled to do what it likes with it. That must not
      // reach the cached copy, or every later hit returns the corruption.
      assertThat(port.embed("mutable")[0]).isNotEqualTo(1_000.0f);
    }
  }

  @Test
  @Timeout(30)
  @DisplayName("a refusal becomes the unavailability C17 already knows how to handle")
  void aRefusalBecomesTheUnavailabilityC17AlreadyKnowsHowToHandle() {
    try (EmbeddingBatcher batcher =
        batcherOver(
            pipelineOver(
                (tenant, model, inputs, tokens, cost) -> EmbeddingGovernancePort.Decision.DENY))) {
      final EmbeddingPort port = new PipelineEmbeddingPort(batcher, ACME, MODEL, 5_000L);

      // The port returns float[], so there is nowhere to put a neutral failure reason. Every
      // refusal
      // collapses to this one exception, which C17 already handles by refusing the write. The
      // reason
      // is not lost — metrics and audit recorded it before this point — but it is not visible here.
      assertThatThrownBy(() -> port.embed("denied"))
          .isInstanceOf(MemoryStoreUnavailableException.class);
      assertThat(metrics.count("failed." + EmbeddingFailure.BUDGET_EXCEEDED.name())).isEqualTo(1L);
    }
  }

  @Test
  @Timeout(30)
  @DisplayName("a shut-down batcher refuses rather than hanging the caller")
  void aShutDownBatcherRefusesRatherThanHangingTheCaller() {
    final EmbeddingBatcher batcher = batcherOver(pipelineOver(EmbeddingGovernancePort.PERMISSIVE));
    final EmbeddingPort port = new PipelineEmbeddingPort(batcher, ACME, MODEL, 5_000L);
    batcher.close();

    // A caller blocked for ever on a future nobody will complete is the shutdown bug that survives
    // into production, because it only appears when something else is already going wrong.
    assertThatThrownBy(() -> port.embed("after close"))
        .isInstanceOf(MemoryStoreUnavailableException.class);
  }

  @Test
  @Timeout(30)
  @DisplayName("a caller that runs out of wait budget is refused, not left blocked")
  void aCallerThatRunsOutOfWaitBudgetIsRefusedNotLeftBlocked() {
    registry.register(new EmbeddingFixtures.ConcurrentProvider("slow", clock, 32, 2_000L));
    final EmbeddingPipeline pipeline =
        new EmbeddingPipeline(
            registry,
            cache,
            EmbeddingGovernancePort.PERMISSIVE,
            EmbeddingRetryPolicy.immediate(1),
            metrics,
            EmbeddingAuditPort.NOOP);

    try (EmbeddingBatcher batcher =
        new EmbeddingBatcher(32, 1_000_000, Duration.ofMillis(10), 100, metrics, pipeline::embed)) {
      final EmbeddingPort port = new PipelineEmbeddingPort(batcher, ACME, MODEL, 100L);

      assertThatThrownBy(() -> port.embed("slow"))
          .isInstanceOf(MemoryStoreUnavailableException.class)
          .hasMessageContaining("wait budget");
    }
  }

  @Test
  @Timeout(30)
  @DisplayName("two tenants are served by two instances, which is the tenant limitation B55")
  void twoTenantsAreServedByTwoInstances() {
    try (EmbeddingBatcher batcher = batcherOver(pipelineOver(EmbeddingGovernancePort.PERMISSIVE))) {
      final EmbeddingPort acme = new PipelineEmbeddingPort(batcher, ACME, MODEL, 5_000L);
      final EmbeddingPort globex =
          new PipelineEmbeddingPort(batcher, EmbeddingFixtures.GLOBEX, MODEL, 5_000L);

      // The vectors agree because embedding does not depend on the tenant; the cache entries do
      // not,
      // because isolation does. What this test really records is the shape of the limitation: the
      // tenant is fixed at construction, so a deployment serving many tenants needs one bridge per
      // tenant, and a single MemoryRuntime is not wired for that today. B55.
      assertThat(acme.embed("shared")).containsExactly(globex.embed("shared"));
      assertThat(cache.size()).isEqualTo(2);
    }
  }

  @Test
  @DisplayName("the memory-facing port still names no provider, model, dimension or tenant")
  void theMemoryFacingPortStillNamesNoProviderModelDimensionOrTenant() {
    final Method[] methods = EmbeddingPort.class.getDeclaredMethods();

    // The structural check behind MEM-1 and MEM-2. B25 was allowed to build anything it liked
    // *behind* this signature and nothing at all *in* it; a milestone that had quietly added a
    // model id or a tenant here would have moved provider knowledge into the Memory Runtime, which
    // is the one thing the mission forbids. Asserted against the compiled interface rather than
    // against the source, because that is what C17 actually links against.
    assertThat(methods).hasSize(1);
    assertThat(methods[0].getName()).isEqualTo("embed");
    assertThat(methods[0].getReturnType()).isEqualTo(float[].class);
    assertThat(methods[0].getParameterTypes()).containsExactly(String.class);

    final String surface =
        (EmbeddingPort.class.getName()
                + methods[0].toGenericString()
                + List.of(EmbeddingPort.class.getInterfaces()))
            .toLowerCase(Locale.ROOT);
    assertThat(surface)
        .doesNotContain("openai")
        .doesNotContain("provider")
        .doesNotContain("tenant")
        .doesNotContain("model")
        .doesNotContain("dimension");
  }

  @Test
  @Timeout(30)
  @DisplayName("the bridge is the only thing C17 needs, and it needs no embedding type to use it")
  void theBridgeIsTheOnlyThingC17NeedsAndItNeedsNoEmbeddingTypeToUseIt() {
    try (EmbeddingBatcher batcher = batcherOver(pipelineOver(EmbeddingGovernancePort.PERMISSIVE))) {
      // Declared as the Memory Runtime's own port type, constructed once, then used through a
      // signature that mentions nothing from this module. That is the whole integration contract.
      final EmbeddingPort port = new PipelineEmbeddingPort(batcher, ACME, MODEL, 5_000L);

      // The whole of what C17 does with the bridge: call one method, receive a vector of the
      // model's width. Nothing from this module appears in that signature.
      assertThat(port.embed("only this")).hasSize(DIMENSION);
    }
  }
}

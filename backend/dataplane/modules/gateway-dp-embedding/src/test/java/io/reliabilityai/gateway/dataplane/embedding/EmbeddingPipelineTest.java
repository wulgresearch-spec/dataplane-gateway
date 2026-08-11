package io.reliabilityai.gateway.dataplane.embedding;

import static io.reliabilityai.gateway.dataplane.embedding.EmbeddingFixtures.ACME;
import static io.reliabilityai.gateway.dataplane.embedding.EmbeddingFixtures.DIMENSION;
import static io.reliabilityai.gateway.dataplane.embedding.EmbeddingFixtures.GLOBEX;
import static io.reliabilityai.gateway.dataplane.embedding.EmbeddingFixtures.MODEL;
import static org.assertj.core.api.Assertions.assertThat;

import io.reliabilityai.gateway.dataplane.embedding.api.EmbeddingFailure;
import io.reliabilityai.gateway.dataplane.embedding.api.EmbeddingGovernancePort;
import io.reliabilityai.gateway.dataplane.embedding.api.EmbeddingRequest;
import io.reliabilityai.gateway.dataplane.embedding.api.EmbeddingResponse;
import io.reliabilityai.gateway.dataplane.embedding.application.EmbeddingCache;
import io.reliabilityai.gateway.dataplane.embedding.application.EmbeddingPipeline;
import io.reliabilityai.gateway.dataplane.embedding.application.EmbeddingProviderRegistry;
import io.reliabilityai.gateway.dataplane.embedding.domain.EmbeddingFailurePolicy;
import io.reliabilityai.gateway.dataplane.embedding.domain.EmbeddingNormalizer;
import io.reliabilityai.gateway.dataplane.embedding.domain.EmbeddingRetryPolicy;
import io.reliabilityai.gateway.dataplane.embedding.internal.InProcessEmbeddingMetrics;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Cache, budget, split, retry, normalize — the pipeline stages and the order they run in. */
@DisplayName("embedding pipeline")
final class EmbeddingPipelineTest {

  private EmbeddingFixtures.TestClock clock;
  private InProcessEmbeddingMetrics metrics;
  private EmbeddingCache cache;
  private EmbeddingProviderRegistry registry;

  @BeforeEach
  void setUp() {
    clock = new EmbeddingFixtures.TestClock();
    metrics = new InProcessEmbeddingMetrics();
    cache = new EmbeddingCache(clock, metrics, Duration.ofHours(1), Duration.ofMinutes(10), 1_000);
    registry = new EmbeddingProviderRegistry(EmbeddingFailurePolicy.defaults(), clock);
  }

  private EmbeddingPipeline pipelineOver(
      final EmbeddingFixtures.ScriptedProvider provider, final EmbeddingGovernancePort governance) {
    registry.register(provider);
    return new EmbeddingPipeline(
        registry,
        cache,
        governance,
        EmbeddingRetryPolicy.immediate(3),
        metrics,
        io.reliabilityai.gateway.dataplane.embedding.api.EmbeddingAuditPort.NOOP);
  }

  private static EmbeddingRequest request(final String... texts) {
    return new EmbeddingRequest(
        ACME, MODEL, List.of(texts), EmbeddingRequest.EmbeddingPurpose.WRITE);
  }

  @Test
  @DisplayName("a request returns one outcome per input, in order")
  void aRequestReturnsOneOutcomePerInputInOrder() {
    final EmbeddingPipeline pipeline =
        pipelineOver(
            new EmbeddingFixtures.ScriptedProvider("p", clock, 64),
            EmbeddingGovernancePort.PERMISSIVE);

    final EmbeddingResponse response = pipeline.embed(request("alpha", "beta", "gamma"));

    assertThat(response.outcomes()).hasSize(3);
    assertThat(response.complete()).isTrue();
    assertThat(response.at(0)).isPresent();
    assertThat(response.at(0).get().vector()).isNotEqualTo(response.at(1).get().vector());
  }

  @Test
  @DisplayName("every returned vector is normalized and of the declared width")
  void everyReturnedVectorIsNormalizedAndOfTheDeclaredWidth() {
    final EmbeddingPipeline pipeline =
        pipelineOver(
            new EmbeddingFixtures.ScriptedProvider("p", clock, 64),
            EmbeddingGovernancePort.PERMISSIVE);

    final EmbeddingResponse response = pipeline.embed(request("alpha"));

    // The scripted provider deliberately returns vectors that are not unit length, so this asserts
    // the normalizer actually ran rather than that the provider happened to be well behaved.
    final var embedding = response.at(0).orElseThrow();
    assertThat(embedding.dimension()).isEqualTo(DIMENSION);
    assertThat(embedding.normalized()).isTrue();
    assertThat(EmbeddingNormalizer.isUnitLength(embedding.vector())).isTrue();
  }

  @Test
  @DisplayName("a second identical request is served from cache without calling the provider")
  void aSecondIdenticalRequestIsServedFromCacheWithoutCallingTheProvider() {
    final EmbeddingFixtures.ScriptedProvider provider =
        new EmbeddingFixtures.ScriptedProvider("p", clock, 64);
    final EmbeddingPipeline pipeline = pipelineOver(provider, EmbeddingGovernancePort.PERMISSIVE);

    final var first = pipeline.embed(request("repeated")).at(0).orElseThrow();
    final var second = pipeline.embed(request("repeated")).at(0).orElseThrow();

    assertThat(provider.calls()).isEqualTo(1);
    assertThat(second.vector()).isEqualTo(first.vector());
    assertThat(metrics.cacheHitRatio()).isEqualTo(0.5);
  }

  @Test
  @DisplayName("a cached request costs nothing")
  void aCachedRequestCostsNothing() {
    final EmbeddingPipeline pipeline =
        pipelineOver(
            new EmbeddingFixtures.ScriptedProvider("p", clock, 64),
            EmbeddingGovernancePort.PERMISSIVE);
    pipeline.embed(request("repeated"));

    assertThat(pipeline.embed(request("repeated")).usage().costMicros()).isZero();
  }

  @Test
  @DisplayName("a partly cached request only sends the misses")
  void aPartlyCachedRequestOnlySendsTheMisses() {
    final EmbeddingFixtures.ScriptedProvider provider =
        new EmbeddingFixtures.ScriptedProvider("p", clock, 64);
    final EmbeddingPipeline pipeline = pipelineOver(provider, EmbeddingGovernancePort.PERMISSIVE);
    pipeline.embed(request("one", "two"));

    final EmbeddingResponse response = pipeline.embed(request("one", "two", "three", "four"));

    assertThat(response.complete()).isTrue();
    assertThat(provider.largestBatch()).isEqualTo(2);
    assertThat(provider.calls()).isEqualTo(2);
  }

  @Test
  @DisplayName("one tenant never sees another cached vector")
  void oneTenantNeverSeesAnotherCachedVector() {
    final EmbeddingFixtures.ScriptedProvider provider =
        new EmbeddingFixtures.ScriptedProvider("p", clock, 64);
    final EmbeddingPipeline pipeline = pipelineOver(provider, EmbeddingGovernancePort.PERMISSIVE);

    pipeline.embed(
        new EmbeddingRequest(
            ACME, MODEL, List.of("shared"), EmbeddingRequest.EmbeddingPurpose.WRITE));
    pipeline.embed(
        new EmbeddingRequest(
            GLOBEX, MODEL, List.of("shared"), EmbeddingRequest.EmbeddingPurpose.WRITE));

    // The vector would be identical either way; the point is that the second tenant paid for it.
    // A shared cache is an oracle telling one tenant what another has embedded.
    assertThat(provider.calls()).isEqualTo(2);
  }

  @Test
  @DisplayName("governance refuses the spend before any provider call is made")
  void governanceRefusesTheSpendBeforeAnyProviderCallIsMade() {
    final EmbeddingFixtures.ScriptedProvider provider =
        new EmbeddingFixtures.ScriptedProvider("p", clock, 64);
    final EmbeddingPipeline pipeline =
        pipelineOver(
            provider,
            (tenant, model, inputs, tokens, cost) -> EmbeddingGovernancePort.Decision.DENY);

    final EmbeddingResponse response = pipeline.embed(request("expensive"));

    assertThat(provider.calls()).isZero();
    assertThat(response.failures())
        .singleElement()
        .satisfies(
            failed -> assertThat(failed.reason()).isEqualTo(EmbeddingFailure.BUDGET_EXCEEDED));
  }

  @Test
  @DisplayName("governance sees an estimate that grows with the input")
  void governanceSeesAnEstimateThatGrowsWithTheInput() {
    final List<Long> seenTokens = new ArrayList<>();
    final List<Long> seenCost = new ArrayList<>();
    final EmbeddingPipeline pipeline =
        pipelineOver(
            new EmbeddingFixtures.ScriptedProvider("p", clock, 64),
            (tenant, model, inputs, tokens, cost) -> {
              seenTokens.add(tokens);
              seenCost.add(cost);
              return EmbeddingGovernancePort.Decision.ALLOW;
            });

    pipeline.embed(request("x".repeat(4)));
    pipeline.embed(request("y".repeat(4000)));

    assertThat(seenTokens.get(1)).isGreaterThan(seenTokens.get(0));
    assertThat(seenCost.get(1)).isGreaterThan(seenCost.get(0));
  }

  @Test
  @DisplayName("governance is not consulted for a request the cache can serve")
  void governanceIsNotConsultedForARequestTheCacheCanServe() {
    final AtomicInteger consultations = new AtomicInteger();
    final EmbeddingPipeline pipeline =
        pipelineOver(
            new EmbeddingFixtures.ScriptedProvider("p", clock, 64),
            (tenant, model, inputs, tokens, cost) -> {
              consultations.incrementAndGet();
              return EmbeddingGovernancePort.Decision.ALLOW;
            });
    pipeline.embed(request("cached"));

    pipeline.embed(request("cached"));

    // Refusing spend that was never going to happen would make a budget look exhausted while no
    // money was being spent at all.
    assertThat(consultations).hasValue(1);
  }

  @Test
  @DisplayName("a transient failure is retried and then succeeds")
  void aTransientFailureIsRetriedAndThenSucceeds() {
    final EmbeddingFixtures.ScriptedProvider provider =
        new EmbeddingFixtures.ScriptedProvider("p", clock, 64)
            .then(new EmbeddingFixtures.ScriptedProvider.Fail(EmbeddingFailure.RATE_LIMITED))
            .then(new EmbeddingFixtures.ScriptedProvider.Succeed());
    final EmbeddingPipeline pipeline = pipelineOver(provider, EmbeddingGovernancePort.PERMISSIVE);

    assertThat(pipeline.embed(request("retried")).complete()).isTrue();
    assertThat(provider.calls()).isEqualTo(2);
    assertThat(metrics.count("retries.RATE_LIMITED")).isEqualTo(1);
  }

  @Test
  @DisplayName("a permanent failure is not retried")
  void aPermanentFailureIsNotRetried() {
    final EmbeddingFixtures.ScriptedProvider provider =
        new EmbeddingFixtures.ScriptedProvider("p", clock, 64)
            .then(new EmbeddingFixtures.ScriptedProvider.Fail(EmbeddingFailure.AUTH_FAILED));
    final EmbeddingPipeline pipeline = pipelineOver(provider, EmbeddingGovernancePort.PERMISSIVE);

    final EmbeddingResponse response = pipeline.embed(request("rejected"));

    // Repeating a rejected credential is an authentication attack against your own provider.
    assertThat(provider.calls()).isEqualTo(1);
    assertThat(response.failures())
        .singleElement()
        .satisfies(f -> assertThat(f.reason()).isEqualTo(EmbeddingFailure.AUTH_FAILED));
  }

  @Test
  @DisplayName("retries stop at the policy limit")
  void retriesStopAtThePolicyLimit() {
    final EmbeddingFixtures.ScriptedProvider provider =
        new EmbeddingFixtures.ScriptedProvider("p", clock, 64)
            .then(new EmbeddingFixtures.ScriptedProvider.Fail(EmbeddingFailure.UNAVAILABLE))
            .then(new EmbeddingFixtures.ScriptedProvider.Fail(EmbeddingFailure.UNAVAILABLE))
            .then(new EmbeddingFixtures.ScriptedProvider.Fail(EmbeddingFailure.UNAVAILABLE))
            .then(new EmbeddingFixtures.ScriptedProvider.Fail(EmbeddingFailure.UNAVAILABLE));
    final EmbeddingPipeline pipeline = pipelineOver(provider, EmbeddingGovernancePort.PERMISSIVE);

    final EmbeddingResponse response = pipeline.embed(request("doomed"));

    assertThat(provider.calls()).isEqualTo(3);
    assertThat(response.failures())
        .singleElement()
        .satisfies(f -> assertThat(f.reason()).isEqualTo(EmbeddingFailure.UNAVAILABLE));
  }

  @Test
  @DisplayName("a timeout is retried like any other transient failure")
  void aTimeoutIsRetriedLikeAnyOtherTransientFailure() {
    final EmbeddingFixtures.ScriptedProvider provider =
        new EmbeddingFixtures.ScriptedProvider("p", clock, 64)
            .then(new EmbeddingFixtures.ScriptedProvider.Fail(EmbeddingFailure.TIMEOUT))
            .then(new EmbeddingFixtures.ScriptedProvider.Succeed());
    final EmbeddingPipeline pipeline = pipelineOver(provider, EmbeddingGovernancePort.PERMISSIVE);

    assertThat(pipeline.embed(request("slow")).complete()).isTrue();
    assertThat(provider.calls()).isEqualTo(2);
  }

  @Test
  @DisplayName("repeated failures move the provider out of health")
  void repeatedFailuresMoveTheProviderOutOfHealth() {
    final EmbeddingFixtures.ScriptedProvider provider =
        new EmbeddingFixtures.ScriptedProvider("p", clock, 64);
    for (int i = 0; i < 12; i++) {
      provider.then(new EmbeddingFixtures.ScriptedProvider.Fail(EmbeddingFailure.UNAVAILABLE));
    }
    final EmbeddingPipeline pipeline = pipelineOver(provider, EmbeddingGovernancePort.PERMISSIVE);

    pipeline.embed(request("a"));
    pipeline.embed(request("b"));
    pipeline.embed(request("c"));
    pipeline.embed(request("d"));

    assertThat(registry.health(provider.id()).orElseThrow().state())
        .isEqualTo(
            io.reliabilityai.gateway.dataplane.embedding.api.EmbeddingHealth.State.UNAVAILABLE);
  }

  @Test
  @DisplayName("a partial batch failure returns the successes alongside the refusal")
  void aPartialBatchFailureReturnsTheSuccessesAlongsideTheRefusal() {
    final EmbeddingFixtures.ScriptedProvider provider =
        new EmbeddingFixtures.ScriptedProvider("p", clock, 64)
            .then(new EmbeddingFixtures.ScriptedProvider.PartialFailure(Set.of(1)));
    final EmbeddingPipeline pipeline = pipelineOver(provider, EmbeddingGovernancePort.PERMISSIVE);

    final EmbeddingResponse response = pipeline.embed(request("good", "bad", "good2"));

    // One oversized record must not block every write batched alongside it.
    assertThat(response.embedded()).isEqualTo(2);
    assertThat(response.at(0)).isPresent();
    assertThat(response.at(1)).isEmpty();
    assertThat(response.at(2)).isPresent();
  }

  @Test
  @DisplayName("a known-unembeddable input is not sent again")
  void aKnownUnembeddableInputIsNotSentAgain() {
    final EmbeddingFixtures.ScriptedProvider provider =
        new EmbeddingFixtures.ScriptedProvider("p", clock, 64)
            .then(new EmbeddingFixtures.ScriptedProvider.PartialFailure(Set.of(0)));
    final EmbeddingPipeline pipeline = pipelineOver(provider, EmbeddingGovernancePort.PERMISSIVE);
    pipeline.embed(request("hopeless"));

    final EmbeddingResponse again = pipeline.embed(request("hopeless"));

    // The provider charges for refusals too, so re-sending a document that will never embed is a
    // standing bill for nothing.
    assertThat(provider.calls()).isEqualTo(1);
    assertThat(again.failures())
        .singleElement()
        .satisfies(f -> assertThat(f.reason()).isEqualTo(EmbeddingFailure.TOO_LARGE));
    assertThat(metrics.count("cache.negativeHit")).isEqualTo(1);
  }

  @Test
  @DisplayName("a transient failure is never negatively cached")
  void aTransientFailureIsNeverNegativelyCached() {
    final EmbeddingFixtures.ScriptedProvider provider =
        new EmbeddingFixtures.ScriptedProvider("p", clock, 64)
            .then(new EmbeddingFixtures.ScriptedProvider.Fail(EmbeddingFailure.RATE_LIMITED))
            .then(new EmbeddingFixtures.ScriptedProvider.Fail(EmbeddingFailure.RATE_LIMITED))
            .then(new EmbeddingFixtures.ScriptedProvider.Fail(EmbeddingFailure.RATE_LIMITED));
    final EmbeddingPipeline pipeline = pipelineOver(provider, EmbeddingGovernancePort.PERMISSIVE);
    pipeline.embed(request("throttled"));

    // Caching a rate limit would turn a momentary throttle into a lasting refusal.
    assertThat(cache.negativeSize()).isZero();
    assertThat(pipeline.embed(request("throttled")).complete()).isTrue();
  }

  @Test
  @DisplayName("a provider returning the wrong width fails that input rather than corrupting it")
  void aProviderReturningTheWrongWidthFailsThatInputRatherThanCorruptingIt() {
    final EmbeddingFixtures.ScriptedProvider provider =
        new EmbeddingFixtures.ScriptedProvider("p", clock, 64)
            .then(new EmbeddingFixtures.ScriptedProvider.WrongDimension(DIMENSION + 3));
    final EmbeddingPipeline pipeline = pipelineOver(provider, EmbeddingGovernancePort.PERMISSIVE);

    final EmbeddingResponse response = pipeline.embed(request("mismatched"));

    // Padding or truncating here would produce an index that looks healthy and retrieves nonsense.
    assertThat(response.failures())
        .singleElement()
        .satisfies(f -> assertThat(f.reason()).isEqualTo(EmbeddingFailure.DIMENSION_MISMATCH));
    assertThat(provider.calls()).isEqualTo(1);
  }

  @Test
  @DisplayName("a request for an unserved model is refused without a call")
  void aRequestForAnUnservedModelIsRefusedWithoutACall() {
    final EmbeddingFixtures.ScriptedProvider provider =
        new EmbeddingFixtures.ScriptedProvider("p", clock, 64);
    final EmbeddingPipeline pipeline = pipelineOver(provider, EmbeddingGovernancePort.PERMISSIVE);

    final EmbeddingResponse response =
        pipeline.embed(
            new EmbeddingRequest(
                ACME, "no-such-model", List.of("x"), EmbeddingRequest.EmbeddingPurpose.WRITE));

    assertThat(provider.calls()).isZero();
    assertThat(response.failures())
        .singleElement()
        .satisfies(f -> assertThat(f.reason()).isEqualTo(EmbeddingFailure.REJECTED));
  }

  @Test
  @DisplayName("a request larger than the provider batch limit is split")
  void aRequestLargerThanTheProviderBatchLimitIsSplit() {
    final EmbeddingFixtures.ScriptedProvider provider =
        new EmbeddingFixtures.ScriptedProvider("p", clock, 4);
    final EmbeddingPipeline pipeline = pipelineOver(provider, EmbeddingGovernancePort.PERMISSIVE);
    final String[] texts = new String[10];
    for (int i = 0; i < texts.length; i++) {
      texts[i] = "input-" + i;
    }

    final EmbeddingResponse response = pipeline.embed(request(texts));

    assertThat(response.outcomes()).hasSize(10);
    assertThat(response.complete()).isTrue();
    assertThat(provider.calls()).isEqualTo(3);
    assertThat(provider.largestBatch()).isEqualTo(4);
  }

  @Test
  @DisplayName("usage is reported and recorded")
  void usageIsReportedAndRecorded() {
    final EmbeddingPipeline pipeline =
        pipelineOver(
            new EmbeddingFixtures.ScriptedProvider("p", clock, 64),
            EmbeddingGovernancePort.PERMISSIVE);

    final EmbeddingResponse response = pipeline.embed(request("a", "b"));

    assertThat(response.usage().inputTokens()).isEqualTo(8L);
    assertThat(response.usage().providerCalls()).isEqualTo(1);
    assertThat(metrics.count("cost.inputTokens")).isEqualTo(8L);
  }

  @Test
  @DisplayName("the same input embeds identically every time")
  void theSameInputEmbedsIdenticallyEveryTime() {
    final EmbeddingPipeline pipeline =
        pipelineOver(
            new EmbeddingFixtures.ScriptedProvider("p", clock, 64),
            EmbeddingGovernancePort.PERMISSIVE);

    final float[] first = pipeline.embed(request("stable")).at(0).orElseThrow().vector();
    cache.invalidateTenant(ACME);
    final float[] second = pipeline.embed(request("stable")).at(0).orElseThrow().vector();

    // Invalidating the cache first, so this proves the pipeline is deterministic rather than that
    // the cache returned the same object.
    assertThat(second).isEqualTo(first);
  }
}

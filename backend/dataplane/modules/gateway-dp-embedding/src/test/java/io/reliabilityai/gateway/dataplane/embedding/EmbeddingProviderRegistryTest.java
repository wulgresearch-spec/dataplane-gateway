package io.reliabilityai.gateway.dataplane.embedding;

import static io.reliabilityai.gateway.dataplane.embedding.EmbeddingFixtures.MODEL;
import static org.assertj.core.api.Assertions.assertThat;

import io.reliabilityai.gateway.dataplane.embedding.api.EmbeddingFailure;
import io.reliabilityai.gateway.dataplane.embedding.api.EmbeddingHealth;
import io.reliabilityai.gateway.dataplane.embedding.application.EmbeddingProviderRegistry;
import io.reliabilityai.gateway.dataplane.embedding.domain.EmbeddingFailurePolicy;
import io.reliabilityai.gateway.dataplane.provider.api.ProviderId;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Which provider answers, and what happens when it stops answering.
 *
 * <p>The registry is the only place a vendor enters the system, so it is also the only place a
 * request can quietly change which vector space it lands in. An embedding from provider A and one
 * from provider B are not comparable — different training, different geometry — so a registry that
 * silently failed over mid-corpus would write records no later query could match, and would do it
 * without an error anywhere. Selection is therefore asserted to be <em>deterministic</em> as firmly
 * as it is asserted to be health-aware.
 */
@DisplayName("provider registry")
final class EmbeddingProviderRegistryTest {

  private EmbeddingFixtures.TestClock clock;
  private EmbeddingProviderRegistry registry;

  @BeforeEach
  void setUp() {
    clock = new EmbeddingFixtures.TestClock();
    registry = new EmbeddingProviderRegistry(EmbeddingFailurePolicy.defaults(), clock);
  }

  private EmbeddingFixtures.ConcurrentProvider provider(final String name) {
    return new EmbeddingFixtures.ConcurrentProvider(name, clock, 64);
  }

  @Test
  @DisplayName("an empty registry serves nothing")
  void anEmptyRegistryServesNothing() {
    assertThat(registry.size()).isZero();
    assertThat(registry.select(MODEL)).isEmpty();
  }

  @Test
  @DisplayName("a registered provider is selected for the model it serves")
  void aRegisteredProviderIsSelectedForTheModelItServes() {
    registry.register(provider("alpha"));

    final Optional<EmbeddingProviderRegistry.Selection> selection = registry.select(MODEL);

    assertThat(selection).isPresent();
    assertThat(selection.orElseThrow().provider().id().value()).isEqualTo("alpha");
    assertThat(selection.orElseThrow().model().id()).isEqualTo(MODEL);
    assertThat(selection.orElseThrow().model().dimension()).isEqualTo(EmbeddingFixtures.DIMENSION);
  }

  @Test
  @DisplayName("a model nobody serves selects nothing rather than something close")
  void aModelNobodyServesSelectsNothingRatherThanSomethingClose() {
    registry.register(provider("alpha"));

    // Falling back to "the only model we have" would hand the caller a vector of a width and a
    // geometry it did not ask for, which is worse than a refusal it can act on.
    assertThat(registry.select("some-other-model")).isEmpty();
  }

  @Test
  @DisplayName("selection is deterministic across repeated calls")
  void selectionIsDeterministicAcrossRepeatedCalls() {
    registry.register(provider("zulu"));
    registry.register(provider("alpha"));
    registry.register(provider("mike"));

    for (int i = 0; i < 50; i++) {
      // Ordered by provider id once health ties, and not by hash-map iteration order. Round-robin
      // here would spread one corpus across three incompatible vector spaces.
      assertThat(registry.select(MODEL).orElseThrow().provider().id().value()).isEqualTo("alpha");
    }
  }

  @Test
  @DisplayName("a healthy provider is preferred over a degraded one")
  void aHealthyProviderIsPreferredOverADegradedOne() {
    registry.register(provider("alpha"));
    registry.register(provider("bravo"));
    for (int i = 0; i < 3; i++) {
      registry.recordFailure(ProviderId.of("alpha"), EmbeddingFailure.UNAVAILABLE);
    }

    assertThat(registry.health(ProviderId.of("alpha")).orElseThrow().state())
        .isEqualTo(EmbeddingHealth.State.DEGRADED);
    // Health beats the id tie-break, so 'alpha' loses to 'bravo' despite sorting first.
    assertThat(registry.select(MODEL).orElseThrow().provider().id().value()).isEqualTo("bravo");
  }

  @Test
  @DisplayName("an unavailable provider is skipped when another one exists")
  void anUnavailableProviderIsSkippedWhenAnotherOneExists() {
    registry.register(provider("alpha"));
    registry.register(provider("bravo"));
    for (int i = 0; i < 10; i++) {
      registry.recordFailure(ProviderId.of("alpha"), EmbeddingFailure.NETWORK);
    }

    assertThat(registry.health(ProviderId.of("alpha")).orElseThrow().usable()).isFalse();
    assertThat(registry.select(MODEL).orElseThrow().provider().id().value()).isEqualTo("bravo");
  }

  @Test
  @DisplayName("an unavailable provider is still selected when it is the only one")
  void anUnavailableProviderIsStillSelectedWhenItIsTheOnlyOne() {
    registry.register(provider("alpha"));
    for (int i = 0; i < 10; i++) {
      registry.recordFailure(ProviderId.of("alpha"), EmbeddingFailure.NETWORK);
    }

    // Deliberate, and the limitation is recorded as B59. Refusing to call the only provider we have
    // would convert a transient outage into a permanent one and remove the only path back to
    // health,
    // because health here is observed from real traffic and there is no probe to recover with.
    assertThat(registry.select(MODEL)).isPresent();
    assertThat(registry.select(MODEL).orElseThrow().provider().id().value()).isEqualTo("alpha");
  }

  @Test
  @DisplayName("a success returns a provider to selection")
  void aSuccessReturnsAProviderToSelection() {
    registry.register(provider("alpha"));
    registry.register(provider("bravo"));
    for (int i = 0; i < 10; i++) {
      registry.recordFailure(ProviderId.of("alpha"), EmbeddingFailure.TIMEOUT);
    }
    assertThat(registry.select(MODEL).orElseThrow().provider().id().value()).isEqualTo("bravo");

    registry.recordSuccess(ProviderId.of("alpha"));

    assertThat(registry.select(MODEL).orElseThrow().provider().id().value()).isEqualTo("alpha");
  }

  @Test
  @DisplayName("health is tracked per provider, not shared")
  void healthIsTrackedPerProviderNotShared() {
    registry.register(provider("alpha"));
    registry.register(provider("bravo"));

    registry.recordFailure(ProviderId.of("alpha"), EmbeddingFailure.RATE_LIMITED);

    assertThat(registry.health(ProviderId.of("alpha")).orElseThrow().consecutiveFailures())
        .isEqualTo(1);
    assertThat(registry.health(ProviderId.of("bravo")).orElseThrow().consecutiveFailures())
        .isZero();
  }

  @Test
  @DisplayName("recording against an unregistered provider is ignored rather than fatal")
  void recordingAgainstAnUnregisteredProviderIsIgnoredRatherThanFatal() {
    // A provider deregistered by a config reload while a call was in flight must not take the
    // calling thread down on the way back.
    registry.recordSuccess(ProviderId.of("ghost"));
    registry.recordFailure(ProviderId.of("ghost"), EmbeddingFailure.NETWORK);

    assertThat(registry.health(ProviderId.of("ghost"))).isEmpty();
  }

  @Test
  @DisplayName("deregistering removes a provider from selection")
  void deregisteringRemovesAProviderFromSelection() {
    registry.register(provider("alpha"));
    registry.register(provider("bravo"));

    registry.deregister(ProviderId.of("alpha"));

    assertThat(registry.size()).isEqualTo(1);
    assertThat(registry.select(MODEL).orElseThrow().provider().id().value()).isEqualTo("bravo");
  }

  @Test
  @DisplayName("re-registering the same id replaces rather than duplicates")
  void reRegisteringTheSameIdReplacesRatherThanDuplicates() {
    registry.register(provider("alpha"));
    registry.register(provider("alpha"));

    assertThat(registry.size()).isEqualTo(1);
  }

  @Test
  @DisplayName("re-registering resets observed health, because it is a different instance")
  void reRegisteringResetsObservedHealth() {
    registry.register(provider("alpha"));
    for (int i = 0; i < 10; i++) {
      registry.recordFailure(ProviderId.of("alpha"), EmbeddingFailure.NETWORK);
    }

    registry.register(provider("alpha"));

    // Health is observed from traffic against a particular adapter instance. A reload that swaps in
    // a new one — new credentials, new endpoint — has no evidence about the new instance yet, and
    // inheriting the old instance's failures would keep a fixed provider marked broken.
    assertThat(registry.health(ProviderId.of("alpha")).orElseThrow().state())
        .isEqualTo(EmbeddingHealth.State.HEALTHY);
  }
}

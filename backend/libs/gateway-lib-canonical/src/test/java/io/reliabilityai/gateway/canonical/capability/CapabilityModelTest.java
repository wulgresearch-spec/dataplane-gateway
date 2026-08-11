package io.reliabilityai.gateway.canonical.capability;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Locale;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/**
 * Tests the capability vocabulary and the set algebra every capability question is asked through.
 */
class CapabilityModelTest {

  /** The tokens that were already published before the typed model existed. */
  private static final Set<String> FROZEN_LEGACY_TOKENS =
      Set.of("tools", "streaming", "json_mode", "reasoning", "vision", "embeddings");

  // ---- the vocabulary --------------------------------------------------------------------------

  @ParameterizedTest
  @EnumSource(ProviderCapability.class)
  void everyCapabilityHasALowercaseWireToken(final ProviderCapability capability) {
    assertThat(capability.token()).isNotBlank();
    assertThat(capability.token()).isEqualTo(capability.token().toLowerCase(Locale.ROOT));
  }

  @ParameterizedTest
  @EnumSource(ProviderCapability.class)
  void everyTokenRoundTripsBackToItsCapability(final ProviderCapability capability) {
    assertThat(ProviderCapability.fromToken(capability.token())).contains(capability);
  }

  @Test
  void noTwoCapabilitiesShareAToken() {
    final Set<String> tokens =
        java.util.Arrays.stream(ProviderCapability.values())
            .map(ProviderCapability::token)
            .collect(java.util.stream.Collectors.toSet());

    // A shared token would make two capabilities indistinguishable on the wire, so a snapshot
    // granting
    // one would silently grant the other.
    assertThat(tokens).hasSize(ProviderCapability.values().length);
  }

  @Test
  void theTokensThatPredateThisTypeAreUnchanged() {
    // Renaming any of these is a breaking change to every published snapshot, and would silently
    // disable the capability rather than fail — the router would simply stop matching it.
    assertThat(ProviderCapability.FUNCTION_CALLING.token()).isEqualTo("tools");
    assertThat(ProviderCapability.STREAMING.token()).isEqualTo("streaming");
    assertThat(ProviderCapability.JSON_MODE.token()).isEqualTo("json_mode");
    assertThat(ProviderCapability.REASONING.token()).isEqualTo("reasoning");
    assertThat(ProviderCapability.VISION.token()).isEqualTo("vision");
    assertThat(ProviderCapability.EMBEDDINGS.token()).isEqualTo("embeddings");
  }

  @Test
  void everyLegacyTokenStillResolves() {
    for (final String token : FROZEN_LEGACY_TOKENS) {
      assertThat(ProviderCapability.fromToken(token)).as(token).isPresent();
    }
  }

  @Test
  void theVocabularyCoversTheOperationsTheGatewayGoverns() {
    // Each of these is a difference between providers the gateway must be able to express without
    // knowing which vendor it is talking to.
    assertThat(ProviderCapability.values())
        .contains(
            ProviderCapability.TEXT_GENERATION,
            ProviderCapability.CHAT_COMPLETION,
            ProviderCapability.TEXT_COMPLETION,
            ProviderCapability.RESPONSES,
            ProviderCapability.STREAMING,
            ProviderCapability.FUNCTION_CALLING,
            ProviderCapability.JSON_SCHEMA,
            ProviderCapability.EMBEDDINGS,
            ProviderCapability.IMAGE_GENERATION,
            ProviderCapability.VISION,
            ProviderCapability.AUDIO_INPUT,
            ProviderCapability.AUDIO_OUTPUT,
            ProviderCapability.MULTIMODAL,
            ProviderCapability.REASONING,
            ProviderCapability.LONG_CONTEXT,
            ProviderCapability.PROMPT_CACHE,
            ProviderCapability.BATCH,
            ProviderCapability.FILES,
            ProviderCapability.ASSISTANTS,
            ProviderCapability.TOKEN_COUNTING);
  }

  @Test
  void anUnknownTokenResolvesToNothingRatherThanThrowing() {
    // A snapshot from a newer control plane may name capabilities this build has never heard of.
    assertThat(ProviderCapability.fromToken("quantum_entanglement")).isEmpty();
    assertThat(ProviderCapability.fromToken(null)).isEmpty();
    assertThat(ProviderCapability.fromToken("  ")).isEmpty();
  }

  @Test
  void requiringAnUnknownTokenIsAnError() {
    assertThatThrownBy(() -> ProviderCapability.requireToken("nope"))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void tokenLookupIsCaseInsensitive() {
    assertThat(ProviderCapability.fromToken("JSON_MODE")).contains(ProviderCapability.JSON_MODE);
  }

  // ---- the set ---------------------------------------------------------------------------------

  @Test
  void anEmptySetSupportsNothing() {
    assertThat(CapabilitySet.none().isEmpty()).isTrue();
    assertThat(CapabilitySet.none().supports(ProviderCapability.STREAMING)).isFalse();
    assertThat(CapabilitySet.none().tokens()).isEmpty();
  }

  @Test
  void aSetSupportsExactlyWhatItWasGiven() {
    final CapabilitySet set =
        CapabilitySet.of(ProviderCapability.STREAMING, ProviderCapability.VISION);

    assertThat(set.supports(ProviderCapability.STREAMING)).isTrue();
    assertThat(set.supports(ProviderCapability.VISION)).isTrue();
    assertThat(set.supports(ProviderCapability.BATCH)).isFalse();
    assertThat(set.size()).isEqualTo(2);
  }

  @Test
  void tokensAreSortedSoTwoIdenticalDeclarationsProduceIdenticalSnapshots() {
    final CapabilitySet one =
        CapabilitySet.of(ProviderCapability.VISION, ProviderCapability.STREAMING);
    final CapabilitySet other =
        CapabilitySet.of(ProviderCapability.STREAMING, ProviderCapability.VISION);

    assertThat(one.tokens()).containsExactly("streaming", "vision");
    assertThat(one.tokens()).isEqualTo(other.tokens());
    assertThat(one).isEqualTo(other).hasSameHashCodeAs(other);
  }

  @Test
  void everythingRequiredIsSupportedWhenTheOfferIsASuperset() {
    final CapabilitySet offered =
        CapabilitySet.of(
            ProviderCapability.STREAMING, ProviderCapability.VISION, ProviderCapability.BATCH);
    final CapabilitySet required = CapabilitySet.of(ProviderCapability.STREAMING);

    assertThat(offered.supportsAll(required)).isTrue();
    assertThat(offered.missingFrom(required).isEmpty()).isTrue();
  }

  @Test
  void aShortfallIsNamedRatherThanJustReported() {
    final CapabilitySet offered = CapabilitySet.of(ProviderCapability.STREAMING);
    final CapabilitySet required =
        CapabilitySet.of(ProviderCapability.STREAMING, ProviderCapability.JSON_SCHEMA);

    assertThat(offered.supportsAll(required)).isFalse();
    // "no candidate matched" sends an operator reading snapshots; naming the capability does not.
    assertThat(offered.missingFrom(required).tokens()).containsExactly("json_schema");
  }

  @Test
  void anEmptyRequirementIsSatisfiedByAnything() {
    assertThat(CapabilitySet.none().supportsAll(CapabilitySet.none())).isTrue();
    assertThat(CapabilitySet.of(ProviderCapability.BATCH).supportsAll(CapabilitySet.none()))
        .isTrue();
  }

  @Test
  void nothingSatisfiesARequirementWhenTheOfferIsEmpty() {
    assertThat(CapabilitySet.none().supportsAll(CapabilitySet.of(ProviderCapability.STREAMING)))
        .isFalse();
  }

  @Test
  void intersectionKeepsOnlyWhatBothHave() {
    final CapabilitySet left =
        CapabilitySet.of(ProviderCapability.STREAMING, ProviderCapability.VISION);
    final CapabilitySet right =
        CapabilitySet.of(ProviderCapability.VISION, ProviderCapability.BATCH);

    assertThat(left.intersect(right).tokens()).containsExactly("vision");
  }

  @Test
  void unionKeepsWhatEitherHas() {
    final CapabilitySet left = CapabilitySet.of(ProviderCapability.STREAMING);
    final CapabilitySet right = CapabilitySet.of(ProviderCapability.BATCH);

    assertThat(left.union(right).tokens()).containsExactly("batch", "streaming");
  }

  @Test
  void aSetBuiltFromTokensDropsTheOnesThisBuildDoesNotKnow() {
    final CapabilitySet set = CapabilitySet.fromTokens(List.of("streaming", "from_the_future"));

    // Keeping an uninterpretable token would make the route appear to support something no code
    // here
    // can deliver.
    assertThat(set.tokens()).containsExactly("streaming");
  }

  @Test
  void aSetRoundTripsThroughItsTokens() {
    final CapabilitySet original =
        CapabilitySet.of(
            ProviderCapability.AUDIO_INPUT,
            ProviderCapability.BATCH,
            ProviderCapability.JSON_SCHEMA);

    assertThat(CapabilitySet.fromTokens(original.tokens())).isEqualTo(original);
  }

  @Test
  void aSetIsImmutableFromTheOutside() {
    final CapabilitySet set = CapabilitySet.of(ProviderCapability.STREAMING);

    assertThatThrownBy(() -> set.capabilities().add(ProviderCapability.BATCH))
        .isInstanceOf(UnsupportedOperationException.class);
    assertThatThrownBy(() -> set.tokens().add("nope"))
        .isInstanceOf(UnsupportedOperationException.class);
  }

  @Test
  void copyingACollectionPreservesMembership() {
    final CapabilitySet set =
        CapabilitySet.copyOf(List.of(ProviderCapability.FILES, ProviderCapability.FILES));

    assertThat(set.size()).isEqualTo(1);
    assertThat(set.supports(ProviderCapability.FILES)).isTrue();
  }
}

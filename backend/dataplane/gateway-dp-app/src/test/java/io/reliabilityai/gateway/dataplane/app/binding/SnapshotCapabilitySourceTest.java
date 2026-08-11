package io.reliabilityai.gateway.dataplane.app.binding;

import static org.assertj.core.api.Assertions.assertThat;

import io.reliabilityai.gateway.canonical.decision.RouteTarget;
import io.reliabilityai.gateway.canonical.identity.CanonicalModelId;
import io.reliabilityai.gateway.canonical.identity.SnapshotVersion;
import io.reliabilityai.gateway.dataplane.provider.api.CapabilityMapping;
import io.reliabilityai.gateway.dataplane.provider.api.PinnedVersion;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/** The capability snapshot resolver: declared capabilities, fail-closed lookups, determinism. */
class SnapshotCapabilitySourceTest {

  private static final CanonicalModelId CHAT = new CanonicalModelId("gpt-4o-mini");
  private static final CanonicalModelId EMBED = new CanonicalModelId("text-embedding-3-small");
  private static final PinnedVersion VERSION = new PinnedVersion("2024-10-01");

  private final SnapshotCapabilitySource source =
      new SnapshotCapabilitySource(
          new SnapshotCapabilitySource.CapabilitySnapshot(
              new SnapshotVersion("provider-capabilities", "v7"),
              List.of(
                  new ProviderCapabilityEntry(
                      CHAT,
                      "openai-main",
                      VERSION,
                      true,
                      true,
                      true,
                      false,
                      true,
                      false,
                      4096L,
                      Map.of("family", "gpt-4o")),
                  new ProviderCapabilityEntry(
                      EMBED,
                      "openai-main",
                      VERSION,
                      false,
                      false,
                      false,
                      false,
                      false,
                      true,
                      8191L,
                      Map.of()))));

  @Test
  void resolvesAKnownModelOnAKnownRoute() {
    final Optional<CapabilityMapping> mapping =
        source.mappingFor(new RouteTarget(CHAT, "openai-main"));

    assertThat(mapping).isPresent();
    assertThat(mapping.orElseThrow().canonicalModelId()).isEqualTo(CHAT);
    assertThat(mapping.orElseThrow().providerApiVersion()).isEqualTo(VERSION);
    assertThat(mapping.orElseThrow().snapshotVersion())
        .isEqualTo(new SnapshotVersion("provider-capabilities", "v7"));
  }

  @Test
  void reportsToolStreamingJsonAndVisionSupport() {
    final CapabilityMapping mapping =
        source.mappingFor(new RouteTarget(CHAT, "openai-main")).orElseThrow();

    assertThat(mapping.capabilities())
        .contains(
            ProviderCapabilityEntry.CAPABILITY_TOOLS,
            ProviderCapabilityEntry.CAPABILITY_STREAMING,
            ProviderCapabilityEntry.CAPABILITY_JSON_MODE,
            ProviderCapabilityEntry.CAPABILITY_VISION);
  }

  @Test
  void omitsCapabilitiesTheModelDoesNotDeclare() {
    final CapabilityMapping mapping =
        source.mappingFor(new RouteTarget(CHAT, "openai-main")).orElseThrow();

    // This model declares no reasoning support, so the token must be absent — an over-granted
    // capability would let the router send work the model cannot do.
    assertThat(mapping.capabilities())
        .doesNotContain(ProviderCapabilityEntry.CAPABILITY_REASONING)
        .doesNotContain(ProviderCapabilityEntry.CAPABILITY_EMBEDDINGS);
  }

  @Test
  void reportsEmbeddingsSupportSeparately() {
    final CapabilityMapping mapping =
        source.mappingFor(new RouteTarget(EMBED, "openai-main")).orElseThrow();

    assertThat(mapping.capabilities())
        .containsExactly(ProviderCapabilityEntry.CAPABILITY_EMBEDDINGS);
  }

  @Test
  void anUnsupportedModelResolvesToEmpty() {
    assertThat(source.mappingFor(new RouteTarget(new CanonicalModelId("o1-pro"), "openai-main")))
        .isEmpty();
  }

  @Test
  void aKnownModelOnAnUnknownRouteResolvesToEmpty() {
    // Capabilities are per route, not per model: the same model on a different route is not
    // assumed.
    assertThat(source.mappingFor(new RouteTarget(CHAT, "openai-secondary"))).isEmpty();
  }

  @Test
  void exposesMaxTokensAndProviderMetadataForOperators() {
    final ProviderCapabilityEntry entry =
        source.entryFor(new RouteTarget(CHAT, "openai-main")).orElseThrow();

    assertThat(entry.maxTokens()).isEqualTo(4096L);
    assertThat(entry.providerMetadata()).containsEntry("family", "gpt-4o");
  }

  @Test
  void lookupIsDeterministic() {
    final Optional<CapabilityMapping> first =
        source.mappingFor(new RouteTarget(CHAT, "openai-main"));

    for (int run = 0; run < 50; run++) {
      assertThat(source.mappingFor(new RouteTarget(CHAT, "openai-main"))).isEqualTo(first);
    }
  }

  @Test
  void anEmptySnapshotResolvesNothing() {
    final SnapshotCapabilitySource empty =
        new SnapshotCapabilitySource(
            new SnapshotCapabilitySource.CapabilitySnapshot(
                new SnapshotVersion("provider-capabilities", "v0"), List.of()));

    assertThat(empty.mappingFor(new RouteTarget(CHAT, "openai-main"))).isEmpty();
  }
}

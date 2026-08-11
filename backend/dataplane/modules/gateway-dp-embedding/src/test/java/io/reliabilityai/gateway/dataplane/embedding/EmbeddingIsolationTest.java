package io.reliabilityai.gateway.dataplane.embedding;

import static io.reliabilityai.gateway.dataplane.embedding.EmbeddingFixtures.ACME;
import static io.reliabilityai.gateway.dataplane.embedding.EmbeddingFixtures.DIMENSION;
import static io.reliabilityai.gateway.dataplane.embedding.EmbeddingFixtures.GLOBEX;
import static io.reliabilityai.gateway.dataplane.embedding.EmbeddingFixtures.MODEL;
import static org.assertj.core.api.Assertions.assertThat;

import io.reliabilityai.gateway.canonical.identity.TenantScope;
import io.reliabilityai.gateway.dataplane.embedding.api.CanonicalEmbedding;
import io.reliabilityai.gateway.dataplane.embedding.api.EmbeddingAuditPort;
import io.reliabilityai.gateway.dataplane.embedding.api.EmbeddingCapability;
import io.reliabilityai.gateway.dataplane.embedding.api.EmbeddingFailure;
import io.reliabilityai.gateway.dataplane.embedding.api.EmbeddingGovernancePort;
import io.reliabilityai.gateway.dataplane.embedding.api.EmbeddingHealth;
import io.reliabilityai.gateway.dataplane.embedding.api.EmbeddingModel;
import io.reliabilityai.gateway.dataplane.embedding.api.EmbeddingProviderPort;
import io.reliabilityai.gateway.dataplane.embedding.api.EmbeddingRequest;
import io.reliabilityai.gateway.dataplane.embedding.api.EmbeddingResponse;
import io.reliabilityai.gateway.dataplane.embedding.api.EmbeddingUsage;
import io.reliabilityai.gateway.dataplane.embedding.application.EmbeddingCache;
import io.reliabilityai.gateway.dataplane.embedding.application.EmbeddingPipeline;
import io.reliabilityai.gateway.dataplane.embedding.application.EmbeddingProviderRegistry;
import io.reliabilityai.gateway.dataplane.embedding.domain.EmbeddingFailurePolicy;
import io.reliabilityai.gateway.dataplane.embedding.domain.EmbeddingRetryPolicy;
import io.reliabilityai.gateway.dataplane.embedding.internal.InProcessEmbeddingMetrics;
import io.reliabilityai.gateway.dataplane.provider.api.ProviderId;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * What the cache key must bind, and what happens when it does not.
 *
 * <p>Every test here corresponds to a defect that a green suite did not catch. A cache hit hands
 * back a vector computed for some <em>other</em> request, so the only question the key ever answers
 * is "which other requests may this one be confused with?" — and the answer has to be "requests
 * identical in every dimension that could change the right answer or the right to see it".
 *
 * <p>These are regressions, not coverage. Each one failed before the key was widened.
 */
@DisplayName("embedding cache isolation")
final class EmbeddingIsolationTest {

  private EmbeddingFixtures.TestClock clock;
  private InProcessEmbeddingMetrics metrics;
  private EmbeddingCache cache;
  private EmbeddingProviderRegistry registry;

  @BeforeEach
  void setUp() {
    clock = new EmbeddingFixtures.TestClock();
    metrics = new InProcessEmbeddingMetrics();
    cache = new EmbeddingCache(clock, metrics, Duration.ofHours(1), Duration.ofMinutes(10), 10_000);
    registry = new EmbeddingProviderRegistry(EmbeddingFailurePolicy.defaults(), clock);
  }

  /**
   * A provider whose vectors carry its identity, so a cross-provider hit is visible in the result.
   */
  private static final class MarkedProvider implements EmbeddingProviderPort {

    private final ProviderId id;
    private final float mark;

    MarkedProvider(final String name, final float mark) {
      this.id = ProviderId.of(name);
      this.mark = mark;
    }

    @Override
    public ProviderId id() {
      return id;
    }

    @Override
    public EmbeddingCapability capability() {
      return new EmbeddingCapability(
          Set.of(new EmbeddingModel(MODEL, DIMENSION, 8191, 20_000L)), 64, 1_000_000, false, false);
    }

    @Override
    public EmbeddingHealth health() {
      return EmbeddingHealth.healthy(java.time.Instant.EPOCH);
    }

    @Override
    public EmbeddingResponse embed(final EmbeddingRequest request) {
      final float[] vector = new float[DIMENSION];
      Arrays.fill(vector, mark);
      final List<EmbeddingResponse.Outcome> outcomes = new ArrayList<>();
      for (int i = 0; i < request.size(); i++) {
        outcomes.add(
            new EmbeddingResponse.Outcome.Embedded(
                new CanonicalEmbedding(
                    id,
                    MODEL,
                    DIMENSION,
                    java.time.Instant.EPOCH,
                    EmbeddingUsage.FREE,
                    false,
                    Map.of(),
                    vector)));
      }
      return new EmbeddingResponse(outcomes, new EmbeddingUsage(4L, 1, 7L));
    }
  }

  private EmbeddingPipeline pipeline() {
    return new EmbeddingPipeline(
        registry,
        cache,
        EmbeddingGovernancePort.PERMISSIVE,
        EmbeddingRetryPolicy.immediate(1),
        metrics,
        EmbeddingAuditPort.NOOP);
  }

  private static EmbeddingRequest request(final TenantScope tenant, final String text) {
    return EmbeddingRequest.of(tenant, MODEL, text, EmbeddingRequest.EmbeddingPurpose.WRITE);
  }

  @Test
  @DisplayName("two workspaces in one tenant do not share cache entries")
  void twoWorkspacesInOneTenantDoNotShareCacheEntries() {
    // MemoryScope (AD-026 §9) names workspace as an isolation level in its own right. Binding only
    // org and tenant let one workspace learn, by timing or by a free request, that another had
    // already embedded a particular string.
    final TenantScope alpha = new TenantScope("acme", "core", "ws-alpha", null);
    final TenantScope beta = new TenantScope("acme", "core", "ws-beta", null);

    assertThat(EmbeddingFixtures.key(alpha, "shared"))
        .isNotEqualTo(EmbeddingFixtures.key(beta, "shared"));
  }

  @Test
  @DisplayName("two projects in one workspace do not share cache entries")
  void twoProjectsInOneWorkspaceDoNotShareCacheEntries() {
    final TenantScope one = new TenantScope("acme", "core", "ws", "proj-one");
    final TenantScope two = new TenantScope("acme", "core", "ws", "proj-two");

    assertThat(EmbeddingFixtures.key(one, "shared"))
        .isNotEqualTo(EmbeddingFixtures.key(two, "shared"));
  }

  @Test
  @DisplayName("an absent workspace is distinct from an empty one")
  void anAbsentWorkspaceIsDistinctFromAnEmptyOne() {
    // Mapping null onto "" would collapse two scopes the type system keeps apart. The presence flag
    // in the digest is what keeps them separate.
    final TenantScope absent = new TenantScope("acme", "core", null, null);
    final TenantScope empty = new TenantScope("acme", "core", "", null);

    assertThat(EmbeddingFixtures.key(absent, "x")).isNotEqualTo(EmbeddingFixtures.key(empty, "x"));
  }

  @Test
  @DisplayName("a workspace value cannot be shifted into the project field")
  void aWorkspaceValueCannotBeShiftedIntoTheProjectField() {
    final TenantScope left = new TenantScope("acme", "core", "ab", "cd");
    final TenantScope right = new TenantScope("acme", "core", "a", "bcd");

    assertThat(EmbeddingFixtures.key(left, "x")).isNotEqualTo(EmbeddingFixtures.key(right, "x"));
  }

  @Test
  @DisplayName("the same text under two providers keys differently")
  void theSameTextUnderTwoProvidersKeysDifferently() {
    assertThat(
            EmbeddingCache.keyFor(
                ACME, EmbeddingFixtures.MODEL_DEF, ProviderId.of("alpha"), "shared"))
        .isNotEqualTo(
            EmbeddingCache.keyFor(
                ACME, EmbeddingFixtures.MODEL_DEF, ProviderId.of("beta"), "shared"));
  }

  @Test
  @DisplayName("the same model id at two widths keys differently")
  void theSameModelIdAtTwoWidthsKeysDifferently() {
    // A neutral model id repointed at a model of a different width must not reuse the old vectors:
    // CanonicalEmbedding would reject the mismatch, but only after the cache had already served it.
    assertThat(
            EmbeddingCache.keyFor(
                ACME, new EmbeddingModel(MODEL, 8, 8191, 1L), EmbeddingFixtures.PROVIDER, "x"))
        .isNotEqualTo(
            EmbeddingCache.keyFor(
                ACME, new EmbeddingModel(MODEL, 16, 8191, 1L), EmbeddingFixtures.PROVIDER, "x"));
  }

  @Test
  @DisplayName("a provider swap does not serve the decommissioned provider's vectors")
  void aProviderSwapDoesNotServeTheDecommissionedProvidersVectors() {
    registry.register(new MarkedProvider("alpha", 1.0f));
    final EmbeddingPipeline pipeline = pipeline();
    final CanonicalEmbedding first = pipeline.embed(request(ACME, "hello")).at(0).orElseThrow();
    assertThat(first.providerId().value()).isEqualTo("alpha");

    // The operator repoints the neutral model id at another vendor — the exact operation the
    // neutral
    // id exists to make possible.
    registry.deregister(ProviderId.of("alpha"));
    registry.register(new MarkedProvider("beta", -1.0f));

    final CanonicalEmbedding second = pipeline.embed(request(ACME, "hello")).at(0).orElseThrow();

    // Before the provider entered the key this returned 'alpha', mixing two vector spaces in one
    // corpus with no error anywhere — the failure ADR-030 §4.2 says provenance exists to make
    // detectable, defeated by the cache that sits in front of it.
    assertThat(second.providerId().value()).isEqualTo("beta");
    assertThat(second.vector()[0]).isNotEqualTo(first.vector()[0]);
  }

  @Test
  @DisplayName("tenants still do not share entries after the key was widened")
  void tenantsStillDoNotShareEntriesAfterTheKeyWasWidened() {
    registry.register(new MarkedProvider("alpha", 1.0f));
    final EmbeddingPipeline pipeline = pipeline();

    pipeline.embed(request(ACME, "shared string"));
    pipeline.embed(request(GLOBEX, "shared string"));

    assertThat(cache.size()).isEqualTo(2);
  }

  @Test
  @DisplayName("an authentication failure does not poison the negative cache")
  void anAuthenticationFailureDoesNotPoisonTheNegativeCache() {
    final String key = EmbeddingFixtures.key(ACME, "x");

    cache.putNegative(key, EmbeddingFailure.AUTH_FAILED);

    // AUTH_FAILED is not retryable — replaying a rejected credential is an attack on your own
    // provider — but it says nothing about the input. Remembering it means rotating the key does
    // not
    // restore service until the negative TTL lapses, with nothing in the logs to explain the delay.
    assertThat(cache.getNegative(key)).isEmpty();
  }

  @Test
  @DisplayName("an internal failure does not poison the negative cache")
  void anInternalFailureDoesNotPoisonTheNegativeCache() {
    final String key = EmbeddingFixtures.key(ACME, "x");

    cache.putNegative(key, EmbeddingFailure.INTERNAL);

    // Its cause is by definition unknown, so it cannot be attributed to the input. A one-off
    // provider glitch must not make one document unembeddable for the next ten minutes.
    assertThat(cache.getNegative(key)).isEmpty();
  }

  @Test
  @DisplayName("failures that really are about the input are still remembered")
  void failuresThatReallyAreAboutTheInputAreStillRemembered() {
    for (final EmbeddingFailure reason :
        List.of(
            EmbeddingFailure.TOO_LARGE,
            EmbeddingFailure.REJECTED,
            EmbeddingFailure.DIMENSION_MISMATCH)) {
      final String key = EmbeddingFixtures.key(ACME, "input-" + reason);
      cache.putNegative(key, reason);
      assertThat(cache.getNegative(key)).contains(reason);
    }
  }
}

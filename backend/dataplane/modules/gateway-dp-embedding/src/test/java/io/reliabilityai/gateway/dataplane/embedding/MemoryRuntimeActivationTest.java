package io.reliabilityai.gateway.dataplane.embedding;

import static io.reliabilityai.gateway.dataplane.embedding.EmbeddingFixtures.ACME;
import static io.reliabilityai.gateway.dataplane.embedding.EmbeddingFixtures.GLOBEX;
import static io.reliabilityai.gateway.dataplane.embedding.EmbeddingFixtures.MODEL;
import static org.assertj.core.api.Assertions.assertThat;

import io.reliabilityai.gateway.canonical.identity.PrincipalId;
import io.reliabilityai.gateway.canonical.identity.TenantScope;
import io.reliabilityai.gateway.dataplane.embedding.api.EmbeddingAuditPort;
import io.reliabilityai.gateway.dataplane.embedding.api.EmbeddingGovernancePort;
import io.reliabilityai.gateway.dataplane.embedding.application.EmbeddingBatcher;
import io.reliabilityai.gateway.dataplane.embedding.application.EmbeddingCache;
import io.reliabilityai.gateway.dataplane.embedding.application.EmbeddingPipeline;
import io.reliabilityai.gateway.dataplane.embedding.application.EmbeddingProviderRegistry;
import io.reliabilityai.gateway.dataplane.embedding.domain.EmbeddingFailurePolicy;
import io.reliabilityai.gateway.dataplane.embedding.domain.EmbeddingRetryPolicy;
import io.reliabilityai.gateway.dataplane.embedding.internal.InProcessEmbeddingMetrics;
import io.reliabilityai.gateway.dataplane.embedding.internal.PipelineEmbeddingPort;
import io.reliabilityai.gateway.dataplane.memory.api.EmbeddingPort;
import io.reliabilityai.gateway.dataplane.memory.api.MemoryAuditPort;
import io.reliabilityai.gateway.dataplane.memory.api.MemoryCaller;
import io.reliabilityai.gateway.dataplane.memory.api.MemoryGovernancePort;
import io.reliabilityai.gateway.dataplane.memory.api.MemoryOutcome;
import io.reliabilityai.gateway.dataplane.memory.api.MemoryScope;
import io.reliabilityai.gateway.dataplane.memory.api.MemoryType;
import io.reliabilityai.gateway.dataplane.memory.api.MemoryWriteRequest;
import io.reliabilityai.gateway.dataplane.memory.application.MemoryEmbedder;
import io.reliabilityai.gateway.dataplane.memory.application.MemoryIdFactory;
import io.reliabilityai.gateway.dataplane.memory.application.MemoryReadPipeline;
import io.reliabilityai.gateway.dataplane.memory.application.MemoryRuntime;
import io.reliabilityai.gateway.dataplane.memory.application.MemoryWritePipeline;
import io.reliabilityai.gateway.dataplane.memory.domain.MemoryRanker;
import io.reliabilityai.gateway.dataplane.memory.internal.ConservativePiiClassifier;
import io.reliabilityai.gateway.dataplane.memory.internal.InMemoryMemoryStore;
import io.reliabilityai.gateway.dataplane.memory.internal.InMemoryVectorIndex;
import io.reliabilityai.gateway.dataplane.memory.internal.InProcessMemoryMetrics;
import io.reliabilityai.gateway.dataplane.memory.internal.MemoryPolicyStore;
import io.reliabilityai.gateway.dataplane.memory.internal.NotRealCryptoSealer;
import java.time.Duration;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * The real Memory Runtime, driven through the real B25 embedding stack.
 *
 * <p>Every other test in this module exercises the embedding pipeline in isolation. This one asks
 * the only question that matters for activation: <b>does {@code MemoryWritePipeline} actually reach
 * a provider through {@code EmbeddingPort}, and does the write behave correctly when it does
 * not?</b> Nothing is mocked below {@code EmbeddingPort} — the batcher, the cache, the registry,
 * the retry policy and the normalizer all run.
 *
 * <p><b>This proves the seam connects. It does not make embedding live.</b> Nothing in production
 * constructs any of this: {@code gateway-dp-app} depends on no memory or embedding module, and
 * AD-026 §15.2 gates that wiring behind Phase 5 completing. The last test in this file pins the
 * exact contract defect that stops a single shared runtime from being wired safely.
 */
@DisplayName("memory runtime activation")
final class MemoryRuntimeActivationTest {

  private final InMemoryMemoryStore store = new InMemoryMemoryStore();
  private final InMemoryVectorIndex index = new InMemoryVectorIndex();
  private final MemoryPolicyStore policies = new MemoryPolicyStore(4);
  private final InProcessMemoryMetrics memoryMetrics = new InProcessMemoryMetrics();
  private final InProcessEmbeddingMetrics embeddingMetrics = new InProcessEmbeddingMetrics();
  private final EmbeddingFixtures.TestClock clock = new EmbeddingFixtures.TestClock();

  /** The one region this test deployment permits. */
  private static final String REGION = "eu-west-1";

  private EmbeddingCache cache;
  private EmbeddingBatcher batcher;

  @org.junit.jupiter.api.BeforeEach
  void installAPermissivePolicy() {
    // MemoryPolicyStore starts UNENFORCEABLE and refuses every write (MEM-21, fail closed). That is
    // correct and is exercised elsewhere; here it would mean nothing ever reached the embedder.
    policies.install(
        new io.reliabilityai.gateway.dataplane.memory.domain.MemoryPolicySnapshot(
            1L,
            java.util.Map.of(),
            java.util.Map.of(),
            new io.reliabilityai.gateway.dataplane.memory.domain.EffectiveMemoryPolicy(
                Optional.empty(),
                Optional.empty(),
                io.reliabilityai.gateway.dataplane.memory.api.PiiAction.ALLOW,
                java.util.Set.of(REGION),
                false,
                false,
                true,
                Optional.empty(),
                io.reliabilityai.gateway.dataplane.memory.domain.EffectiveMemoryPolicy
                    .VersioningMode.APPEND,
                Optional.empty(),
                io.reliabilityai.gateway.dataplane.memory.api.DataClassification.SECRET,
                true)));
  }

  @AfterEach
  void tearDown() {
    if (batcher != null) {
      batcher.close();
    }
  }

  /** Governance that admits everything, so this file measures the embedding path and not policy. */
  private static final MemoryGovernancePort PERMISSIVE_MEMORY_GOVERNANCE =
      new MemoryGovernancePort() {
        @Override
        public Decision admitWrite(
            final PrincipalId principal,
            final MemoryScope scope,
            final MemoryType type,
            final io.reliabilityai.gateway.dataplane.memory.api.DataClassification classification,
            final int bodyLength) {
          return Decision.ALLOWED;
        }

        @Override
        public Decision admitRead(
            final PrincipalId principal,
            final MemoryScope scope,
            final java.util.Set<MemoryType> types,
            final io.reliabilityai.gateway.dataplane.memory.api.RetrievalMode mode) {
          return Decision.ALLOWED;
        }

        @Override
        public Decision admitDelete(
            final PrincipalId principal, final MemoryScope scope, final MemoryType type) {
          return Decision.ALLOWED;
        }
      };

  /**
   * Builds a Memory Runtime whose {@code EmbeddingPort} is the real B25 pipeline.
   *
   * @param provider the embedding provider to place behind the pipeline
   * @param tenant the single tenant this bridge is constructed for — see the last test
   * @return the assembled runtime
   */
  private MemoryRuntime runtimeOver(
      final io.reliabilityai.gateway.dataplane.embedding.api.EmbeddingProviderPort provider,
      final TenantScope tenant) {
    final EmbeddingProviderRegistry registry =
        new EmbeddingProviderRegistry(EmbeddingFailurePolicy.defaults(), clock);
    registry.register(provider);
    cache =
        new EmbeddingCache(
            clock, embeddingMetrics, Duration.ofHours(1), Duration.ofMinutes(10), 10_000);
    final EmbeddingPipeline pipeline =
        new EmbeddingPipeline(
            registry,
            cache,
            EmbeddingGovernancePort.PERMISSIVE,
            EmbeddingRetryPolicy.immediate(2),
            embeddingMetrics,
            EmbeddingAuditPort.NOOP);
    batcher =
        new EmbeddingBatcher(
            32, 1_000_000, Duration.ofMillis(20), 1_000, embeddingMetrics, pipeline::embed);
    final EmbeddingPort port = new PipelineEmbeddingPort(batcher, tenant, MODEL, 10_000L);

    final MemoryEmbedder embedder = new MemoryEmbedder(port);
    final MemoryWritePipeline writes =
        new MemoryWritePipeline(
            policies,
            PERMISSIVE_MEMORY_GOVERNANCE,
            new ConservativePiiClassifier(),
            new NotRealCryptoSealer(),
            store,
            index,
            embedder,
            MemoryAuditPort.NOOP,
            memoryMetrics,
            clock,
            MemoryIdFactory.DETERMINISTIC);
    final MemoryReadPipeline reads =
        new MemoryReadPipeline(
            policies,
            PERMISSIVE_MEMORY_GOVERNANCE,
            store,
            index,
            embedder,
            new NotRealCryptoSealer(),
            new MemoryRanker(
                io.reliabilityai.gateway.dataplane.memory.api.RankingSignal.Weights.DEFAULT),
            MemoryAuditPort.NOOP,
            memoryMetrics,
            clock);
    return new MemoryRuntime(
        writes,
        reads,
        policies,
        PERMISSIVE_MEMORY_GOVERNANCE,
        store,
        index,
        MemoryAuditPort.NOOP,
        memoryMetrics,
        clock);
  }

  private static MemoryCaller caller(final TenantScope tenant) {
    return MemoryCaller.of(
        new PrincipalId("writer"),
        MemoryScope.ofTenant(tenant),
        new io.reliabilityai.gateway.canonical.identity.CorrelationId("corr-1"));
  }

  private static MemoryWriteRequest semantic(final TenantScope tenant, final String body) {
    return MemoryWriteRequest.of(
        MemoryScope.ofTenant(tenant), MemoryType.SEMANTIC, body, body, "eu-west-1");
  }

  @Test
  @Timeout(60)
  @DisplayName("a semantic write reaches a provider through EmbeddingPort and indexes")
  void aSemanticWriteReachesAProviderThroughEmbeddingPortAndIndexes() {
    final EmbeddingFixtures.ConcurrentProvider provider =
        new EmbeddingFixtures.ConcurrentProvider("p", clock, 32);
    final MemoryRuntime runtime = runtimeOver(provider, ACME);

    final MemoryOutcome outcome = runtime.write(caller(ACME), semantic(ACME, "the sky is blue"));

    // The whole chain: MemoryWritePipeline -> MemoryEmbedder -> EmbeddingPort ->
    // PipelineEmbeddingPort -> EmbeddingBatcher -> EmbeddingPipeline -> provider.
    assertThat(outcome).isInstanceOf(MemoryOutcome.Written.class);
    assertThat(((MemoryOutcome.Written) outcome).indexPending())
        .as("the vector reached the index, so nothing is outstanding")
        .isFalse();
    assertThat(provider.calls()).isPositive();
    assertThat(embeddingMetrics.count("cache.miss")).isPositive();
  }

  @Test
  @Timeout(60)
  @DisplayName("a non-semantic write never calls a provider")
  void aNonSemanticWriteNeverCallsAProvider() {
    final EmbeddingFixtures.ConcurrentProvider provider =
        new EmbeddingFixtures.ConcurrentProvider("p", clock, 32);
    final MemoryRuntime runtime = runtimeOver(provider, ACME);

    runtime.write(
        caller(ACME),
        MemoryWriteRequest.of(
            MemoryScope.ofTenant(ACME), MemoryType.SESSION, "a plain fact", "k-fact", "eu-west-1"));

    // MemoryType.requiresEmbedding() is true only for SEMANTIC. Embedding every type would pay a
    // provider for records nothing will ever search semantically.
    assertThat(provider.calls()).isZero();
  }

  @Test
  @Timeout(60)
  @DisplayName("the same content written twice is embedded once")
  void theSameContentWrittenTwiceIsEmbeddedOnce() {
    final EmbeddingFixtures.ConcurrentProvider provider =
        new EmbeddingFixtures.ConcurrentProvider("p", clock, 32);
    final MemoryRuntime runtime = runtimeOver(provider, ACME);

    runtime.write(caller(ACME), semantic(ACME, "repeated content"));
    final int afterFirst = provider.inputs();
    runtime.write(caller(ACME), semantic(ACME, "repeated content"));

    // Either the store deduplicated the record, or the embedding cache served the second write.
    // Both are correct; paying a provider twice for identical text is not.
    assertThat(provider.inputs()).isEqualTo(afterFirst);
  }

  @Test
  @Timeout(60)
  @DisplayName("a provider outage does not lose the memory, only its searchability")
  void aProviderOutageDoesNotLoseTheMemoryOnlyItsSearchability() {
    final EmbeddingFixtures.ScriptedProvider provider =
        new EmbeddingFixtures.ScriptedProvider("p", clock, 32)
            .then(
                new EmbeddingFixtures.ScriptedProvider.Fail(
                    io.reliabilityai.gateway.dataplane.embedding.api.EmbeddingFailure.UNAVAILABLE))
            .then(
                new EmbeddingFixtures.ScriptedProvider.Fail(
                    io.reliabilityai.gateway.dataplane.embedding.api.EmbeddingFailure.UNAVAILABLE));
    final MemoryRuntime runtime = runtimeOver(provider, ACME);

    final MemoryOutcome outcome =
        runtime.write(caller(ACME), semantic(ACME, "survives the outage"));

    // The governing rule: a provider outage must never become a memory write failure. The record is
    // durable and keyword-visible; only its semantic findability is outstanding.
    assertThat(outcome).isInstanceOf(MemoryOutcome.Written.class);
    assertThat(((MemoryOutcome.Written) outcome).indexPending())
        .as("the record survives, flagged as not yet indexed")
        .isTrue();
  }

  @Test
  @Timeout(60)
  @DisplayName("a record left index-pending is never re-indexed, and nothing owns that recovery")
  void aRecordLeftIndexPendingIsNeverReIndexed() {
    final EmbeddingFixtures.ScriptedProvider provider =
        new EmbeddingFixtures.ScriptedProvider("p", clock, 32)
            .then(
                new EmbeddingFixtures.ScriptedProvider.Fail(
                    io.reliabilityai.gateway.dataplane.embedding.api.EmbeddingFailure.UNAVAILABLE))
            .then(
                new EmbeddingFixtures.ScriptedProvider.Fail(
                    io.reliabilityai.gateway.dataplane.embedding.api.EmbeddingFailure.UNAVAILABLE));
    final MemoryRuntime runtime = runtimeOver(provider, ACME);
    runtime.write(caller(ACME), semantic(ACME, "stranded"));

    // The provider recovers. Nothing notices.
    clock.advance(Duration.ofHours(1));

    // MemoryWritePipeline says the record is "marked for the sweeper to reconcile", and
    // MemoryRecord.indexed() exists to clear the flag — but no caller of it exists anywhere in the
    // repository, and MemoryLifecycleSweeper only ever REMOVES index entries. A transient provider
    // blip therefore costs that record its semantic findability permanently.
    //
    // This test pins the gap so that building the reconciler makes it fail and forces an update.
    // It is B67, and it is a blocker for activation rather than a defect in B25.
    assertThat(index.size()).as("nothing re-indexes a record whose embedding failed").isZero();
  }

  @Test
  @Timeout(60)
  @DisplayName("BLOCKER B65: one shared bridge collapses every tenant into one cache namespace")
  void oneSharedBridgeCollapsesEveryTenantIntoOneCacheNamespace() {
    final EmbeddingFixtures.ConcurrentProvider provider =
        new EmbeddingFixtures.ConcurrentProvider("p", clock, 32);
    // A single MemoryRuntime serving many tenants, which is what MemoryRuntime is built to be: its
    // write() takes the scope per call. But EmbeddingPort is float[] embed(String) — no scope — so
    // the bridge behind it must fix ONE tenant at construction.
    final MemoryRuntime runtime = runtimeOver(provider, ACME);

    runtime.write(caller(ACME), semantic(ACME, "identical text"));
    runtime.write(caller(GLOBEX), semantic(GLOBEX, "identical text"));

    // Both tenants' content was cached under ACME's key, because the bridge cannot know which
    // tenant
    // is writing. The vectors are correct and no data crosses a boundary — but the embedding cache
    // has become a single shared namespace, and a cache hit is observable through the roughly
    // tenfold latency gap between a hit and a miss.
    //
    // This is why B26 cannot wire a single shared MemoryRuntime. Closing it requires MemoryScope to
    // reach EmbeddingPort, which is a Memory Runtime contract change (B65), or one runtime instance
    // per tenant, which MemoryRuntime is not shaped for.
    //
    // Asserted as it behaves, deliberately: when EmbeddingPort gains a scope this test fails and
    // sends the reader to the ADR rather than quietly passing.
    assertThat(cache.size())
        .as("two tenants, one cache entry — the blocker this milestone stops on")
        .isEqualTo(1);
  }
}

package io.reliabilityai.gateway.dataplane.memory.store.journal;

import static io.reliabilityai.gateway.dataplane.memory.store.journal.StoreFixtures.OTHER_TENANT;
import static io.reliabilityai.gateway.dataplane.memory.store.journal.StoreFixtures.T0;
import static io.reliabilityai.gateway.dataplane.memory.store.journal.StoreFixtures.TENANT;
import static org.assertj.core.api.Assertions.assertThat;

import io.reliabilityai.gateway.canonical.identity.CorrelationId;
import io.reliabilityai.gateway.canonical.identity.PrincipalId;
import io.reliabilityai.gateway.dataplane.memory.api.DataClassification;
import io.reliabilityai.gateway.dataplane.memory.api.MemoryAuditPort;
import io.reliabilityai.gateway.dataplane.memory.api.MemoryCaller;
import io.reliabilityai.gateway.dataplane.memory.api.MemoryCryptoPort;
import io.reliabilityai.gateway.dataplane.memory.api.MemoryGovernancePort;
import io.reliabilityai.gateway.dataplane.memory.api.MemoryOutcome;
import io.reliabilityai.gateway.dataplane.memory.api.MemoryQuery;
import io.reliabilityai.gateway.dataplane.memory.api.MemoryScope;
import io.reliabilityai.gateway.dataplane.memory.api.MemoryType;
import io.reliabilityai.gateway.dataplane.memory.api.MemoryWriteRequest;
import io.reliabilityai.gateway.dataplane.memory.api.PiiAction;
import io.reliabilityai.gateway.dataplane.memory.api.RankingSignal;
import io.reliabilityai.gateway.dataplane.memory.api.RetrievalMode;
import io.reliabilityai.gateway.dataplane.memory.application.MemoryEmbedder;
import io.reliabilityai.gateway.dataplane.memory.application.MemoryIdFactory;
import io.reliabilityai.gateway.dataplane.memory.application.MemoryLifecycleSweeper;
import io.reliabilityai.gateway.dataplane.memory.application.MemoryReadPipeline;
import io.reliabilityai.gateway.dataplane.memory.application.MemoryRuntime;
import io.reliabilityai.gateway.dataplane.memory.application.MemoryWritePipeline;
import io.reliabilityai.gateway.dataplane.memory.domain.EffectiveMemoryPolicy;
import io.reliabilityai.gateway.dataplane.memory.domain.EffectiveMemoryPolicy.VersioningMode;
import io.reliabilityai.gateway.dataplane.memory.domain.MemoryPolicySnapshot;
import io.reliabilityai.gateway.dataplane.memory.domain.MemoryRanker;
import io.reliabilityai.gateway.dataplane.memory.internal.ConservativePiiClassifier;
import io.reliabilityai.gateway.dataplane.memory.internal.InMemoryVectorIndex;
import io.reliabilityai.gateway.dataplane.memory.internal.InProcessMemoryMetrics;
import io.reliabilityai.gateway.dataplane.memory.internal.MemoryPolicyStore;
import io.reliabilityai.gateway.dataplane.memory.internal.NotRealCryptoSealer;
import io.reliabilityai.gateway.ports.ClockPort;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.DisplayNameGeneration;
import org.junit.jupiter.api.DisplayNameGenerator;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The whole Memory Runtime driven over the durable adapter.
 *
 * <p>The conformance suite proves the adapter satisfies the port. This proves the <em>runtime</em>
 * behaves identically when the port is satisfied durably — that policy, classification, sealing,
 * ranking and the sweeper all work unchanged, and that what they produced is still there after a
 * restart.
 *
 * <p><b>Not one line of C17 was modified for this.</b> The runtime is assembled here exactly as it
 * is in its own tests, with one constructor argument different.
 */
@DisplayNameGeneration(DisplayNameGenerator.ReplaceUnderscores.class)
class MemoryRuntimeOverJournalTest {

  /** A clock the test drives by hand. */
  private static final class TestClock implements ClockPort {
    private final AtomicReference<Instant> now = new AtomicReference<>(T0);

    @Override
    public Instant now() {
      return now.get();
    }

    void advance(final Duration by) {
      now.updateAndGet(instant -> instant.plus(by));
    }
  }

  /** Governance that permits everything, so the test is about storage rather than about policy. */
  private static final class PermissiveGovernance implements MemoryGovernancePort {
    @Override
    public Decision admitWrite(
        final PrincipalId principal,
        final MemoryScope scope,
        final MemoryType type,
        final DataClassification classification,
        final int sizeBytes) {
      return Decision.ALLOWED;
    }

    @Override
    public Decision admitRead(
        final PrincipalId principal,
        final MemoryScope scope,
        final Set<MemoryType> types,
        final RetrievalMode mode) {
      return Decision.ALLOWED;
    }

    @Override
    public Decision admitDelete(
        final PrincipalId principal, final MemoryScope scope, final MemoryType type) {
      return Decision.ALLOWED;
    }
  }

  private final TestClock clock = new TestClock();
  private final InProcessMemoryMetrics metrics = new InProcessMemoryMetrics();
  private final InMemoryVectorIndex index = new InMemoryVectorIndex();

  private static EffectiveMemoryPolicy policy(final Duration ttl, final boolean encryption) {
    return new EffectiveMemoryPolicy(
        Optional.ofNullable(ttl),
        Optional.empty(),
        PiiAction.REDACT,
        Set.of("eu-west-1"),
        encryption,
        false,
        true,
        Optional.empty(),
        VersioningMode.APPEND,
        Optional.empty(),
        DataClassification.SENSITIVE_PII,
        true);
  }

  private MemoryRuntime runtimeOver(
      final JournalMemoryStore store, final MemoryPolicyStore policies) {
    final MemoryEmbedder embedder =
        new MemoryEmbedder(
            text -> {
              final float[] vector = new float[8];
              for (int i = 0; i < text.length(); i++) {
                vector[text.charAt(i) % 8] += 1.0f;
              }
              return vector;
            });
    final MemoryGovernancePort governance = new PermissiveGovernance();
    final MemoryCryptoPort crypto = new NotRealCryptoSealer();
    return new MemoryRuntime(
        new MemoryWritePipeline(
            policies,
            governance,
            new ConservativePiiClassifier(),
            crypto,
            store,
            index,
            embedder,
            MemoryAuditPort.NOOP,
            metrics,
            clock,
            MemoryIdFactory.DETERMINISTIC),
        new MemoryReadPipeline(
            policies,
            governance,
            store,
            index,
            embedder,
            crypto,
            new MemoryRanker(RankingSignal.Weights.DEFAULT),
            MemoryAuditPort.NOOP,
            metrics,
            clock),
        policies,
        governance,
        store,
        index,
        MemoryAuditPort.NOOP,
        metrics,
        clock);
  }

  private static MemoryPolicyStore policiesWith(final EffectiveMemoryPolicy policy) {
    final MemoryPolicyStore store = new MemoryPolicyStore(4);
    store.install(new MemoryPolicySnapshot(1L, Map.of(), Map.of(), policy));
    return store;
  }

  private static MemoryCaller caller() {
    return MemoryCaller.of(
        new PrincipalId("p-1"), MemoryScope.ofTenant(TENANT), new CorrelationId("corr-1"));
  }

  private static MemoryWriteRequest write(
      final MemoryType type, final String body, final String key) {
    return MemoryWriteRequest.of(MemoryScope.ofTenant(TENANT), type, body, key, "eu-west-1");
  }

  @Test
  void aWrittenMemoryIsReadableThroughTheFullPipeline(@TempDir final Path root) {
    final MemoryPolicyStore policies = policiesWith(policy(Duration.ofDays(30), false));
    try (JournalMemoryStore store = new JournalMemoryStore(root)) {
      final MemoryRuntime runtime = runtimeOver(store, policies);
      assertThat(runtime.write(caller(), write(MemoryType.LONG_TERM, "the quick fox", "k1")))
          .isInstanceOf(MemoryOutcome.Written.class);

      final MemoryOutcome read =
          runtime.read(
              caller(),
              MemoryQuery.ofKeyword(MemoryScope.ofTenant(TENANT), MemoryType.LONG_TERM, "fox"));
      assertThat(((MemoryOutcome.Retrieved) read).hits()).hasSize(1);
    }
  }

  @Test
  void memoriesWrittenThroughThePipelineSurviveARestart(@TempDir final Path root) {
    final MemoryPolicyStore policies = policiesWith(policy(Duration.ofDays(30), false));
    try (JournalMemoryStore store = new JournalMemoryStore(root)) {
      final MemoryRuntime runtime = runtimeOver(store, policies);
      for (int i = 0; i < 25; i++) {
        runtime.write(caller(), write(MemoryType.LONG_TERM, "body number " + i, "k" + i));
      }
    }
    try (JournalMemoryStore reopened = new JournalMemoryStore(root)) {
      final MemoryRuntime runtime = runtimeOver(reopened, policies);
      final MemoryOutcome read =
          runtime.read(
              caller(),
              new MemoryQuery(
                  MemoryScope.ofTenant(TENANT),
                  java.util.EnumSet.of(MemoryType.LONG_TERM),
                  RetrievalMode.SCOPE,
                  Optional.empty(),
                  Optional.empty(),
                  Map.of(),
                  Optional.empty(),
                  Optional.empty(),
                  100,
                  false));
      assertThat(((MemoryOutcome.Retrieved) read).hits()).hasSize(25);
    }
  }

  @Test
  void redactionAppliedByThePipelineIsWhatEndsUpOnDisk(@TempDir final Path root) {
    // The runtime redacts before the adapter ever sees the body, so the adapter cannot un-redact
    // and
    // the plaintext never reaches storage.
    final MemoryPolicyStore policies = policiesWith(policy(Duration.ofDays(30), false));
    try (JournalMemoryStore store = new JournalMemoryStore(root)) {
      final MemoryRuntime runtime = runtimeOver(store, policies);
      runtime.write(caller(), write(MemoryType.LONG_TERM, "reach me at alice@example.com", "k1"));
    }
    try (JournalMemoryStore reopened = new JournalMemoryStore(root)) {
      final MemoryRuntime runtime = runtimeOver(reopened, policies);
      final MemoryOutcome read =
          runtime.read(
              caller(), MemoryQuery.ofScope(MemoryScope.ofTenant(TENANT), MemoryType.LONG_TERM));
      final String body = ((MemoryOutcome.Retrieved) read).hits().get(0).record().content().body();
      assertThat(body).doesNotContain("alice@example.com").contains("[redacted]");
    }
  }

  @Test
  void sealedContentSurvivesARestartAndIsStillOpenedOnTheWayOut(@TempDir final Path root) {
    final MemoryPolicyStore policies = policiesWith(policy(Duration.ofDays(30), true));
    try (JournalMemoryStore store = new JournalMemoryStore(root)) {
      final MemoryRuntime runtime = runtimeOver(store, policies);
      final MemoryOutcome written =
          runtime.write(caller(), write(MemoryType.LONG_TERM, "round trip me", "k1"));
      assertThat(((MemoryOutcome.Written) written).sealed()).isTrue();
    }
    try (JournalMemoryStore reopened = new JournalMemoryStore(root)) {
      final MemoryRuntime runtime = runtimeOver(reopened, policies);
      final MemoryOutcome read =
          runtime.read(
              caller(), MemoryQuery.ofScope(MemoryScope.ofTenant(TENANT), MemoryType.LONG_TERM));
      assertThat(((MemoryOutcome.Retrieved) read).hits().get(0).record().content().body())
          .isEqualTo("round trip me");
    }
  }

  @Test
  void deletionThroughTheRuntimeProducesAProofAndRemovesTheRecordDurably(@TempDir final Path root) {
    final MemoryPolicyStore policies = policiesWith(policy(Duration.ofDays(30), false));
    final var id =
        new java.util.concurrent.atomic.AtomicReference<
            io.reliabilityai.gateway.dataplane.memory.api.MemoryRecordId>();
    try (JournalMemoryStore store = new JournalMemoryStore(root)) {
      final MemoryRuntime runtime = runtimeOver(store, policies);
      id.set(
          ((MemoryOutcome.Written)
                  runtime.write(caller(), write(MemoryType.LONG_TERM, "to be forgotten", "k1")))
              .id());
      final MemoryOutcome deleted =
          runtime.delete(caller(), MemoryScope.ofTenant(TENANT), id.get(), "erasure.request");
      assertThat(deleted).isInstanceOf(MemoryOutcome.Deleted.class);
      assertThat(MemoryRuntime.verifyProof(((MemoryOutcome.Deleted) deleted).proof())).isTrue();
    }
    try (JournalMemoryStore reopened = new JournalMemoryStore(root)) {
      assertThat(reopened.get(MemoryScope.ofTenant(TENANT), id.get())).isEmpty();
    }
  }

  @Test
  void theSweeperExpiresRecordsAndTheExpiryIsDurable(@TempDir final Path root) {
    final MemoryPolicyStore policies = policiesWith(policy(Duration.ofHours(1), false));
    try (JournalMemoryStore store = new JournalMemoryStore(root)) {
      final MemoryRuntime runtime = runtimeOver(store, policies);
      runtime.write(caller(), write(MemoryType.SESSION, "transient", "k1"));
      clock.advance(Duration.ofHours(2));

      final MemoryLifecycleSweeper sweeper =
          new MemoryLifecycleSweeper(
              store,
              index,
              policies,
              MemoryAuditPort.NOOP,
              metrics,
              clock,
              new PrincipalId("sweeper"),
              new CorrelationId("sweep-1"));
      assertThat(sweeper.sweep(100).expired()).isEqualTo(1);
    }
    try (JournalMemoryStore reopened = new JournalMemoryStore(root)) {
      assertThat(reopened.size()).isZero();
    }
  }

  @Test
  void tenantIsolationHoldsThroughTheFullPipelineOverDurableStorage(@TempDir final Path root) {
    final MemoryPolicyStore policies = policiesWith(policy(Duration.ofDays(30), false));
    try (JournalMemoryStore store = new JournalMemoryStore(root)) {
      final MemoryRuntime runtime = runtimeOver(store, policies);
      final MemoryCaller theirs =
          MemoryCaller.of(
              new PrincipalId("p-2"),
              MemoryScope.ofTenant(OTHER_TENANT),
              new CorrelationId("corr-2"));

      runtime.write(caller(), write(MemoryType.LONG_TERM, "mine", "k1"));
      runtime.write(
          theirs,
          MemoryWriteRequest.of(
              MemoryScope.ofTenant(OTHER_TENANT),
              MemoryType.LONG_TERM,
              "theirs",
              "k2",
              "eu-west-1"));

      final MemoryOutcome mine =
          runtime.read(
              caller(), MemoryQuery.ofScope(MemoryScope.ofTenant(TENANT), MemoryType.LONG_TERM));
      assertThat(((MemoryOutcome.Retrieved) mine).hits()).hasSize(1);
      assertThat(((MemoryOutcome.Retrieved) mine).hits().get(0).record().content().body())
          .isEqualTo("mine");
    }
  }

  @Test
  void idempotentWritesThroughTheRuntimeStayIdempotentAcrossARestart(@TempDir final Path root) {
    final MemoryPolicyStore policies = policiesWith(policy(Duration.ofDays(30), false));
    try (JournalMemoryStore store = new JournalMemoryStore(root)) {
      runtimeOver(store, policies).write(caller(), write(MemoryType.SESSION, "once", "same-key"));
    }
    try (JournalMemoryStore reopened = new JournalMemoryStore(root)) {
      final MemoryOutcome second =
          runtimeOver(reopened, policies)
              .write(caller(), write(MemoryType.SESSION, "once", "same-key"));
      assertThat(((MemoryOutcome.Written) second).deduplicated()).isTrue();
      assertThat(reopened.size()).isEqualTo(1);
    }
  }

  @Test
  void anUnavailableDurableStoreMakesTheRuntimeFailClosedRatherThanReturnNothing(
      @TempDir final Path root) {
    final MemoryPolicyStore policies = policiesWith(policy(Duration.ofDays(30), false));
    try (JournalMemoryStore store = new JournalMemoryStore(root)) {
      final MemoryRuntime runtime = runtimeOver(store, policies);
      runtime.write(caller(), write(MemoryType.LONG_TERM, "x", "k1"));
      store.setAvailable(false);

      final MemoryOutcome read =
          runtime.read(
              caller(), MemoryQuery.ofScope(MemoryScope.ofTenant(TENANT), MemoryType.LONG_TERM));
      assertThat(read).isInstanceOf(MemoryOutcome.Refused.class);
      assertThat(((MemoryOutcome.Refused) read).code()).isEqualTo("store.unavailable");
      store.setAvailable(true);
    }
  }

  @Test
  void rankingSignalsSurviveARestartBecauseAccessCountsAreDurable(@TempDir final Path root) {
    final MemoryPolicyStore policies = policiesWith(policy(Duration.ofDays(30), false));
    try (JournalMemoryStore store = new JournalMemoryStore(root)) {
      final MemoryRuntime runtime = runtimeOver(store, policies);
      runtime.write(caller(), write(MemoryType.LONG_TERM, "popular", "k1"));
      for (int i = 0; i < 5; i++) {
        runtime.read(
            caller(), MemoryQuery.ofScope(MemoryScope.ofTenant(TENANT), MemoryType.LONG_TERM));
      }
      store.flush();
    }
    try (JournalMemoryStore reopened = new JournalMemoryStore(root)) {
      final MemoryOutcome read =
          runtimeOver(reopened, policies)
              .read(
                  caller(),
                  MemoryQuery.ofScope(MemoryScope.ofTenant(TENANT), MemoryType.LONG_TERM));
      assertThat(((MemoryOutcome.Retrieved) read).hits().get(0).record().accessCount())
          .isGreaterThanOrEqualTo(5L);
    }
  }
}

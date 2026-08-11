package io.reliabilityai.gateway.dataplane.memory.application;

import static io.reliabilityai.gateway.dataplane.memory.MemoryFixtures.OTHER_REGION;
import static io.reliabilityai.gateway.dataplane.memory.MemoryFixtures.OTHER_TENANT;
import static io.reliabilityai.gateway.dataplane.memory.MemoryFixtures.REGION;
import static io.reliabilityai.gateway.dataplane.memory.MemoryFixtures.TENANT;
import static io.reliabilityai.gateway.dataplane.memory.MemoryFixtures.caller;
import static io.reliabilityai.gateway.dataplane.memory.MemoryFixtures.callerFor;
import static io.reliabilityai.gateway.dataplane.memory.MemoryFixtures.callerWithTypes;
import static io.reliabilityai.gateway.dataplane.memory.MemoryFixtures.permissivePolicy;
import static io.reliabilityai.gateway.dataplane.memory.MemoryFixtures.snapshotOf;
import static io.reliabilityai.gateway.dataplane.memory.MemoryFixtures.write;
import static org.assertj.core.api.Assertions.assertThat;

import io.reliabilityai.gateway.canonical.identity.PrincipalId;
import io.reliabilityai.gateway.dataplane.memory.MemoryFixtures.FakeEmbedding;
import io.reliabilityai.gateway.dataplane.memory.MemoryFixtures.FakeGovernance;
import io.reliabilityai.gateway.dataplane.memory.MemoryFixtures.RecordingAudit;
import io.reliabilityai.gateway.dataplane.memory.MemoryFixtures.TestClock;
import io.reliabilityai.gateway.dataplane.memory.api.DataClassification;
import io.reliabilityai.gateway.dataplane.memory.api.MemoryAuditPort.EventType;
import io.reliabilityai.gateway.dataplane.memory.api.MemoryCaller;
import io.reliabilityai.gateway.dataplane.memory.api.MemoryGovernancePort.Decision;
import io.reliabilityai.gateway.dataplane.memory.api.MemoryGovernancePort.Verdict;
import io.reliabilityai.gateway.dataplane.memory.api.MemoryOutcome;
import io.reliabilityai.gateway.dataplane.memory.api.MemoryQuery;
import io.reliabilityai.gateway.dataplane.memory.api.MemoryScope;
import io.reliabilityai.gateway.dataplane.memory.api.MemoryStage;
import io.reliabilityai.gateway.dataplane.memory.api.MemoryType;
import io.reliabilityai.gateway.dataplane.memory.api.MemoryWriteRequest;
import io.reliabilityai.gateway.dataplane.memory.api.PiiAction;
import io.reliabilityai.gateway.dataplane.memory.api.RankingSignal;
import io.reliabilityai.gateway.dataplane.memory.api.RetrievalMode;
import io.reliabilityai.gateway.dataplane.memory.domain.EffectiveMemoryPolicy;
import io.reliabilityai.gateway.dataplane.memory.domain.EffectiveMemoryPolicy.VersioningMode;
import io.reliabilityai.gateway.dataplane.memory.domain.MemoryRanker;
import io.reliabilityai.gateway.dataplane.memory.internal.ConservativePiiClassifier;
import io.reliabilityai.gateway.dataplane.memory.internal.InMemoryMemoryStore;
import io.reliabilityai.gateway.dataplane.memory.internal.InMemoryVectorIndex;
import io.reliabilityai.gateway.dataplane.memory.internal.InProcessMemoryMetrics;
import io.reliabilityai.gateway.dataplane.memory.internal.MemoryPolicyStore;
import io.reliabilityai.gateway.dataplane.memory.internal.NotRealCryptoSealer;
import java.time.Duration;
import java.util.EnumSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayNameGeneration;
import org.junit.jupiter.api.DisplayNameGenerator;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/**
 * The write and read pipelines, end to end against the reference adapters.
 *
 * <p>The assertions that matter most are the ones about <em>order</em> and <em>refusal</em>: that a
 * stage cannot be skipped, that a refusal leaves nothing behind, and that a caller can never reach
 * a scope it is not authorized for no matter how it phrases the request.
 */
@DisplayNameGeneration(DisplayNameGenerator.ReplaceUnderscores.class)
class MemoryPipelineTest {

  private final InMemoryMemoryStore store = new InMemoryMemoryStore();
  private final InMemoryVectorIndex index = new InMemoryVectorIndex();
  private final MemoryPolicyStore policies = new MemoryPolicyStore(4);
  private final FakeGovernance governance = new FakeGovernance();
  private final RecordingAudit audit = new RecordingAudit();
  private final InProcessMemoryMetrics metrics = new InProcessMemoryMetrics();
  private final TestClock clock = new TestClock();
  private final FakeEmbedding embedding = new FakeEmbedding();

  private MemoryRuntime runtime;

  @BeforeEach
  void setUp() {
    policies.install(snapshotOf(permissivePolicy()));
    runtime = build();
  }

  private MemoryRuntime build() {
    final MemoryEmbedder embedder = new MemoryEmbedder(embedding);
    final MemoryWritePipeline writes =
        new MemoryWritePipeline(
            policies,
            governance,
            new ConservativePiiClassifier(),
            new NotRealCryptoSealer(),
            store,
            index,
            embedder,
            audit,
            metrics,
            clock,
            MemoryIdFactory.DETERMINISTIC);
    final MemoryReadPipeline reads =
        new MemoryReadPipeline(
            policies,
            governance,
            store,
            index,
            embedder,
            new NotRealCryptoSealer(),
            new MemoryRanker(RankingSignal.Weights.DEFAULT),
            audit,
            metrics,
            clock);
    return new MemoryRuntime(
        writes, reads, policies, governance, store, index, audit, metrics, clock);
  }

  private void installPolicy(final EffectiveMemoryPolicy policy) {
    policies.install(
        new io.reliabilityai.gateway.dataplane.memory.domain.MemoryPolicySnapshot(
            policies.current().version() + 1, Map.of(), Map.of(), policy));
  }

  private static EffectiveMemoryPolicy policyWith(
      final PiiAction pii,
      final Set<String> regions,
      final boolean encryption,
      final DataClassification ceiling) {
    return new EffectiveMemoryPolicy(
        Optional.of(Duration.ofDays(30)),
        Optional.empty(),
        pii,
        regions,
        encryption,
        false,
        true,
        Optional.empty(),
        VersioningMode.APPEND,
        Optional.empty(),
        ceiling,
        true);
  }

  // ---- Write: the happy path and what it records ------------------------------------------------

  @Test
  void anAdmittedWriteIsStoredAndAudited() {
    final MemoryOutcome outcome =
        runtime.write(
            caller(), write(MemoryScope.ofTenant(TENANT), MemoryType.SESSION, "hello", "k1"));

    assertThat(outcome).isInstanceOf(MemoryOutcome.Written.class);
    assertThat(outcome.succeeded()).isTrue();
    assertThat(store.size()).isEqualTo(1);
    assertThat(audit.countOf(EventType.WRITE)).isEqualTo(1L);
  }

  @Test
  void governanceIsAskedBeforeAnythingIsStored() {
    governance.onWrite(new Decision(Verdict.DENY, "quota.exceeded"));
    final MemoryOutcome outcome =
        runtime.write(caller(), write(MemoryScope.ofTenant(TENANT), MemoryType.SESSION, "x", "k1"));

    assertThat(outcome).isInstanceOf(MemoryOutcome.Refused.class);
    assertThat(((MemoryOutcome.Refused) outcome).stage()).isEqualTo(MemoryStage.GOVERNANCE);
    assertThat(store.size()).isZero();
  }

  @Test
  void anUnenforceableGovernanceVerdictRefusesRatherThanAllowing() {
    // MEM-21. A governance engine that is down must not become an open door.
    governance.onWrite(new Decision(Verdict.UNENFORCEABLE, "engine.down"));
    assertThat(
            runtime.write(
                caller(), write(MemoryScope.ofTenant(TENANT), MemoryType.SESSION, "x", "k1")))
        .isInstanceOf(MemoryOutcome.Refused.class);
    assertThat(metrics.counter("memory.unenforceable")).isEqualTo(1L);
    assertThat(store.size()).isZero();
  }

  @Test
  void anUnenforceableMemoryPolicyRefusesRatherThanAllowing() {
    installPolicy(EffectiveMemoryPolicy.UNENFORCEABLE);
    final MemoryOutcome outcome =
        runtime.write(caller(), write(MemoryScope.ofTenant(TENANT), MemoryType.SESSION, "x", "k1"));
    assertThat(outcome).isInstanceOf(MemoryOutcome.Refused.class);
    assertThat(((MemoryOutcome.Refused) outcome).stage()).isEqualTo(MemoryStage.POLICY);
    assertThat(store.size()).isZero();
  }

  @Test
  void aRefusedWriteLeavesNothingBehindAnywhere() {
    governance.onWrite(new Decision(Verdict.DENY, "denied"));
    runtime.write(caller(), write(MemoryScope.ofTenant(TENANT), MemoryType.SEMANTIC, "x", "k1"));

    assertThat(store.size()).isZero();
    assertThat(index.size()).isZero();
    assertThat(audit.countOf(EventType.WRITE)).isZero();
    assertThat(audit.countOf(EventType.REFUSAL)).isEqualTo(1L);
  }

  // ---- Write: isolation
  // --------------------------------------------------------------------------

  @Test
  void aCallerCannotWriteIntoAnotherTenantByNamingIt() {
    // T2 (cross-tenant write). The scope comes from the authenticated caller, and naming another
    // tenant in the request achieves nothing.
    final MemoryOutcome outcome =
        runtime.write(
            caller(), write(MemoryScope.ofTenant(OTHER_TENANT), MemoryType.SESSION, "x", "k1"));

    assertThat(outcome).isInstanceOf(MemoryOutcome.Refused.class);
    assertThat(((MemoryOutcome.Refused) outcome).code()).isEqualTo("scope.unauthorized");
    assertThat(store.size()).isZero();
  }

  @Test
  void aCallerCannotWriteIntoAnotherPrincipalsPrivateScope() {
    final MemoryCaller alice = callerFor(MemoryScope.ofUser(TENANT, new PrincipalId("alice")));
    final MemoryOutcome outcome =
        runtime.write(
            alice,
            write(
                MemoryScope.ofUser(TENANT, new PrincipalId("bob")),
                MemoryType.LONG_TERM,
                "x",
                "k1"));
    assertThat(outcome).isInstanceOf(MemoryOutcome.Refused.class);
  }

  @Test
  void aCallerCannotUseAMemoryTypeItsRoleDoesNotPermit() {
    // Role isolation, expressed as a capability rather than a row predicate (AD-026 §9.3).
    final MemoryCaller restricted = callerWithTypes(MemoryType.SESSION);
    final MemoryOutcome outcome =
        runtime.write(
            restricted, write(MemoryScope.ofTenant(TENANT), MemoryType.LONG_TERM, "x", "k1"));

    assertThat(outcome).isInstanceOf(MemoryOutcome.Refused.class);
    assertThat(((MemoryOutcome.Refused) outcome).code()).isEqualTo("type.forbidden");
    assertThat(governance.writeCalls()).isZero();
  }

  // ---- Write: policy
  // ------------------------------------------------------------------------------

  @Test
  void aWriteFromAForbiddenRegionIsRefusedBeforeTheAdapterIsChosen() {
    // MEM-22. Writing first and discovering the region is forbidden leaves only a compensating
    // delete, which is not a remedy for data that should never have crossed a border.
    installPolicy(
        policyWith(PiiAction.ALLOW, Set.of(OTHER_REGION), false, DataClassification.SENSITIVE_PII));
    final MemoryOutcome outcome =
        runtime.write(caller(), write(MemoryScope.ofTenant(TENANT), MemoryType.SESSION, "x", "k1"));

    assertThat(outcome).isInstanceOf(MemoryOutcome.Refused.class);
    assertThat(((MemoryOutcome.Refused) outcome).code()).isEqualTo("policy.residency.region");
    assertThat(store.size()).isZero();
  }

  @Test
  void aPolicyPermittingNoRegionAtAllRefusesEveryWrite() {
    installPolicy(policyWith(PiiAction.ALLOW, Set.of(), false, DataClassification.SENSITIVE_PII));
    assertThat(
            runtime.write(
                caller(), write(MemoryScope.ofTenant(TENANT), MemoryType.SESSION, "x", "k1")))
        .isInstanceOf(MemoryOutcome.Refused.class);
  }

  @Test
  void personalDataIsRedactedWhenPolicySaysSo() {
    installPolicy(policyWith(PiiAction.REDACT, Set.of(REGION), false, DataClassification.PII));
    final MemoryOutcome outcome =
        runtime.write(
            caller(),
            write(MemoryScope.ofTenant(TENANT), MemoryType.LONG_TERM, "reach me at a@b.com", "k1"));

    assertThat(outcome).isInstanceOf(MemoryOutcome.Written.class);
    assertThat(((MemoryOutcome.Written) outcome).redacted()).isTrue();

    final MemoryOutcome read =
        runtime.read(
            caller(), MemoryQuery.ofScope(MemoryScope.ofTenant(TENANT), MemoryType.LONG_TERM));
    final String body = ((MemoryOutcome.Retrieved) read).hits().get(0).record().content().body();
    assertThat(body).doesNotContain("a@b.com").contains("[redacted]");
  }

  @Test
  void personalDataIsRefusedWhenPolicySaysSo() {
    installPolicy(policyWith(PiiAction.REFUSE, Set.of(REGION), false, DataClassification.PII));
    final MemoryOutcome outcome =
        runtime.write(
            caller(),
            write(MemoryScope.ofTenant(TENANT), MemoryType.LONG_TERM, "mail a@b.com", "k1"));

    assertThat(outcome).isInstanceOf(MemoryOutcome.Refused.class);
    assertThat(((MemoryOutcome.Refused) outcome).code()).isEqualTo("policy.pii.refused");
    assertThat(store.size()).isZero();
  }

  @Test
  void aCredentialIsNeverStoredWhateverThePolicySays() {
    installPolicy(
        policyWith(PiiAction.ALLOW, Set.of(REGION), false, DataClassification.SENSITIVE_PII));
    final MemoryOutcome outcome =
        runtime.write(
            caller(),
            write(MemoryScope.ofTenant(TENANT), MemoryType.LONG_TERM, "api_key=hunter2", "k1"));

    assertThat(outcome).isInstanceOf(MemoryOutcome.Refused.class);
    assertThat(((MemoryOutcome.Refused) outcome).code()).isEqualTo("classification.forbidden");
    assertThat(store.size()).isZero();
  }

  @Test
  void aClassificationAboveTheScopeCeilingIsRefused() {
    installPolicy(policyWith(PiiAction.ALLOW, Set.of(REGION), false, DataClassification.INTERNAL));
    final MemoryOutcome outcome =
        runtime.write(
            caller(),
            write(MemoryScope.ofTenant(TENANT), MemoryType.LONG_TERM, "email a@b.com", "k1"));
    assertThat(outcome).isInstanceOf(MemoryOutcome.Refused.class);
    assertThat(((MemoryOutcome.Refused) outcome).code()).isEqualTo("policy.classification");
  }

  @Test
  void contentIsSealedAtRestWhenPolicyRequiresIt() {
    installPolicy(
        policyWith(PiiAction.ALLOW, Set.of(REGION), true, DataClassification.SENSITIVE_PII));
    final MemoryOutcome outcome =
        runtime.write(
            caller(),
            write(
                MemoryScope.ofTenant(TENANT),
                MemoryType.LONG_TERM,
                "ordinary business note",
                "k1"));

    assertThat(((MemoryOutcome.Written) outcome).sealed()).isTrue();
    // What is actually at rest is not the plaintext.
    final var stored =
        store.search(MemoryQuery.ofScope(MemoryScope.ofTenant(TENANT), MemoryType.LONG_TERM));
    assertThat(stored.get(0).record().content().sealed()).isTrue();
    assertThat(stored.get(0).record().content().body()).isNotEqualTo("ordinary business note");
    assertThat(stored.get(0).record().content().plaintext()).isEmpty();
  }

  @Test
  void aSealedRecordIsOpenedOnTheWayOut() {
    installPolicy(
        policyWith(PiiAction.ALLOW, Set.of(REGION), true, DataClassification.SENSITIVE_PII));
    runtime.write(
        caller(), write(MemoryScope.ofTenant(TENANT), MemoryType.LONG_TERM, "round trip", "k1"));

    final MemoryOutcome read =
        runtime.read(
            caller(), MemoryQuery.ofScope(MemoryScope.ofTenant(TENANT), MemoryType.LONG_TERM));
    assertThat(((MemoryOutcome.Retrieved) read).hits().get(0).record().content().body())
        .isEqualTo("round trip");
  }

  @Test
  void aTtlIsTakenFromPolicyRatherThanFromTheCaller() {
    final MemoryWriteRequest greedy =
        new MemoryWriteRequest(
            MemoryScope.ofTenant(TENANT),
            MemoryType.SESSION,
            "x",
            Map.of(),
            Optional.of(Duration.ofDays(36_500)),
            0.5d,
            "k1",
            REGION);
    runtime.write(caller(), greedy);

    final var stored =
        store.search(MemoryQuery.ofScope(MemoryScope.ofTenant(TENANT), MemoryType.SESSION));
    assertThat(stored.get(0).record().expiresAt()).contains(clock.now().plus(Duration.ofDays(30)));
  }

  @Test
  void aToolMemoryIsTaintedWhateverTheCallerWants() {
    runtime.write(
        caller(), write(MemoryScope.ofTenant(TENANT), MemoryType.TOOL, "web page text", "k1"));
    final var stored =
        store.search(MemoryQuery.ofScope(MemoryScope.ofTenant(TENANT), MemoryType.TOOL));
    assertThat(stored.get(0).record().tainted()).isTrue();
  }

  @Test
  void theTaintFlagSurvivesOntoTheHitSoACallerCannotConsumeContentWithoutIt() {
    runtime.write(
        caller(), write(MemoryScope.ofTenant(TENANT), MemoryType.TOOL, "untrusted", "k1"));
    final MemoryOutcome read =
        runtime.read(caller(), MemoryQuery.ofScope(MemoryScope.ofTenant(TENANT), MemoryType.TOOL));
    assertThat(((MemoryOutcome.Retrieved) read).hits().get(0).tainted()).isTrue();
  }

  // ---- Write: idempotence
  // -------------------------------------------------------------------------

  @Test
  void twoWritesWithTheSameKeyAreOneWrite() {
    // A retried tool invocation after a lost acknowledgement must not become two memories: a
    // duplicate
    // distorts every ranking signal that counts frequency.
    final MemoryWriteRequest request =
        write(MemoryScope.ofTenant(TENANT), MemoryType.SESSION, "same", "k1");
    runtime.write(caller(), request);
    final MemoryOutcome second = runtime.write(caller(), request);

    assertThat(store.size()).isEqualTo(1);
    assertThat(((MemoryOutcome.Written) second).deduplicated()).isTrue();
  }

  @Test
  void theSameWriteKeyInTwoTenantsIsTwoDifferentWrites() {
    // Idempotence is per scope. Collapsing across tenants would be a cross-tenant leak dressed up
    // as
    // deduplication.
    final MemoryCaller mine = caller();
    final MemoryCaller theirs = callerFor(MemoryScope.ofTenant(OTHER_TENANT));
    runtime.write(mine, write(MemoryScope.ofTenant(TENANT), MemoryType.SESSION, "a", "shared-key"));
    runtime.write(
        theirs, write(MemoryScope.ofTenant(OTHER_TENANT), MemoryType.SESSION, "b", "shared-key"));
    assertThat(store.size()).isEqualTo(2);
  }

  // ---- Write: failure handling
  // ---------------------------------------------------------------------

  @Test
  void anUnreachableStoreRefusesTheWriteRatherThanClaimingDurability() {
    store.setAvailable(false);
    final MemoryOutcome outcome =
        runtime.write(caller(), write(MemoryScope.ofTenant(TENANT), MemoryType.SESSION, "x", "k1"));
    assertThat(outcome).isInstanceOf(MemoryOutcome.Refused.class);
    assertThat(((MemoryOutcome.Refused) outcome).code()).isEqualTo("store.unavailable");
  }

  @Test
  void aWriteSurvivesAnIndexOutageAndIsMarkedForReconciliation() {
    // The record is durable; only the secondary index is missing. Failing the write would throw
    // away
    // a durable record because an index was briefly unavailable (AD-026 §11.2).
    index.setAvailable(false);
    final MemoryOutcome outcome =
        runtime.write(
            caller(), write(MemoryScope.ofTenant(TENANT), MemoryType.SEMANTIC, "x", "k1"));

    assertThat(outcome).isInstanceOf(MemoryOutcome.Written.class);
    assertThat(((MemoryOutcome.Written) outcome).indexPending()).isTrue();
    assertThat(store.size()).isEqualTo(1);
  }

  @Test
  void aFailingAuditSinkDoesNotUndoADurableWrite() {
    audit.exploding(true);
    final MemoryOutcome outcome =
        runtime.write(caller(), write(MemoryScope.ofTenant(TENANT), MemoryType.SESSION, "x", "k1"));
    assertThat(outcome).isInstanceOf(MemoryOutcome.Written.class);
    assertThat(metrics.counter("memory.store_unavailable.audit")).isPositive();
  }

  // ---- Read
  // ----------------------------------------------------------------------------------------

  @Test
  void aReadReturnsOnlyWhatTheCallerMaySee() {
    final MemoryCaller mine = caller();
    final MemoryCaller theirs = callerFor(MemoryScope.ofTenant(OTHER_TENANT));
    runtime.write(mine, write(MemoryScope.ofTenant(TENANT), MemoryType.SESSION, "mine", "k1"));
    runtime.write(
        theirs, write(MemoryScope.ofTenant(OTHER_TENANT), MemoryType.SESSION, "theirs", "k2"));

    final MemoryOutcome outcome =
        runtime.read(mine, MemoryQuery.ofScope(MemoryScope.ofTenant(TENANT), MemoryType.SESSION));
    final var hits = ((MemoryOutcome.Retrieved) outcome).hits();
    assertThat(hits).hasSize(1);
    assertThat(hits.get(0).record().content().body()).isEqualTo("mine");
  }

  @Test
  void aQueryNamingAnotherTenantIsRefusedRatherThanReturningEmpty() {
    // Refused at NARROW, before any adapter is asked (MEM-15).
    final MemoryOutcome outcome =
        runtime.read(
            caller(), MemoryQuery.ofScope(MemoryScope.ofTenant(OTHER_TENANT), MemoryType.SESSION));
    assertThat(outcome).isInstanceOf(MemoryOutcome.Refused.class);
    assertThat(((MemoryOutcome.Refused) outcome).stage()).isEqualTo(MemoryStage.NARROW);
  }

  @Test
  void anEmptyReadIsStillASuccessfulReadAndIsStillAudited() {
    // MEM-12. A miss is precisely the signal that matters when someone is probing.
    final MemoryOutcome outcome =
        runtime.read(
            caller(), MemoryQuery.ofScope(MemoryScope.ofTenant(TENANT), MemoryType.SESSION));
    assertThat(outcome.succeeded()).isTrue();
    assertThat(((MemoryOutcome.Retrieved) outcome).empty()).isTrue();
    assertThat(audit.countOf(EventType.READ)).isEqualTo(1L);
  }

  @Test
  void aRefusedReadIsAudited() {
    governance.onRead(new Decision(Verdict.DENY, "no"));
    runtime.read(caller(), MemoryQuery.ofScope(MemoryScope.ofTenant(TENANT), MemoryType.SESSION));
    assertThat(audit.countOf(EventType.REFUSAL)).isEqualTo(1L);
  }

  @Test
  void anExpiredRecordIsInvisibleEvenBeforeTheSweeperReachesIt() {
    // A late sweeper must not make expired data visible (AD-026 §11).
    runtime.write(caller(), write(MemoryScope.ofTenant(TENANT), MemoryType.SESSION, "x", "k1"));
    clock.advance(Duration.ofDays(31));

    final MemoryOutcome outcome =
        runtime.read(
            caller(), MemoryQuery.ofScope(MemoryScope.ofTenant(TENANT), MemoryType.SESSION));
    assertThat(((MemoryOutcome.Retrieved) outcome).empty()).isTrue();
    // Still in the store — only the sweeper removes it, and it has not run.
    assertThat(store.size()).isEqualTo(1);
  }

  @Test
  void aKeywordQueryFindsWhatItAsksFor() {
    runtime.write(
        caller(), write(MemoryScope.ofTenant(TENANT), MemoryType.LONG_TERM, "the quick fox", "k1"));
    runtime.write(
        caller(), write(MemoryScope.ofTenant(TENANT), MemoryType.LONG_TERM, "unrelated", "k2"));

    final MemoryOutcome outcome =
        runtime.read(
            caller(),
            MemoryQuery.ofKeyword(MemoryScope.ofTenant(TENANT), MemoryType.LONG_TERM, "fox"));
    final var hits = ((MemoryOutcome.Retrieved) outcome).hits();
    assertThat(hits).hasSize(1);
    assertThat(hits.get(0).record().content().body()).contains("fox");
  }

  @Test
  void aSemanticQueryRefusesWhenTheEmbeddingProviderIsDown() {
    // Degrading silently would answer a different question than the one asked.
    runtime.write(
        caller(), write(MemoryScope.ofTenant(TENANT), MemoryType.SEMANTIC, "anything", "k1"));
    embedding.available(false);

    final MemoryOutcome outcome = runtime.read(caller(), semanticQuery());
    assertThat(outcome).isInstanceOf(MemoryOutcome.Refused.class);
    assertThat(((MemoryOutcome.Refused) outcome).code()).isEqualTo("embedding.unavailable");
  }

  @Test
  void aHybridQueryDegradesToKeywordAndSaysSo() {
    runtime.write(
        caller(), write(MemoryScope.ofTenant(TENANT), MemoryType.SEMANTIC, "fox story", "k1"));
    embedding.available(false);

    final MemoryOutcome outcome = runtime.read(caller(), hybridQuery("fox"));
    assertThat(outcome).isInstanceOf(MemoryOutcome.Retrieved.class);
    final MemoryOutcome.Retrieved retrieved = (MemoryOutcome.Retrieved) outcome;
    assertThat(retrieved.degraded()).isTrue();
    assertThat(retrieved.mode()).isEqualTo(RetrievalMode.KEYWORD);
    assertThat(metrics.counter("memory.degraded")).isEqualTo(1L);
  }

  @Test
  void anUnreachableStoreRefusesTheReadRatherThanReturningAPartialResult() {
    runtime.write(caller(), write(MemoryScope.ofTenant(TENANT), MemoryType.SESSION, "x", "k1"));
    store.setAvailable(false);

    final MemoryOutcome outcome =
        runtime.read(
            caller(), MemoryQuery.ofScope(MemoryScope.ofTenant(TENANT), MemoryType.SESSION));
    assertThat(outcome).isInstanceOf(MemoryOutcome.Refused.class);
    assertThat(((MemoryOutcome.Refused) outcome).code()).isEqualTo("store.unavailable");
  }

  @Test
  void aMetadataFilterIsHonoured() {
    runtime.write(
        caller(),
        new MemoryWriteRequest(
            MemoryScope.ofTenant(TENANT),
            MemoryType.TASK,
            "a",
            Map.of("status", "open"),
            Optional.empty(),
            0.5d,
            "k1",
            REGION));
    runtime.write(
        caller(),
        new MemoryWriteRequest(
            MemoryScope.ofTenant(TENANT),
            MemoryType.TASK,
            "b",
            Map.of("status", "closed"),
            Optional.empty(),
            0.5d,
            "k2",
            REGION));

    final MemoryQuery filtered =
        new MemoryQuery(
            MemoryScope.ofTenant(TENANT),
            EnumSet.of(MemoryType.TASK),
            RetrievalMode.METADATA,
            Optional.empty(),
            Optional.empty(),
            Map.of("status", "open"),
            Optional.empty(),
            Optional.empty(),
            20,
            false);
    final var hits = ((MemoryOutcome.Retrieved) runtime.read(caller(), filtered)).hits();
    assertThat(hits).hasSize(1);
    assertThat(hits.get(0).record().content().body()).isEqualTo("a");
  }

  @Test
  void aTimeWindowIsHonoured() {
    runtime.write(
        caller(), write(MemoryScope.ofTenant(TENANT), MemoryType.EPISODIC, "early", "k1"));
    clock.advance(Duration.ofHours(2));
    runtime.write(caller(), write(MemoryScope.ofTenant(TENANT), MemoryType.EPISODIC, "late", "k2"));

    final MemoryQuery window =
        new MemoryQuery(
            MemoryScope.ofTenant(TENANT),
            EnumSet.of(MemoryType.EPISODIC),
            RetrievalMode.TIME,
            Optional.empty(),
            Optional.empty(),
            Map.of(),
            Optional.of(clock.now().minus(Duration.ofHours(1))),
            Optional.empty(),
            20,
            false);
    final var hits = ((MemoryOutcome.Retrieved) runtime.read(caller(), window)).hits();
    assertThat(hits).hasSize(1);
    assertThat(hits.get(0).record().content().body()).isEqualTo("late");
  }

  @ParameterizedTest
  @EnumSource(MemoryType.class)
  void everyMemoryTypeCanBeWrittenAndReadBack(final MemoryType type) {
    runtime.write(
        caller(), write(MemoryScope.ofTenant(TENANT), type, "body for " + type, "k-" + type));
    final MemoryOutcome outcome =
        runtime.read(caller(), MemoryQuery.ofScope(MemoryScope.ofTenant(TENANT), type));
    assertThat(((MemoryOutcome.Retrieved) outcome).hits()).hasSize(1);
  }

  @Test
  void readsRecordAnAccessSoTheUsageSignalsHaveSomethingToWorkWith() {
    runtime.write(caller(), write(MemoryScope.ofTenant(TENANT), MemoryType.LONG_TERM, "x", "k1"));
    runtime.read(caller(), MemoryQuery.ofScope(MemoryScope.ofTenant(TENANT), MemoryType.LONG_TERM));
    runtime.read(caller(), MemoryQuery.ofScope(MemoryScope.ofTenant(TENANT), MemoryType.LONG_TERM));

    final var stored =
        store.search(MemoryQuery.ofScope(MemoryScope.ofTenant(TENANT), MemoryType.LONG_TERM));
    assertThat(stored.get(0).record().accessCount()).isPositive();
  }

  @Test
  void aFailingAuditSinkDoesNotFailAReadTheCallerWasEntitledTo() {
    // AD-026 §11.1's deliberate asymmetry: an observability outage must not become a memory outage.
    runtime.write(caller(), write(MemoryScope.ofTenant(TENANT), MemoryType.SESSION, "x", "k1"));
    audit.exploding(true);

    final MemoryOutcome outcome =
        runtime.read(
            caller(), MemoryQuery.ofScope(MemoryScope.ofTenant(TENANT), MemoryType.SESSION));
    assertThat(outcome).isInstanceOf(MemoryOutcome.Retrieved.class);
    assertThat(((MemoryOutcome.Retrieved) outcome).hits()).hasSize(1);
  }

  @Test
  void everyAuditEventIsContentFree() {
    // MEM-26. The trail names content by digest and never contains it.
    runtime.write(
        caller(),
        write(MemoryScope.ofTenant(TENANT), MemoryType.LONG_TERM, "a very distinctive body", "k1"));
    runtime.read(caller(), MemoryQuery.ofScope(MemoryScope.ofTenant(TENANT), MemoryType.LONG_TERM));

    assertThat(audit.events()).isNotEmpty();
    assertThat(audit.events())
        .allSatisfy(
            event -> assertThat(event.toString()).doesNotContain("a very distinctive body"));
  }

  private static MemoryQuery semanticQuery() {
    return new MemoryQuery(
        MemoryScope.ofTenant(TENANT),
        EnumSet.of(MemoryType.SEMANTIC),
        RetrievalMode.SEMANTIC,
        Optional.of("query"),
        Optional.empty(),
        Map.of(),
        Optional.empty(),
        Optional.empty(),
        20,
        false);
  }

  private static MemoryQuery hybridQuery(final String text) {
    return new MemoryQuery(
        MemoryScope.ofTenant(TENANT),
        EnumSet.of(MemoryType.SEMANTIC),
        RetrievalMode.HYBRID,
        Optional.of(text),
        Optional.empty(),
        Map.of(),
        Optional.empty(),
        Optional.empty(),
        20,
        false);
  }
}

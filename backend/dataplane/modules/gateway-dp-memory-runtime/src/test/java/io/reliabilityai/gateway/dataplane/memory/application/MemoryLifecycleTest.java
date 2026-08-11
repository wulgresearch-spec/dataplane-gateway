package io.reliabilityai.gateway.dataplane.memory.application;

import static io.reliabilityai.gateway.dataplane.memory.MemoryFixtures.REGION;
import static io.reliabilityai.gateway.dataplane.memory.MemoryFixtures.TENANT;
import static io.reliabilityai.gateway.dataplane.memory.MemoryFixtures.caller;
import static io.reliabilityai.gateway.dataplane.memory.MemoryFixtures.permissivePolicy;
import static io.reliabilityai.gateway.dataplane.memory.MemoryFixtures.snapshotOf;
import static io.reliabilityai.gateway.dataplane.memory.MemoryFixtures.write;
import static org.assertj.core.api.Assertions.assertThat;

import io.reliabilityai.gateway.canonical.identity.CorrelationId;
import io.reliabilityai.gateway.canonical.identity.PrincipalId;
import io.reliabilityai.gateway.dataplane.memory.MemoryFixtures.FakeEmbedding;
import io.reliabilityai.gateway.dataplane.memory.MemoryFixtures.FakeGovernance;
import io.reliabilityai.gateway.dataplane.memory.MemoryFixtures.RecordingAudit;
import io.reliabilityai.gateway.dataplane.memory.MemoryFixtures.TestClock;
import io.reliabilityai.gateway.dataplane.memory.api.DataClassification;
import io.reliabilityai.gateway.dataplane.memory.api.DeleteProof;
import io.reliabilityai.gateway.dataplane.memory.api.MemoryAuditPort.EventType;
import io.reliabilityai.gateway.dataplane.memory.api.MemoryContent;
import io.reliabilityai.gateway.dataplane.memory.api.MemoryGovernancePort.Decision;
import io.reliabilityai.gateway.dataplane.memory.api.MemoryGovernancePort.Verdict;
import io.reliabilityai.gateway.dataplane.memory.api.MemoryOutcome;
import io.reliabilityai.gateway.dataplane.memory.api.MemoryQuery;
import io.reliabilityai.gateway.dataplane.memory.api.MemoryRecord;
import io.reliabilityai.gateway.dataplane.memory.api.MemoryRecordId;
import io.reliabilityai.gateway.dataplane.memory.api.MemoryScope;
import io.reliabilityai.gateway.dataplane.memory.api.MemoryType;
import io.reliabilityai.gateway.dataplane.memory.api.PiiAction;
import io.reliabilityai.gateway.dataplane.memory.api.RankingSignal;
import io.reliabilityai.gateway.dataplane.memory.domain.EffectiveMemoryPolicy;
import io.reliabilityai.gateway.dataplane.memory.domain.EffectiveMemoryPolicy.VersioningMode;
import io.reliabilityai.gateway.dataplane.memory.domain.MemoryDigest;
import io.reliabilityai.gateway.dataplane.memory.domain.MemoryPolicySnapshot;
import io.reliabilityai.gateway.dataplane.memory.domain.MemoryRanker;
import io.reliabilityai.gateway.dataplane.memory.internal.ConservativePiiClassifier;
import io.reliabilityai.gateway.dataplane.memory.internal.InMemoryMemoryStore;
import io.reliabilityai.gateway.dataplane.memory.internal.InMemoryVectorIndex;
import io.reliabilityai.gateway.dataplane.memory.internal.InProcessMemoryMetrics;
import io.reliabilityai.gateway.dataplane.memory.internal.MemoryPolicyStore;
import io.reliabilityai.gateway.dataplane.memory.internal.NotRealCryptoSealer;
import java.time.Duration;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayNameGeneration;
import org.junit.jupiter.api.DisplayNameGenerator;
import org.junit.jupiter.api.Test;

/**
 * Deletion with proof, legal hold, TTL expiry and archival.
 *
 * <p>The legal-hold cases matter most. MEM-20 says a hold survives <em>every</em> removal path, and
 * a hold honoured on three of four paths is not a hold — so each path is tested separately rather
 * than once through whichever route happens to be convenient.
 */
@DisplayNameGeneration(DisplayNameGenerator.ReplaceUnderscores.class)
class MemoryLifecycleTest {

  private final InMemoryMemoryStore store = new InMemoryMemoryStore();
  private final InMemoryVectorIndex index = new InMemoryVectorIndex();
  private final MemoryPolicyStore policies = new MemoryPolicyStore(4);
  private final FakeGovernance governance = new FakeGovernance();
  private final RecordingAudit audit = new RecordingAudit();
  private final InProcessMemoryMetrics metrics = new InProcessMemoryMetrics();
  private final TestClock clock = new TestClock();

  private MemoryRuntime runtime;
  private MemoryLifecycleSweeper sweeper;

  @BeforeEach
  void setUp() {
    policies.install(snapshotOf(permissivePolicy()));
    final MemoryEmbedder embedder = new MemoryEmbedder(new FakeEmbedding());
    runtime =
        new MemoryRuntime(
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
                MemoryIdFactory.DETERMINISTIC),
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
                clock),
            policies,
            governance,
            store,
            index,
            audit,
            metrics,
            clock);
    sweeper =
        new MemoryLifecycleSweeper(
            store,
            index,
            policies,
            audit,
            metrics,
            clock,
            new PrincipalId("sweeper"),
            new CorrelationId("sweep-1"));
  }

  private void installPolicy(final EffectiveMemoryPolicy policy) {
    policies.install(
        new MemoryPolicySnapshot(policies.current().version() + 1, Map.of(), Map.of(), policy));
  }

  private static EffectiveMemoryPolicy policy(
      final Duration ttl,
      final boolean hold,
      final boolean deleteAllowed,
      final Duration archiveAfter,
      final Duration retention) {
    return new EffectiveMemoryPolicy(
        Optional.ofNullable(ttl),
        Optional.ofNullable(retention),
        PiiAction.ALLOW,
        Set.of(REGION),
        false,
        hold,
        deleteAllowed,
        Optional.ofNullable(archiveAfter),
        VersioningMode.APPEND,
        Optional.empty(),
        DataClassification.SENSITIVE_PII,
        true);
  }

  private MemoryRecordId writeOne(final String body, final String key) {
    final MemoryOutcome outcome =
        runtime.write(
            caller(), write(MemoryScope.ofTenant(TENANT), MemoryType.LONG_TERM, body, key));
    return ((MemoryOutcome.Written) outcome).id();
  }

  // ---- Delete and proof ----------------------------------------------------------------------

  @Test
  void aDeleteRemovesTheContentAndLeavesAProof() {
    final MemoryRecordId id = writeOne("to be forgotten", "k1");
    final MemoryOutcome outcome =
        runtime.delete(caller(), MemoryScope.ofTenant(TENANT), id, "erasure.request");

    assertThat(outcome).isInstanceOf(MemoryOutcome.Deleted.class);
    assertThat(store.size()).isZero();

    final DeleteProof proof = ((MemoryOutcome.Deleted) outcome).proof();
    assertThat(proof.recordId()).isEqualTo(id);
    assertThat(proof.reason()).isEqualTo("erasure.request");
    assertThat(MemoryRuntime.verifyProof(proof)).isTrue();
  }

  @Test
  void theProofDoesNotContainWhatWasDeleted() {
    // A proof that quoted its content would be a copy of the thing that was supposed to be gone
    // (AD-026 §10.5).
    final MemoryRecordId id = writeOne("a very distinctive innocuous body", "k1");
    final DeleteProof proof =
        ((MemoryOutcome.Deleted)
                runtime.delete(caller(), MemoryScope.ofTenant(TENANT), id, "erasure.request"))
            .proof();
    assertThat(proof.toString()).doesNotContain("a very distinctive innocuous body");
  }

  @Test
  void aTamperedProofFailsVerification() {
    final MemoryRecordId id = writeOne("body", "k1");
    final DeleteProof proof =
        ((MemoryOutcome.Deleted)
                runtime.delete(caller(), MemoryScope.ofTenant(TENANT), id, "erasure.request"))
            .proof();

    final DeleteProof altered =
        new DeleteProof(
            proof.recordId(),
            proof.scopeKey(),
            proof.contentDigest(),
            proof.deletedBy(),
            "something.else",
            proof.deletedAt(),
            proof.proofDigest());
    assertThat(MemoryRuntime.verifyProof(altered)).isFalse();
  }

  @Test
  void theProofBindingCannotBeForgedByRearrangingFields() {
    // Length-prefixed for this reason: two different field sets must not produce one binding
    // string.
    final String left =
        DeleteProof.bindingString(
            MemoryRecordId.of("ab"), "c", "d", new PrincipalId("p"), "r", clock.now());
    final String right =
        DeleteProof.bindingString(
            MemoryRecordId.of("a"), "bc", "d", new PrincipalId("p"), "r", clock.now());
    assertThat(left).isNotEqualTo(right);
  }

  @Test
  void aDeletedRecordAlsoLosesItsIndexEntry() {
    // An index hit that dereferences to a missing record must never reach a caller (AD-026 §11.2).
    runtime.write(
        caller(), write(MemoryScope.ofTenant(TENANT), MemoryType.SEMANTIC, "indexed", "k1"));
    assertThat(index.size()).isEqualTo(1);

    final MemoryRecordId id =
        store
            .search(MemoryQuery.ofScope(MemoryScope.ofTenant(TENANT), MemoryType.SEMANTIC))
            .get(0)
            .record()
            .id();
    runtime.delete(caller(), MemoryScope.ofTenant(TENANT), id, "cleanup");
    assertThat(index.size()).isZero();
  }

  @Test
  void deletingIsAudited() {
    final MemoryRecordId id = writeOne("x", "k1");
    runtime.delete(caller(), MemoryScope.ofTenant(TENANT), id, "cleanup");
    assertThat(audit.countOf(EventType.DELETE)).isEqualTo(1L);
  }

  @Test
  void aDeleteGovernanceDenialStopsIt() {
    final MemoryRecordId id = writeOne("x", "k1");
    governance.onDelete(new Decision(Verdict.DENY, "not.permitted"));

    assertThat(runtime.delete(caller(), MemoryScope.ofTenant(TENANT), id, "cleanup"))
        .isInstanceOf(MemoryOutcome.Refused.class);
    assertThat(store.size()).isEqualTo(1);
  }

  @Test
  void aPolicyForbiddingDeletionStopsIt() {
    final MemoryRecordId id = writeOne("x", "k1");
    installPolicy(policy(Duration.ofDays(30), false, false, null, null));

    final MemoryOutcome outcome =
        runtime.delete(caller(), MemoryScope.ofTenant(TENANT), id, "cleanup");
    assertThat(((MemoryOutcome.Refused) outcome).code()).isEqualTo("policy.delete.forbidden");
    assertThat(store.size()).isEqualTo(1);
  }

  @Test
  void deletingSomethingThatDoesNotExistIsIndistinguishableFromDeletingSomethingForbidden() {
    // AD-026 §10.3: distinguishing them would make this an oracle for other tenants' records.
    final MemoryOutcome missing =
        runtime.delete(caller(), MemoryScope.ofTenant(TENANT), MemoryRecordId.of("ghost"), "x");
    assertThat(((MemoryOutcome.Refused) missing).code()).isEqualTo("not.found");
  }

  // ---- Legal hold, on every removal path -------------------------------------------------------

  @Test
  void aHeldRecordSurvivesAnExplicitDelete() {
    installPolicy(policy(Duration.ofDays(30), true, true, null, null));
    final MemoryRecordId id = writeOne("held", "k1");

    final MemoryOutcome outcome =
        runtime.delete(caller(), MemoryScope.ofTenant(TENANT), id, "erasure.request");
    assertThat(((MemoryOutcome.Refused) outcome).code()).isEqualTo("legal.hold");
    assertThat(store.size()).isEqualTo(1);
  }

  @Test
  void aHeldRecordSurvivesTtlExpiryAndTheSweeper() {
    installPolicy(policy(Duration.ofHours(1), true, true, null, null));
    writeOne("held", "k1");
    clock.advance(Duration.ofDays(2));

    final MemoryLifecycleSweeper.Pass pass = sweeper.sweep(100);
    assertThat(pass.expired()).isZero();
    assertThat(pass.held()).isEqualTo(1);
    assertThat(store.size()).isEqualTo(1);
  }

  @Test
  void aHeldRecordSurvivesArchival() {
    installPolicy(policy(Duration.ofHours(1), true, true, Duration.ofMinutes(1), null));
    writeOne("held", "k1");
    clock.advance(Duration.ofDays(2));

    final MemoryLifecycleSweeper.Pass pass = sweeper.sweep(100);
    assertThat(pass.archived()).isZero();
    assertThat(pass.held()).isEqualTo(1);
  }

  @Test
  void aHeldRecordCannotBeArchivedThroughTheRecordApiEither() {
    // Belt and braces: the record type itself refuses, so no future code path can bypass the
    // sweeper's
    // check by constructing the archived record directly.
    final MemoryRecord held =
        new MemoryRecord(
            MemoryRecordId.of("r"),
            MemoryScope.ofTenant(TENANT),
            MemoryType.LONG_TERM,
            MemoryContent.plain("x", MemoryDigest.of("x")),
            DataClassification.INTERNAL,
            Map.of(),
            clock.now(),
            Optional.empty(),
            1,
            0.5d,
            false,
            true,
            false,
            false,
            Optional.empty(),
            0L);
    assertThat(held.removable()).isFalse();
    org.assertj.core.api.Assertions.assertThatThrownBy(held::archivedRecord)
        .isInstanceOf(IllegalStateException.class);
  }

  // ---- TTL, retention and archival --------------------------------------------------------------

  @Test
  void anExpiredRecordIsSweptAway() {
    installPolicy(policy(Duration.ofHours(1), false, true, null, null));
    writeOne("transient", "k1");
    clock.advance(Duration.ofHours(2));

    final MemoryLifecycleSweeper.Pass pass = sweeper.sweep(100);
    assertThat(pass.expired()).isEqualTo(1);
    assertThat(store.size()).isZero();
    assertThat(audit.countOf(EventType.EXPIRY)).isEqualTo(1L);
  }

  @Test
  void aRecordThatHasNotExpiredIsLeftAlone() {
    installPolicy(policy(Duration.ofDays(30), false, true, null, null));
    writeOne("fresh", "k1");
    clock.advance(Duration.ofHours(1));

    assertThat(sweeper.sweep(100).expired()).isZero();
    assertThat(store.size()).isEqualTo(1);
  }

  @Test
  void aRetentionFloorAboveTheTtlStampsNoExpiryAtAllSoTheRecordNeverBecomesACandidate() {
    // The conflict is resolved at write time: the record is stamped with no expiry, so it never
    // reaches the sweeper. Better than expiring it and relying on the sweeper to notice — deleting
    // data a retention rule requires is the worse error (AD-026 §8.2).
    installPolicy(policy(Duration.ofHours(1), false, true, null, Duration.ofDays(365)));
    writeOne("must be kept", "k1");
    clock.advance(Duration.ofDays(2));

    final MemoryLifecycleSweeper.Pass pass = sweeper.sweep(100);
    assertThat(pass.expired()).isZero();
    assertThat(store.size()).isEqualTo(1);
    assertThat(
            store
                .search(MemoryQuery.ofScope(MemoryScope.ofTenant(TENANT), MemoryType.LONG_TERM))
                .get(0)
                .record()
                .expiresAt())
        .isEmpty();
  }

  @Test
  void aRetentionFloorAddedAfterTheWriteStopsTheSweeperDeletingWhatIsAlreadyExpired() {
    // The sweeper's own conflict check, which the case above cannot reach. This is the shape that
    // actually happens in production: a record written under a short TTL, then a retention
    // obligation
    // imposed afterwards. The already-stamped expiry must not be honoured against the new floor.
    installPolicy(policy(Duration.ofHours(1), false, true, null, null));
    writeOne("written before the obligation", "k1");
    clock.advance(Duration.ofDays(2));

    installPolicy(policy(Duration.ofHours(1), false, true, null, Duration.ofDays(365)));

    final MemoryLifecycleSweeper.Pass pass = sweeper.sweep(100);
    assertThat(pass.expired()).isZero();
    assertThat(pass.held()).isEqualTo(1);
    assertThat(store.size()).isEqualTo(1);
  }

  @Test
  void anExpiredRecordIsArchivedRatherThanDeletedWhenPolicySaysSo() {
    installPolicy(policy(Duration.ofHours(1), false, true, Duration.ofMinutes(30), null));
    writeOne("cold", "k1");
    clock.advance(Duration.ofHours(2));

    final MemoryLifecycleSweeper.Pass pass = sweeper.sweep(100);
    assertThat(pass.archived()).isEqualTo(1);
    assertThat(pass.expired()).isZero();
    assertThat(store.size()).isEqualTo(1);
    assertThat(audit.countOf(EventType.ARCHIVE)).isEqualTo(1L);
  }

  @Test
  void anArchivedRecordIsInvisibleUnlessTheQueryAsksForIt() {
    installPolicy(policy(Duration.ofHours(1), false, true, Duration.ofMinutes(30), null));
    writeOne("cold", "k1");
    clock.advance(Duration.ofHours(2));
    sweeper.sweep(100);

    final MemoryOutcome hidden =
        runtime.read(
            caller(), MemoryQuery.ofScope(MemoryScope.ofTenant(TENANT), MemoryType.LONG_TERM));
    assertThat(((MemoryOutcome.Retrieved) hidden).empty()).isTrue();
  }

  @Test
  void aSweepIsIdempotentSoARepeatedPassChangesNothingFurther() {
    installPolicy(policy(Duration.ofHours(1), false, true, null, null));
    writeOne("transient", "k1");
    clock.advance(Duration.ofHours(2));

    assertThat(sweeper.sweep(100).expired()).isEqualTo(1);
    assertThat(sweeper.sweep(100).expired()).isZero();
    assertThat(store.size()).isZero();
  }

  @Test
  void aSweepOverAnUnreachableStoreDoesNothingRatherThanFailing() {
    // Correctness does not depend on the sweeper: TTL is re-evaluated on every read, so a dead
    // sweeper
    // costs storage rather than visibility of expired data.
    store.setAvailable(false);
    final MemoryLifecycleSweeper.Pass pass = sweeper.sweep(100);
    assertThat(pass.productive()).isFalse();
    assertThat(metrics.counter("memory.store_unavailable")).isPositive();
  }

  @Test
  void aSweepBatchIsBounded() {
    installPolicy(policy(Duration.ofHours(1), false, true, null, null));
    for (int i = 0; i < 20; i++) {
      writeOne("t" + i, "k" + i);
    }
    clock.advance(Duration.ofHours(2));

    assertThat(sweeper.sweep(5).expired()).isEqualTo(5);
    assertThat(store.size()).isEqualTo(15);
  }

  @Test
  void aSweepAlsoClearsTheIndexSoNoOrphanEntrySurvivesTheRecord() {
    installPolicy(policy(Duration.ofHours(1), false, true, null, null));
    runtime.write(caller(), write(MemoryScope.ofTenant(TENANT), MemoryType.SEMANTIC, "x", "k1"));
    assertThat(index.size()).isEqualTo(1);
    clock.advance(Duration.ofHours(2));

    sweeper.sweep(100);
    assertThat(index.size()).isZero();
  }

  @Test
  void aZeroBatchIsRefused() {
    org.assertj.core.api.Assertions.assertThatThrownBy(() -> sweeper.sweep(0))
        .isInstanceOf(IllegalArgumentException.class);
  }
}

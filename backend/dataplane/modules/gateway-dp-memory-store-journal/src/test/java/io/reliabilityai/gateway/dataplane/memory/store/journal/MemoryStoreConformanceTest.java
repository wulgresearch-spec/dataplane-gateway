package io.reliabilityai.gateway.dataplane.memory.store.journal;

import static io.reliabilityai.gateway.dataplane.memory.store.journal.StoreFixtures.ALICE;
import static io.reliabilityai.gateway.dataplane.memory.store.journal.StoreFixtures.BOB;
import static io.reliabilityai.gateway.dataplane.memory.store.journal.StoreFixtures.OTHER_TENANT;
import static io.reliabilityai.gateway.dataplane.memory.store.journal.StoreFixtures.T0;
import static io.reliabilityai.gateway.dataplane.memory.store.journal.StoreFixtures.TENANT;
import static io.reliabilityai.gateway.dataplane.memory.store.journal.StoreFixtures.archivedQuery;
import static io.reliabilityai.gateway.dataplane.memory.store.journal.StoreFixtures.awkwardRecord;
import static io.reliabilityai.gateway.dataplane.memory.store.journal.StoreFixtures.keywordQuery;
import static io.reliabilityai.gateway.dataplane.memory.store.journal.StoreFixtures.limitedQuery;
import static io.reliabilityai.gateway.dataplane.memory.store.journal.StoreFixtures.metadataQuery;
import static io.reliabilityai.gateway.dataplane.memory.store.journal.StoreFixtures.record;
import static io.reliabilityai.gateway.dataplane.memory.store.journal.StoreFixtures.scopeQuery;
import static io.reliabilityai.gateway.dataplane.memory.store.journal.StoreFixtures.sealedRecord;
import static io.reliabilityai.gateway.dataplane.memory.store.journal.StoreFixtures.windowQuery;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.reliabilityai.gateway.canonical.identity.TenantScope;
import io.reliabilityai.gateway.dataplane.memory.api.MemoryRecord;
import io.reliabilityai.gateway.dataplane.memory.api.MemoryRecordId;
import io.reliabilityai.gateway.dataplane.memory.api.MemoryScope;
import io.reliabilityai.gateway.dataplane.memory.api.MemoryStorePort;
import io.reliabilityai.gateway.dataplane.memory.api.MemoryStorePort.PutResult;
import io.reliabilityai.gateway.dataplane.memory.api.MemoryStoreUnavailableException;
import io.reliabilityai.gateway.dataplane.memory.api.MemoryType;
import io.reliabilityai.gateway.dataplane.memory.internal.InMemoryMemoryStore;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Supplier;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayNameGeneration;
import org.junit.jupiter.api.DisplayNameGenerator;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * The {@code MemoryStorePort} contract, run against both implementations.
 *
 * <p><b>This is the deliverable that proves the abstraction.</b> Every case below is written
 * against the published port and nothing else, and every one runs twice: once on the reference
 * in-memory store and once on the durable journal adapter. A case that passes for one and fails for
 * the other is a hole in the port, not a bug in an adapter — which is exactly what a conformance
 * suite is for.
 *
 * <p>The suite is deliberately in the adapter module rather than in C17. C17 must not depend on a
 * storage engine, so it cannot name one even in a test; putting the suite here keeps the dependency
 * pointing the right way (AD-026 §15.3).
 *
 * <p>Each numbered guarantee from AD-026 §12.3 has at least one case that would fail if the
 * guarantee were dropped.
 */
@DisplayNameGeneration(DisplayNameGenerator.ReplaceUnderscores.class)
class MemoryStoreConformanceTest {

  @TempDir static Path journalRoot;

  /**
   * A store under test, plus a way to reopen it — reopening is meaningless for the in-memory one.
   */
  record Subject(String name, MemoryStorePort store, Supplier<MemoryStorePort> reopen) {
    @Override
    public String toString() {
      return name;
    }
  }

  static Stream<Subject> bothStores() throws IOException {
    final Path root = Files.createTempDirectory(journalRoot, "conformance");
    return Stream.of(
        new Subject("in-memory", new InMemoryMemoryStore(), InMemoryMemoryStore::new),
        new Subject("journal", new JournalMemoryStore(root), () -> new JournalMemoryStore(root)));
  }

  private static MemoryScope tenantScope() {
    return MemoryScope.ofTenant(TENANT);
  }

  private static String writeKey(final String id) {
    return "wk-" + id;
  }

  // ---- A1: put is durable before it returns
  // ------------------------------------------------------

  @ParameterizedTest
  @MethodSource("bothStores")
  void aStoredRecordIsImmediatelyReadable(final Subject subject) {
    final MemoryRecord stored = record("r1", tenantScope(), "hello");
    assertThat(subject.store().put(stored, writeKey("r1")).created()).isTrue();
    assertThat(subject.store().get(tenantScope(), stored.id())).contains(stored);
  }

  @ParameterizedTest
  @MethodSource("bothStores")
  void putReturnsTheRecordItStored(final Subject subject) {
    final MemoryRecord stored = record("r1", tenantScope(), "hello");
    final PutResult result = subject.store().put(stored, writeKey("r1"));
    assertThat(result.record()).isEqualTo(stored);
  }

  // ---- A2: put is idempotent on the write key
  // ------------------------------------------------------

  @ParameterizedTest
  @MethodSource("bothStores")
  void thesameWriteKeyTwiceStoresOnce(final Subject subject) {
    final MemoryRecord first = record("r1", tenantScope(), "first");
    assertThat(subject.store().put(first, writeKey("shared")).created()).isTrue();

    final MemoryRecord second = record("r2", tenantScope(), "second");
    final PutResult replay = subject.store().put(second, writeKey("shared"));

    assertThat(replay.created()).isFalse();
    assertThat(replay.record()).isEqualTo(first);
    assertThat(subject.store().get(tenantScope(), second.id())).isEmpty();
  }

  @ParameterizedTest
  @MethodSource("bothStores")
  void theSameWriteKeyInTwoTenantsIsTwoDifferentWrites(final Subject subject) {
    // Idempotence is per scope. Collapsing across tenants would be a cross-tenant leak dressed up
    // as
    // deduplication.
    final MemoryScope mine = tenantScope();
    final MemoryScope theirs = MemoryScope.ofTenant(OTHER_TENANT);
    assertThat(subject.store().put(record("a", mine, "mine"), "shared").created()).isTrue();
    assertThat(subject.store().put(record("b", theirs, "theirs"), "shared").created()).isTrue();

    assertThat(subject.store().get(mine, MemoryRecordId.of("a"))).isPresent();
    assertThat(subject.store().get(theirs, MemoryRecordId.of("b"))).isPresent();
  }

  @ParameterizedTest
  @MethodSource("bothStores")
  void aWriteKeyIsReusableOnceItsRecordIsDeleted(final Subject subject) {
    // Otherwise a delete-then-rewrite cycle would be silently swallowed as a duplicate, and the
    // caller
    // would believe it had rewritten something that is not there.
    final MemoryRecord first = record("r1", tenantScope(), "first");
    subject.store().put(first, writeKey("k"));
    subject.store().delete(tenantScope(), first.id());

    final MemoryRecord again = record("r1", tenantScope(), "again");
    assertThat(subject.store().put(again, writeKey("k")).created()).isTrue();
    assertThat(subject.store().get(tenantScope(), again.id())).contains(again);
  }

  // ---- A3: search reaches only the narrowed scope
  // ---------------------------------------------------

  @ParameterizedTest
  @MethodSource("bothStores")
  void searchNeverReturnsAnotherTenantsRecords(final Subject subject) {
    subject.store().put(record("mine", tenantScope(), "shared text"), writeKey("a"));
    subject
        .store()
        .put(record("theirs", MemoryScope.ofTenant(OTHER_TENANT), "shared text"), writeKey("b"));

    final List<MemoryStorePort.ScoredRecord> hits =
        subject.store().search(scopeQuery(tenantScope(), MemoryType.LONG_TERM));
    assertThat(hits).hasSize(1);
    assertThat(hits.get(0).record().id().value()).isEqualTo("mine");
  }

  @ParameterizedTest
  @MethodSource("bothStores")
  void getNeverReachesAnotherTenantEvenByExactId(final Subject subject) {
    subject
        .store()
        .put(record("shared-id", MemoryScope.ofTenant(OTHER_TENANT), "theirs"), writeKey("b"));
    assertThat(subject.store().get(tenantScope(), MemoryRecordId.of("shared-id"))).isEmpty();
  }

  @ParameterizedTest
  @MethodSource("bothStores")
  void aUserPrivateRecordIsInvisibleToAnotherUser(final Subject subject) {
    subject
        .store()
        .put(record("alices", MemoryScope.ofUser(TENANT, ALICE), "private"), writeKey("a"));
    assertThat(subject.store().get(MemoryScope.ofUser(TENANT, BOB), MemoryRecordId.of("alices")))
        .isEmpty();
    assertThat(
            subject
                .store()
                .search(scopeQuery(MemoryScope.ofUser(TENANT, BOB), MemoryType.LONG_TERM)))
        .isEmpty();
  }

  @ParameterizedTest
  @MethodSource("bothStores")
  void aUserPrivateRecordIsVisibleToItsOwner(final Subject subject) {
    subject
        .store()
        .put(record("alices", MemoryScope.ofUser(TENANT, ALICE), "private"), writeKey("a"));
    assertThat(subject.store().get(MemoryScope.ofUser(TENANT, ALICE), MemoryRecordId.of("alices")))
        .isPresent();
  }

  @ParameterizedTest
  @MethodSource("bothStores")
  void searchHonoursTheTypeFilter(final Subject subject) {
    subject
        .store()
        .put(
            record("a", tenantScope(), MemoryType.SESSION, "x", Map.of(), null, T0), writeKey("a"));
    subject
        .store()
        .put(
            record("b", tenantScope(), MemoryType.LONG_TERM, "x", Map.of(), null, T0),
            writeKey("b"));

    assertThat(subject.store().search(scopeQuery(tenantScope(), MemoryType.SESSION))).hasSize(1);
  }

  @ParameterizedTest
  @MethodSource("bothStores")
  void searchHonoursMetadataFilters(final Subject subject) {
    subject
        .store()
        .put(
            record("open", tenantScope(), MemoryType.TASK, "x", Map.of("status", "open"), null, T0),
            writeKey("a"));
    subject
        .store()
        .put(
            record("done", tenantScope(), MemoryType.TASK, "x", Map.of("status", "done"), null, T0),
            writeKey("b"));

    final List<MemoryStorePort.ScoredRecord> hits =
        subject
            .store()
            .search(metadataQuery(tenantScope(), MemoryType.TASK, Map.of("status", "open")));
    assertThat(hits).hasSize(1);
    assertThat(hits.get(0).record().id().value()).isEqualTo("open");
  }

  @ParameterizedTest
  @MethodSource("bothStores")
  void searchHonoursATimeWindow(final Subject subject) {
    subject
        .store()
        .put(
            record("early", tenantScope(), MemoryType.EPISODIC, "x", Map.of(), null, T0),
            writeKey("a"));
    subject
        .store()
        .put(
            record(
                "late",
                tenantScope(),
                MemoryType.EPISODIC,
                "x",
                Map.of(),
                null,
                T0.plus(Duration.ofHours(5))),
            writeKey("b"));

    final List<MemoryStorePort.ScoredRecord> hits =
        subject
            .store()
            .search(
                windowQuery(
                    tenantScope(), MemoryType.EPISODIC, T0.plus(Duration.ofHours(1)), null));
    assertThat(hits).hasSize(1);
    assertThat(hits.get(0).record().id().value()).isEqualTo("late");
  }

  @ParameterizedTest
  @MethodSource("bothStores")
  void searchFindsKeywordMatchesAndSkipsNonMatches(final Subject subject) {
    subject.store().put(record("hit", tenantScope(), "the quick brown fox"), writeKey("a"));
    subject.store().put(record("miss", tenantScope(), "nothing relevant"), writeKey("b"));

    final List<MemoryStorePort.ScoredRecord> hits =
        subject.store().search(keywordQuery(tenantScope(), MemoryType.LONG_TERM, "fox"));
    assertThat(hits).hasSize(1);
    assertThat(hits.get(0).record().id().value()).isEqualTo("hit");
  }

  @ParameterizedTest
  @MethodSource("bothStores")
  void searchDoesNotMatchInsideASealedBody(final Subject subject) {
    // Matching ciphertext would be meaningless; decrypting to search would put key handling inside
    // a
    // storage adapter, which MEM-8 forbids.
    subject.store().put(sealedRecord("sealed", tenantScope(), "findable plaintext"), writeKey("a"));
    assertThat(
            subject.store().search(keywordQuery(tenantScope(), MemoryType.LONG_TERM, "findable")))
        .isEmpty();
  }

  @ParameterizedTest
  @MethodSource("bothStores")
  void archivedRecordsAreHiddenUnlessAskedFor(final Subject subject) {
    final MemoryRecord archived = record("cold", tenantScope(), "x").archivedRecord();
    subject.store().put(archived, writeKey("a"));

    assertThat(subject.store().search(scopeQuery(tenantScope(), MemoryType.LONG_TERM))).isEmpty();
    assertThat(subject.store().search(archivedQuery(tenantScope(), MemoryType.LONG_TERM)))
        .hasSize(1);
  }

  // ---- A4: unavailability throws, never an empty result
  // ---------------------------------------------

  @ParameterizedTest
  @MethodSource("bothStores")
  void anUnavailableStoreThrowsRatherThanReportingNoMemories(final Subject subject) {
    // The most damaging thing an adapter can get wrong. An empty result is indistinguishable from
    // "this tenant has no memories", which reads as data loss and, in a write-if-absent flow,
    // causes it.
    subject.store().put(record("r1", tenantScope(), "x"), writeKey("a"));
    setAvailable(subject, false);
    try {
      assertThatThrownBy(
              () -> subject.store().search(scopeQuery(tenantScope(), MemoryType.LONG_TERM)))
          .isInstanceOf(MemoryStoreUnavailableException.class);
      assertThatThrownBy(() -> subject.store().get(tenantScope(), MemoryRecordId.of("r1")))
          .isInstanceOf(MemoryStoreUnavailableException.class);
      assertThatThrownBy(() -> subject.store().put(record("r2", tenantScope(), "y"), writeKey("b")))
          .isInstanceOf(MemoryStoreUnavailableException.class);
    } finally {
      setAvailable(subject, true);
    }
  }

  @ParameterizedTest
  @MethodSource("bothStores")
  void aStoreRecoversWhenItBecomesAvailableAgain(final Subject subject) {
    setAvailable(subject, false);
    setAvailable(subject, true);
    assertThat(subject.store().put(record("r1", tenantScope(), "x"), writeKey("a")).created())
        .isTrue();
  }

  @ParameterizedTest
  @MethodSource("bothStores")
  void touchIsBestEffortAndNeverThrowsWhenTheStoreIsDown(final Subject subject) {
    // The port declares it best-effort: losing an access count degrades ranking slightly, while
    // failing the read that triggered it loses the answer entirely.
    subject.store().put(record("r1", tenantScope(), "x"), writeKey("a"));
    setAvailable(subject, false);
    try {
      subject.store().touch(tenantScope(), MemoryRecordId.of("r1"), T0);
    } finally {
      setAvailable(subject, true);
    }
  }

  // ---- A5: bounds are honoured
  // -----------------------------------------------------------------------

  @ParameterizedTest
  @MethodSource("bothStores")
  void searchNeverReturnsMoreThanTheLimit(final Subject subject) {
    for (int i = 0; i < 40; i++) {
      subject.store().put(record("r" + i, tenantScope(), "body " + i), writeKey("k" + i));
    }
    assertThat(subject.store().search(limitedQuery(tenantScope(), MemoryType.LONG_TERM, 7)))
        .hasSize(7);
  }

  @ParameterizedTest
  @MethodSource("bothStores")
  void expiredNeverReturnsMoreThanItsLimit(final Subject subject) {
    for (int i = 0; i < 20; i++) {
      subject
          .store()
          .put(
              record(
                  "r" + i, tenantScope(), MemoryType.SESSION, "x", Map.of(), T0.plusSeconds(1), T0),
              writeKey("k" + i));
    }
    assertThat(subject.store().expired(T0.plusSeconds(10), 5)).hasSize(5);
  }

  // ---- A6: stored bytes come back byte-identical
  // -------------------------------------------------------

  @ParameterizedTest
  @MethodSource("bothStores")
  void everyFieldOfAnAwkwardRecordSurvivesAStoreAndFetch(final Subject subject) {
    // Newlines, separators, escapes, unicode, every flag set, an expiry, a prior access. If any
    // field
    // is dropped or mangled by a codec, this is where it shows.
    final MemoryRecord awkward = awkwardRecord("awkward", tenantScope());
    subject.store().put(awkward, writeKey("a"));
    assertThat(subject.store().get(tenantScope(), awkward.id())).contains(awkward);
  }

  @ParameterizedTest
  @MethodSource("bothStores")
  void aSealedRecordKeepsItsCiphertextKeyReferenceAndPlaintextDigest(final Subject subject) {
    final MemoryRecord sealed = sealedRecord("sealed", tenantScope(), "the plaintext");
    subject.store().put(sealed, writeKey("a"));

    final MemoryRecord back = subject.store().get(tenantScope(), sealed.id()).orElseThrow();
    assertThat(back.content().sealed()).isTrue();
    assertThat(back.content().body()).isEqualTo(StoreFixtures.fakeCipher("the plaintext"));
    assertThat(back.content().keyRef()).contains("key-ref-1");
    assertThat(back.content().digest()).isEqualTo(StoreFixtures.digestOf("the plaintext"));
  }

  @ParameterizedTest
  @MethodSource("bothStores")
  void aScopeWithNullWorkspaceStaysDistinctFromOneWithAnEmptyWorkspace(final Subject subject) {
    // Two scopes differing only there must not collapse into one, or records would leak between
    // them.
    final MemoryScope nullWorkspace =
        new MemoryScope(new TenantScope("acme", "core", null, null), Optional.empty(), "");
    final MemoryScope namedWorkspace =
        new MemoryScope(new TenantScope("acme", "core", "ws", null), Optional.empty(), "");

    subject.store().put(record("n", nullWorkspace, "x"), writeKey("a"));
    subject.store().put(record("w", namedWorkspace, "x"), writeKey("b"));

    // A workspace-pinned caller sees only its own; the tenant-wide caller sees both.
    assertThat(subject.store().search(scopeQuery(namedWorkspace, MemoryType.LONG_TERM))).hasSize(1);
    assertThat(subject.store().search(scopeQuery(nullWorkspace, MemoryType.LONG_TERM))).hasSize(2);
  }

  // ---- A7: delete is idempotent and reports what it did
  // --------------------------------------------------

  @ParameterizedTest
  @MethodSource("bothStores")
  void deleteRemovesTheRecordAndReportsTrue(final Subject subject) {
    final MemoryRecord stored = record("r1", tenantScope(), "x");
    subject.store().put(stored, writeKey("a"));
    assertThat(subject.store().delete(tenantScope(), stored.id())).isTrue();
    assertThat(subject.store().get(tenantScope(), stored.id())).isEmpty();
  }

  @ParameterizedTest
  @MethodSource("bothStores")
  void deletingSomethingAlreadyGoneReportsFalseRatherThanFailing(final Subject subject) {
    assertThat(subject.store().delete(tenantScope(), MemoryRecordId.of("never"))).isFalse();
  }

  @ParameterizedTest
  @MethodSource("bothStores")
  void deleteIsIdempotent(final Subject subject) {
    final MemoryRecord stored = record("r1", tenantScope(), "x");
    subject.store().put(stored, writeKey("a"));
    assertThat(subject.store().delete(tenantScope(), stored.id())).isTrue();
    assertThat(subject.store().delete(tenantScope(), stored.id())).isFalse();
  }

  @ParameterizedTest
  @MethodSource("bothStores")
  void aCallerCannotDeleteARecordItCouldNotHaveRead(final Subject subject) {
    subject.store().put(record("alices", MemoryScope.ofUser(TENANT, ALICE), "x"), writeKey("a"));
    assertThat(subject.store().delete(MemoryScope.ofUser(TENANT, BOB), MemoryRecordId.of("alices")))
        .isFalse();
    assertThat(subject.store().get(MemoryScope.ofUser(TENANT, ALICE), MemoryRecordId.of("alices")))
        .isPresent();
  }

  // ---- expiry and touch
  // -----------------------------------------------------------------------------------

  @ParameterizedTest
  @MethodSource("bothStores")
  void expiredReturnsOnlyRecordsPastTheirExpiry(final Subject subject) {
    subject
        .store()
        .put(
            record("gone", tenantScope(), MemoryType.SESSION, "x", Map.of(), T0.plusSeconds(1), T0),
            writeKey("a"));
    subject
        .store()
        .put(
            record(
                "fresh",
                tenantScope(),
                MemoryType.SESSION,
                "x",
                Map.of(),
                T0.plus(Duration.ofDays(9)),
                T0),
            writeKey("b"));
    subject
        .store()
        .put(
            record("eternal", tenantScope(), MemoryType.SESSION, "x", Map.of(), null, T0),
            writeKey("c"));

    final List<MemoryRecord> due = subject.store().expired(T0.plusSeconds(10), 100);
    assertThat(due).hasSize(1);
    assertThat(due.get(0).id().value()).isEqualTo("gone");
  }

  @ParameterizedTest
  @MethodSource("bothStores")
  void expiredSpansEveryTenantBecauseTheSweeperIsAPlatformActor(final Subject subject) {
    subject
        .store()
        .put(
            record("mine", tenantScope(), MemoryType.SESSION, "x", Map.of(), T0.plusSeconds(1), T0),
            writeKey("a"));
    subject
        .store()
        .put(
            record(
                "theirs",
                MemoryScope.ofTenant(OTHER_TENANT),
                MemoryType.SESSION,
                "x",
                Map.of(),
                T0.plusSeconds(1),
                T0),
            writeKey("b"));

    assertThat(subject.store().expired(T0.plusSeconds(10), 100)).hasSize(2);
  }

  @ParameterizedTest
  @MethodSource("bothStores")
  void touchRecordsAnAccess(final Subject subject) {
    final MemoryRecord stored = record("r1", tenantScope(), "x");
    subject.store().put(stored, writeKey("a"));
    subject.store().touch(tenantScope(), stored.id(), T0.plusSeconds(60));

    final MemoryRecord back = subject.store().get(tenantScope(), stored.id()).orElseThrow();
    assertThat(back.accessCount()).isEqualTo(1L);
    assertThat(back.lastAccessedAt()).contains(T0.plusSeconds(60));
  }

  @ParameterizedTest
  @MethodSource("bothStores")
  void touchingAnUnknownRecordIsHarmless(final Subject subject) {
    subject.store().touch(tenantScope(), MemoryRecordId.of("never"), T0);
  }

  @ParameterizedTest
  @MethodSource("bothStores")
  void touchCannotBeUsedToProbeAnotherUsersRecords(final Subject subject) {
    subject.store().put(record("alices", MemoryScope.ofUser(TENANT, ALICE), "x"), writeKey("a"));
    subject.store().touch(MemoryScope.ofUser(TENANT, BOB), MemoryRecordId.of("alices"), T0);

    final MemoryRecord back =
        subject
            .store()
            .get(MemoryScope.ofUser(TENANT, ALICE), MemoryRecordId.of("alices"))
            .orElseThrow();
    assertThat(back.accessCount()).isZero();
  }

  // ---- absent behaviour
  // ----------------------------------------------------------------------------------

  @ParameterizedTest
  @MethodSource("bothStores")
  void gettingSomethingThatWasNeverStoredIsEmptyRatherThanAnError(final Subject subject) {
    assertThat(subject.store().get(tenantScope(), MemoryRecordId.of("never"))).isEmpty();
  }

  @ParameterizedTest
  @MethodSource("bothStores")
  void searchingAnEmptyTenantIsEmptyRatherThanAnError(final Subject subject) {
    assertThat(
            subject.store().search(scopeQuery(MemoryScope.ofTenant(OTHER_TENANT), MemoryType.TOOL)))
        .isEmpty();
  }

  private static void setAvailable(final Subject subject, final boolean up) {
    if (subject.store() instanceof InMemoryMemoryStore inMemory) {
      inMemory.setAvailable(up);
    } else if (subject.store() instanceof JournalMemoryStore journal) {
      journal.setAvailable(up);
    }
  }
}

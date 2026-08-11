package io.reliabilityai.gateway.dataplane.agent.internal;

import static io.reliabilityai.gateway.dataplane.agent.AgentFixtures.OTHER_TENANT;
import static io.reliabilityai.gateway.dataplane.agent.AgentFixtures.T0;
import static io.reliabilityai.gateway.dataplane.agent.AgentFixtures.TENANT;
import static io.reliabilityai.gateway.dataplane.agent.AgentFixtures.created;
import static io.reliabilityai.gateway.dataplane.agent.AgentFixtures.model;
import static io.reliabilityai.gateway.dataplane.agent.AgentFixtures.plan;
import static io.reliabilityai.gateway.dataplane.agent.AgentFixtures.security;
import static io.reliabilityai.gateway.dataplane.agent.AgentFixtures.securityIn;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.reliabilityai.gateway.dataplane.agent.api.CancellationCause;
import io.reliabilityai.gateway.dataplane.agent.api.Plan;
import io.reliabilityai.gateway.dataplane.agent.api.RunEvent;
import io.reliabilityai.gateway.dataplane.agent.api.RunId;
import io.reliabilityai.gateway.dataplane.agent.api.RunRepository;
import io.reliabilityai.gateway.dataplane.agent.api.RunRepository.AppendResult;
import io.reliabilityai.gateway.dataplane.agent.api.RunStoreUnavailableException;
import io.reliabilityai.gateway.dataplane.agent.api.StepId;
import io.reliabilityai.gateway.dataplane.agent.api.StepKind;
import io.reliabilityai.gateway.dataplane.agent.api.TerminalReason;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.function.Supplier;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayNameGeneration;
import org.junit.jupiter.api.DisplayNameGenerator;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * The run store's contract, exercised against both implementations.
 *
 * <p>The shared parameterised cases pin down the semantics a durable store must provide —
 * conditional append above all — so that swapping the file journal for a database cannot quietly
 * change them. The journal-specific cases below cover the two hard parts a file brings with it:
 * fsync and torn writes.
 */
@DisplayNameGeneration(DisplayNameGenerator.ReplaceUnderscores.class)
class RunStoreTest {

  @TempDir static Path journalRoot;

  private static final RunId RUN = RunId.of("run-a");
  private static final String SUFFIX = ".jrnl";
  private static final Plan PLAN = plan(model("a"), model("b"));

  static Stream<Supplier<RunRepository>> bothStores() {
    return Stream.of(
        InMemoryRunRepository::new,
        () -> {
          try {
            return new JournalRunRepository(
                Files.createTempDirectory(journalRoot, "store"), new CanonicalRunSerializer());
          } catch (final IOException failure) {
            throw new IllegalStateException(failure);
          }
        });
  }

  private static RunEvent step(final int index) {
    return new RunEvent.StepScheduled(
        StepId.of(RUN, index), 0, "a", StepKind.PIPELINE, 1, "ref-" + index, T0);
  }

  // ---- The contract both stores must satisfy ------------------------------------------------

  @ParameterizedTest
  @MethodSource("bothStores")
  void aCreatedRunIsLoadableAndHasExactlyOneEvent(final Supplier<RunRepository> factory) {
    final RunRepository store = factory.get();
    assertThat(store.create(RUN, created(PLAN, security("chat"))))
        .isInstanceOf(AppendResult.Appended.class);
    assertThat(store.load(RUN)).isPresent();
    assertThat(store.load(RUN).orElseThrow().offset()).isEqualTo(1L);
  }

  @ParameterizedTest
  @MethodSource("bothStores")
  void creatingTheSameRunTwiceConflictsRatherThanOverwriting(
      final Supplier<RunRepository> factory) {
    final RunRepository store = factory.get();
    store.create(RUN, created(PLAN, security("chat")));
    assertThat(store.create(RUN, created(PLAN, security("chat"))))
        .isInstanceOf(AppendResult.Conflict.class);
    assertThat(store.load(RUN).orElseThrow().offset()).isEqualTo(1L);
  }

  @ParameterizedTest
  @MethodSource("bothStores")
  void anUnknownRunLoadsAsEmptyRatherThanThrowing(final Supplier<RunRepository> factory) {
    assertThat(factory.get().load(RunId.of("never-created"))).isEmpty();
  }

  @ParameterizedTest
  @MethodSource("bothStores")
  void appendingAtTheCurrentOffsetSucceedsAndAdvancesIt(final Supplier<RunRepository> factory) {
    final RunRepository store = factory.get();
    store.create(RUN, created(PLAN, security("chat")));
    final AppendResult appended = store.append(RUN, 1L, step(0));
    assertThat(appended).isInstanceOf(AppendResult.Appended.class);
    assertThat(((AppendResult.Appended) appended).newOffset()).isEqualTo(2L);
  }

  @ParameterizedTest
  @MethodSource("bothStores")
  void appendingAtAStaleOffsetConflictsAndChangesNothing(final Supplier<RunRepository> factory) {
    // The conditional write is the mutual-exclusion primitive. Two executors that both believe they
    // hold the lease arrive here; the second sees a longer history and must abandon its step.
    final RunRepository store = factory.get();
    store.create(RUN, created(PLAN, security("chat")));
    store.append(RUN, 1L, step(0));
    final AppendResult stale = store.append(RUN, 1L, step(1));
    assertThat(stale).isInstanceOf(AppendResult.Conflict.class);
    assertThat(((AppendResult.Conflict) stale).actualOffset()).isEqualTo(2L);
    assertThat(store.load(RUN).orElseThrow().offset()).isEqualTo(2L);
  }

  @ParameterizedTest
  @MethodSource("bothStores")
  void appendingAheadOfTheCurrentOffsetAlsoConflicts(final Supplier<RunRepository> factory) {
    final RunRepository store = factory.get();
    store.create(RUN, created(PLAN, security("chat")));
    assertThat(store.append(RUN, 99L, step(0))).isInstanceOf(AppendResult.Conflict.class);
  }

  @ParameterizedTest
  @MethodSource("bothStores")
  void appendingToARunThatDoesNotExistConflicts(final Supplier<RunRepository> factory) {
    assertThat(factory.get().append(RunId.of("ghost"), 0L, step(0)))
        .isInstanceOf(AppendResult.Conflict.class);
  }

  @ParameterizedTest
  @MethodSource("bothStores")
  void eventsComeBackInTheOrderTheyWereAppended(final Supplier<RunRepository> factory) {
    final RunRepository store = factory.get();
    store.create(RUN, created(PLAN, security("chat")));
    for (int i = 0; i < 10; i++) {
      store.append(RUN, 1L + i, step(i));
    }
    final List<RunEvent> events = store.load(RUN).orElseThrow().events();
    assertThat(events).hasSize(11);
    for (int i = 0; i < 10; i++) {
      assertThat(((RunEvent.StepScheduled) events.get(i + 1)).stepId().index()).isEqualTo(i);
    }
  }

  @ParameterizedTest
  @MethodSource("bothStores")
  void aTerminatedRunIsNoLongerClaimable(final Supplier<RunRepository> factory) {
    final RunRepository store = factory.get();
    store.create(RUN, created(PLAN, security("chat")));
    assertThat(store.claimable(TENANT, T0, 10)).contains(RUN);
    store.append(RUN, 1L, RunEvent.RunTerminated.of(TerminalReason.COMPLETED_SUCCESS, 0, 0L, T0));
    assertThat(store.claimable(TENANT, T0, 10)).isEmpty();
    assertThat(store.unfinished(TENANT, 10)).isEmpty();
  }

  @ParameterizedTest
  @MethodSource("bothStores")
  void aParkedRunIsNotClaimableUntilItsWakeInstant(final Supplier<RunRepository> factory) {
    final RunRepository store = factory.get();
    store.create(RUN, created(PLAN, security("chat")));
    store.append(RUN, 1L, new RunEvent.RunParked(T0.plusSeconds(600), T0));
    assertThat(store.claimable(TENANT, T0, 10)).isEmpty();
    assertThat(store.claimable(TENANT, T0.plusSeconds(600), 10)).contains(RUN);
  }

  @ParameterizedTest
  @MethodSource("bothStores")
  void aParkedRunIsStillUnfinishedSoTheRecoverySweepSeesIt(final Supplier<RunRepository> factory) {
    // AD-025 SM-8: a bound must be able to terminate a parked run, so the sweep has to find it even
    // while it is not claimable.
    final RunRepository store = factory.get();
    store.create(RUN, created(PLAN, security("chat")));
    store.append(RUN, 1L, new RunEvent.RunParked(T0.plusSeconds(600), T0));
    assertThat(store.unfinished(TENANT, 10)).contains(RUN);
  }

  @ParameterizedTest
  @MethodSource("bothStores")
  void runsAreTenantPartitionedInBothDirections(final Supplier<RunRepository> factory) {
    final RunRepository store = factory.get();
    final RunId mine = RunId.of("mine");
    final RunId theirs = RunId.of("theirs");
    store.create(mine, created(PLAN, security("chat")));
    store.create(theirs, created(PLAN, securityIn(OTHER_TENANT, "c2", "chat")));

    assertThat(store.claimable(TENANT, T0, 10)).containsExactly(mine);
    assertThat(store.claimable(OTHER_TENANT, T0, 10)).containsExactly(theirs);
    assertThat(store.unfinished(TENANT, 10)).doesNotContain(theirs);
  }

  @ParameterizedTest
  @MethodSource("bothStores")
  void aClaimIsExclusiveWhileItIsLive(final Supplier<RunRepository> factory) {
    final RunRepository store = factory.get();
    store.create(RUN, created(PLAN, security("chat")));
    assertThat(store.claim(RUN, "node-a", T0, T0.plusSeconds(30))).isTrue();
    assertThat(store.claim(RUN, "node-b", T0, T0.plusSeconds(30))).isFalse();
  }

  @ParameterizedTest
  @MethodSource("bothStores")
  void anExpiredClaimIsTakeableByAnotherNode(final Supplier<RunRepository> factory) {
    // This is what lets a dead node's work be picked up: nothing has to notice it died.
    final RunRepository store = factory.get();
    store.create(RUN, created(PLAN, security("chat")));
    store.claim(RUN, "node-a", T0, T0.plusSeconds(30));
    assertThat(store.claim(RUN, "node-b", T0.plusSeconds(31), T0.plusSeconds(61))).isTrue();
  }

  @ParameterizedTest
  @MethodSource("bothStores")
  void aClaimedRunIsNotOfferedToAnotherExecutor(final Supplier<RunRepository> factory) {
    final RunRepository store = factory.get();
    store.create(RUN, created(PLAN, security("chat")));
    store.claim(RUN, "node-a", T0, T0.plusSeconds(30));
    assertThat(store.claimable(TENANT, T0, 10)).isEmpty();
    assertThat(store.claimable(TENANT, T0.plusSeconds(31), 10)).contains(RUN);
  }

  @ParameterizedTest
  @MethodSource("bothStores")
  void onlyTheHolderMayReleaseAClaim(final Supplier<RunRepository> factory) {
    // A stale node whose lease already expired must not be able to knock the current holder off.
    final RunRepository store = factory.get();
    store.create(RUN, created(PLAN, security("chat")));
    store.claim(RUN, "node-a", T0, T0.plusSeconds(30));
    store.release(RUN, "node-b");
    assertThat(store.claim(RUN, "node-c", T0, T0.plusSeconds(30))).isFalse();
    store.release(RUN, "node-a");
    assertThat(store.claim(RUN, "node-c", T0, T0.plusSeconds(30))).isTrue();
  }

  @ParameterizedTest
  @MethodSource("bothStores")
  void releasingAnUnknownRunIsHarmless(final Supplier<RunRepository> factory) {
    factory.get().release(RunId.of("ghost"), "node-a");
  }

  @ParameterizedTest
  @MethodSource("bothStores")
  void claimingAnUnknownRunFails(final Supplier<RunRepository> factory) {
    assertThat(factory.get().claim(RunId.of("ghost"), "n", T0, T0.plusSeconds(1))).isFalse();
  }

  @ParameterizedTest
  @MethodSource("bothStores")
  void theClaimableLimitIsRespected(final Supplier<RunRepository> factory) {
    final RunRepository store = factory.get();
    for (int i = 0; i < 20; i++) {
      store.create(RunId.of("r-" + i), created(PLAN, security("chat")));
    }
    assertThat(store.claimable(TENANT, T0, 5)).hasSize(5);
    assertThat(store.unfinished(TENANT, 7)).hasSize(7);
  }

  // ---- In-memory specifics -------------------------------------------------------------------

  @Test
  void anUnavailableStoreThrowsOnLoadRatherThanReturningAnEmptyHistory() {
    // HSC-8. An empty history is indistinguishable from a new run, so reporting unavailability that
    // way would make a recovering node restart a live run from step one.
    final InMemoryRunRepository store = new InMemoryRunRepository();
    store.create(RUN, created(PLAN, security("chat")));
    store.setAvailable(false);
    assertThatThrownBy(() -> store.load(RUN)).isInstanceOf(RunStoreUnavailableException.class);
  }

  @Test
  void anUnavailableStoreReportsUnavailabilityOnAppendRatherThanConflict() {
    final InMemoryRunRepository store = new InMemoryRunRepository();
    store.create(RUN, created(PLAN, security("chat")));
    store.setAvailable(false);
    assertThat(store.append(RUN, 1L, step(0))).isInstanceOf(AppendResult.Unavailable.class);
  }

  @Test
  void anUnavailableStoreRecoversWhenItComesBack() {
    final InMemoryRunRepository store = new InMemoryRunRepository();
    store.create(RUN, created(PLAN, security("chat")));
    store.setAvailable(false);
    store.setAvailable(true);
    assertThat(store.append(RUN, 1L, step(0))).isInstanceOf(AppendResult.Appended.class);
  }

  @Test
  void clearingForgetsEverything() {
    final InMemoryRunRepository store = new InMemoryRunRepository();
    store.create(RUN, created(PLAN, security("chat")));
    assertThat(store.size()).isEqualTo(1);
    store.clear();
    assertThat(store.size()).isZero();
  }

  // ---- Journal specifics: durability and torn writes ------------------------------------------

  @Test
  void aRunSurvivesReopeningTheStore(@TempDir final Path dir) {
    final JournalRunRepository first = new JournalRunRepository(dir, new CanonicalRunSerializer());
    first.create(RUN, created(PLAN, security("chat")));
    first.append(RUN, 1L, step(0));

    final JournalRunRepository reopened =
        new JournalRunRepository(dir, new CanonicalRunSerializer());
    assertThat(reopened.load(RUN)).isPresent();
    assertThat(reopened.load(RUN).orElseThrow().offset()).isEqualTo(2L);
  }

  @Test
  void aReopenedRunKeepsItsEventsIntactAndInOrder(@TempDir final Path dir) {
    final JournalRunRepository first = new JournalRunRepository(dir, new CanonicalRunSerializer());
    first.create(RUN, created(PLAN, security("chat")));
    first.append(RUN, 1L, step(0));
    first.append(
        RUN,
        2L,
        new RunEvent.StepCompleted(StepId.of(RUN, 0), "a", "the answer", "dig", 42L, true, T0));

    final JournalRunRepository reopened =
        new JournalRunRepository(dir, new CanonicalRunSerializer());
    final List<RunEvent> events = reopened.load(RUN).orElseThrow().events();
    assertThat(events.get(0)).isInstanceOf(RunEvent.RunCreated.class);
    assertThat(events.get(1)).isInstanceOf(RunEvent.StepScheduled.class);
    final RunEvent.StepCompleted completed = (RunEvent.StepCompleted) events.get(2);
    assertThat(completed.value()).isEqualTo("the answer");
    assertThat(completed.costMicros()).isEqualTo(42L);
    assertThat(completed.tainted()).isTrue();
  }

  @Test
  void aTerminatedRunIsStillTerminatedAfterAReopen(@TempDir final Path dir) {
    final JournalRunRepository first = new JournalRunRepository(dir, new CanonicalRunSerializer());
    first.create(RUN, created(PLAN, security("chat")));
    first.append(RUN, 1L, RunEvent.RunTerminated.of(TerminalReason.CANCELLED_CALLER, 0, 0L, T0));

    final JournalRunRepository reopened =
        new JournalRunRepository(dir, new CanonicalRunSerializer());
    assertThat(reopened.claimable(TENANT, T0, 10)).isEmpty();
    assertThat(reopened.load(RUN).orElseThrow().terminated()).isTrue();
  }

  @Test
  void aParkedRunIsStillParkedAfterAReopen(@TempDir final Path dir) {
    final JournalRunRepository first = new JournalRunRepository(dir, new CanonicalRunSerializer());
    first.create(RUN, created(PLAN, security("chat")));
    first.append(RUN, 1L, new RunEvent.RunParked(T0.plus(Duration.ofHours(6)), T0));

    final JournalRunRepository reopened =
        new JournalRunRepository(dir, new CanonicalRunSerializer());
    assertThat(reopened.claimable(TENANT, T0, 10)).isEmpty();
    assertThat(reopened.claimable(TENANT, T0.plus(Duration.ofHours(6)), 10)).contains(RUN);
  }

  @Test
  void aTornFinalRecordFromPowerLossIsDiscardedAndTheRestSurvives(@TempDir final Path dir)
      throws IOException {
    // Power lost part-way through a write leaves a line with no terminator. The prefix before it is
    // intact, and a half-record must never be repaired into a fabricated fact.
    final JournalRunRepository first = new JournalRunRepository(dir, new CanonicalRunSerializer());
    first.create(RUN, created(PLAN, security("chat")));
    first.append(RUN, 1L, step(0));

    final Path journal = onlyJournalIn(dir);
    Files.writeString(
        journal,
        Files.readString(journal, StandardCharsets.UTF_8) + "deadbeef:step.sched",
        StandardCharsets.UTF_8);
    // The appended fragment has no terminator, which is exactly what a half-completed write leaves.

    final JournalRunRepository reopened =
        new JournalRunRepository(dir, new CanonicalRunSerializer());
    assertThat(reopened.load(RUN).orElseThrow().offset()).isEqualTo(2L);
  }

  @Test
  void aTornTailIsTruncatedOnDiskSoTheNextAppendLandsAtTheRightOffset(@TempDir final Path dir)
      throws IOException {
    final JournalRunRepository first = new JournalRunRepository(dir, new CanonicalRunSerializer());
    first.create(RUN, created(PLAN, security("chat")));
    final Path journal = onlyJournalIn(dir);
    final long goodLength = Files.size(journal);
    try (RandomAccessFile raf = new RandomAccessFile(journal.toFile(), "rw")) {
      raf.seek(goodLength);
      raf.write("garbage-with-no-newline".getBytes(StandardCharsets.UTF_8));
    }

    final JournalRunRepository reopened =
        new JournalRunRepository(dir, new CanonicalRunSerializer());
    assertThat(Files.size(journal)).isEqualTo(goodLength);
    assertThat(reopened.append(RUN, 1L, step(0))).isInstanceOf(AppendResult.Appended.class);

    final JournalRunRepository again = new JournalRunRepository(dir, new CanonicalRunSerializer());
    assertThat(again.load(RUN).orElseThrow().offset()).isEqualTo(2L);
  }

  @Test
  void aCompleteLineWhoseChecksumDisagreesIsDiscarded(@TempDir final Path dir) throws IOException {
    // Rarer than a missing terminator, and the reason a length check alone is not enough: a
    // partially
    // written sector can happen to contain a newline.
    final JournalRunRepository first = new JournalRunRepository(dir, new CanonicalRunSerializer());
    first.create(RUN, created(PLAN, security("chat")));
    first.append(RUN, 1L, step(0));

    final Path journal = onlyJournalIn(dir);
    final List<String> lines = Files.readAllLines(journal, StandardCharsets.UTF_8);
    // Line 0 is the run-id header, line 1 the creation record, line 2 the scheduled step.
    // Corrupting
    // the step's checksum must discard it and leave the creation record intact.
    assertThat(lines).hasSize(3);
    final String corrupted = lines.get(2).replaceFirst("^[0-9a-f]+:", "00000000:");
    Files.writeString(
        journal,
        lines.get(0) + "\n" + lines.get(1) + "\n" + corrupted + "\n",
        StandardCharsets.UTF_8);

    final JournalRunRepository reopened =
        new JournalRunRepository(dir, new CanonicalRunSerializer());
    assertThat(reopened.load(RUN).orElseThrow().offset()).isEqualTo(1L);
  }

  @Test
  void aJournalWhoseFirstRecordIsUnreadableIsNotRegisteredAsARun(@TempDir final Path dir)
      throws IOException {
    // No creation record means no run. Attaching the surviving events to an invented run would be
    // worse than leaving the file alone for a human.
    final JournalRunRepository first = new JournalRunRepository(dir, new CanonicalRunSerializer());
    first.create(RUN, created(PLAN, security("chat")));
    final Path journal = onlyJournalIn(dir);
    Files.writeString(journal, "#run=run-a\nnot-a-record-at-all\n", StandardCharsets.UTF_8);

    final JournalRunRepository reopened =
        new JournalRunRepository(dir, new CanonicalRunSerializer());
    assertThat(reopened.load(RUN)).isEmpty();
    assertThat(reopened.size()).isZero();
  }

  @Test
  void aJournalWithNoHeaderIsNotRecoveredUnderAGuessedIdentity(@TempDir final Path dir)
      throws IOException {
    // Step identities are stored relative to their run, so recovering under the wrong id would
    // silently rewrite every step in the history. Refusing is the only safe option.
    final JournalRunRepository first = new JournalRunRepository(dir, new CanonicalRunSerializer());
    first.create(RUN, created(PLAN, security("chat")));
    final Path journal = onlyJournalIn(dir);
    final List<String> lines = Files.readAllLines(journal, StandardCharsets.UTF_8);
    Files.writeString(journal, lines.get(1) + "\n", StandardCharsets.UTF_8);

    assertThat(new JournalRunRepository(dir, new CanonicalRunSerializer()).size()).isZero();
  }

  @Test
  void twoRunIdsThatSanitiseAlikeGetDistinctJournals(@TempDir final Path dir) {
    // "a/b" and "a-b" collapse to one name under sanitising alone; sharing a journal would
    // interleave
    // two histories irrecoverably, so the file name carries a digest of the raw id.
    final JournalRunRepository store = new JournalRunRepository(dir, new CanonicalRunSerializer());
    final RunId first = RunId.of("a/b");
    final RunId second = RunId.of("a-b");
    store.create(first, created(PLAN, security("chat")));
    store.create(second, created(PLAN, security("chat")));

    assertThat(store.size()).isEqualTo(2);
    final JournalRunRepository reopened =
        new JournalRunRepository(dir, new CanonicalRunSerializer());
    assertThat(reopened.load(first)).isPresent();
    assertThat(reopened.load(second)).isPresent();
  }

  @Test
  void aRunIdWithCharactersOutsideTheFileNameAlphabetComesBackVerbatim(@TempDir final Path dir) {
    final JournalRunRepository store = new JournalRunRepository(dir, new CanonicalRunSerializer());
    final RunId awkward = RunId.of("tenant:run/42 ✓");
    store.create(awkward, created(PLAN, security("chat")));

    final JournalRunRepository reopened =
        new JournalRunRepository(dir, new CanonicalRunSerializer());
    assertThat(reopened.load(awkward)).isPresent();
    assertThat(reopened.load(awkward).orElseThrow().runId()).isEqualTo(awkward);
  }

  @Test
  void anEmptyJournalFileIsIgnored(@TempDir final Path dir) throws IOException {
    Files.createDirectories(dir.resolve("acme-core"));
    Files.writeString(dir.resolve("acme-core").resolve("empty.jrnl"), "");
    final JournalRunRepository store = new JournalRunRepository(dir, new CanonicalRunSerializer());
    assertThat(store.size()).isZero();
  }

  @Test
  void aHostileRunIdCannotEscapeTheJournalRoot(@TempDir final Path dir) throws IOException {
    // Run ids are caller-supplied strings. A caller who supplies a traversal must not choose where
    // the
    // journal lands. The property asserted is containment, not a particular file name — the
    // sanitiser
    // is free to rewrite whatever it likes, provided nothing lands outside the root.
    final JournalRunRepository store = new JournalRunRepository(dir, new CanonicalRunSerializer());
    final RunId hostile = RunId.of("../../../etc/passwd");
    store.create(hostile, created(PLAN, security("chat")));
    assertThat(store.load(hostile)).isPresent();

    final Path journal = onlyJournalIn(dir);
    assertThat(journal.toRealPath()).startsWith(dir.toRealPath());
    final String name = journal.getFileName().toString();
    assertThat(name).endsWith(SUFFIX).doesNotContain("/").doesNotContain("\\");
    assertThat(name.substring(0, name.length() - SUFFIX.length())).doesNotContain(".");
  }

  @Test
  void aHostileTenantNameAlsoStaysInsideTheRoot(@TempDir final Path dir) throws IOException {
    final JournalRunRepository store = new JournalRunRepository(dir, new CanonicalRunSerializer());
    final io.reliabilityai.gateway.canonical.identity.TenantScope hostile =
        io.reliabilityai.gateway.canonical.identity.TenantScope.of("../..", "../etc");
    store.create(RunId.of("r-hostile"), created(PLAN, securityIn(hostile, "c-hostile", "chat")));
    assertThat(onlyJournalIn(dir).toRealPath()).startsWith(dir.toRealPath());
  }

  @Test
  void aRunWithHostileContentSurvivesAReopen(@TempDir final Path dir) {
    final JournalRunRepository first = new JournalRunRepository(dir, new CanonicalRunSerializer());
    first.create(RUN, created(PLAN, security("chat")));
    final String hostile = "line one\nline|two=three\\four";
    first.append(
        RUN, 1L, new RunEvent.StepCompleted(StepId.of(RUN, 0), "a", hostile, "d", 0L, false, T0));

    final JournalRunRepository reopened =
        new JournalRunRepository(dir, new CanonicalRunSerializer());
    final RunEvent.StepCompleted decoded =
        (RunEvent.StepCompleted) reopened.load(RUN).orElseThrow().events().get(1);
    assertThat(decoded.value()).isEqualTo(hostile);
  }

  @Test
  void manyRunsAcrossManyTenantsAllComeBack(@TempDir final Path dir) {
    final JournalRunRepository first = new JournalRunRepository(dir, new CanonicalRunSerializer());
    for (int i = 0; i < 25; i++) {
      first.create(RunId.of("r-" + i), created(PLAN, security("chat")));
      first.create(RunId.of("o-" + i), created(PLAN, securityIn(OTHER_TENANT, "c-" + i, "chat")));
    }
    final JournalRunRepository reopened =
        new JournalRunRepository(dir, new CanonicalRunSerializer());
    assertThat(reopened.size()).isEqualTo(50);
    assertThat(reopened.unfinished(TENANT, 100)).hasSize(25);
    assertThat(reopened.unfinished(OTHER_TENANT, 100)).hasSize(25);
  }

  @Test
  void aCancellationRecordedBeforeACrashIsStillThereAfterOne(@TempDir final Path dir) {
    final JournalRunRepository first = new JournalRunRepository(dir, new CanonicalRunSerializer());
    first.create(RUN, created(PLAN, security("chat")));
    first.append(RUN, 1L, new RunEvent.RunCancelled(CancellationCause.USER, "stop", T0));

    final JournalRunRepository reopened =
        new JournalRunRepository(dir, new CanonicalRunSerializer());
    assertThat(reopened.load(RUN).orElseThrow().events().get(1))
        .isInstanceOf(RunEvent.RunCancelled.class);
  }

  private static Path onlyJournalIn(final Path dir) throws IOException {
    try (Stream<Path> files = Files.walk(dir)) {
      return files
          .filter(path -> path.getFileName().toString().endsWith(".jrnl"))
          .findFirst()
          .orElseThrow(() -> new IllegalStateException("no journal under " + dir));
    }
  }
}

package io.reliabilityai.gateway.dataplane.memory.store.journal;

import static io.reliabilityai.gateway.dataplane.memory.store.journal.StoreFixtures.ALICE;
import static io.reliabilityai.gateway.dataplane.memory.store.journal.StoreFixtures.BOB;
import static io.reliabilityai.gateway.dataplane.memory.store.journal.StoreFixtures.OTHER_TENANT;
import static io.reliabilityai.gateway.dataplane.memory.store.journal.StoreFixtures.T0;
import static io.reliabilityai.gateway.dataplane.memory.store.journal.StoreFixtures.TENANT;
import static io.reliabilityai.gateway.dataplane.memory.store.journal.StoreFixtures.record;
import static io.reliabilityai.gateway.dataplane.memory.store.journal.StoreFixtures.scopeQuery;
import static io.reliabilityai.gateway.dataplane.memory.store.journal.StoreFixtures.sealedRecord;
import static org.assertj.core.api.Assertions.assertThat;

import io.reliabilityai.gateway.canonical.identity.TenantScope;
import io.reliabilityai.gateway.dataplane.memory.api.MemoryRecordId;
import io.reliabilityai.gateway.dataplane.memory.api.MemoryScope;
import io.reliabilityai.gateway.dataplane.memory.api.MemoryType;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayNameGeneration;
import org.junit.jupiter.api.DisplayNameGenerator;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Isolation, enumeration, leakage and timing.
 *
 * <p>The adapter is the last place a cross-tenant read can happen, and the first place a filesystem
 * layout can leak what it should not. These cases attack both.
 */
@DisplayNameGeneration(DisplayNameGenerator.ReplaceUnderscores.class)
class JournalSecurityTest {

  private static final MemoryScope TENANT_SCOPE = MemoryScope.ofTenant(TENANT);

  private static MemoryScope tenantScope() {
    return TENANT_SCOPE;
  }

  private static List<Path> segments(final Path root) throws IOException {
    try (Stream<Path> files = Files.walk(root)) {
      return files.filter(path -> path.getFileName().toString().endsWith(".log")).toList();
    }
  }

  // ---- tenant isolation -------------------------------------------------------------------------

  @Test
  void eachTenantGetsItsOwnDirectorySoIsolationIsAFilesystemBoundary(@TempDir final Path root)
      throws IOException {
    try (JournalMemoryStore store = new JournalMemoryStore(root)) {
      store.put(record("mine", tenantScope(), "x"), "k1");
      store.put(record("theirs", MemoryScope.ofTenant(OTHER_TENANT), "y"), "k2");

      // Two partitions, two directories, no shared file. A query cannot reach across without being
      // handed the wrong directory, and the directory is chosen from the caller's narrowed scope.
      assertThat(store.partitionCount()).isEqualTo(2);
      assertThat(segments(root)).hasSize(2);
      assertThat(segments(root).get(0).getParent()).isNotEqualTo(segments(root).get(1).getParent());
    }
  }

  @Test
  void oneTenantsSegmentNeverContainsAnothersContent(@TempDir final Path root) throws IOException {
    try (JournalMemoryStore store = new JournalMemoryStore(root)) {
      store.put(record("mine", tenantScope(), "ACME-CONFIDENTIAL"), "k1");
      store.put(record("theirs", MemoryScope.ofTenant(OTHER_TENANT), "GLOBEX-CONFIDENTIAL"), "k2");
    }
    for (final Path segment : segments(root)) {
      final String contents = Files.readString(segment, StandardCharsets.UTF_8);
      final boolean acme = contents.contains("ACME-CONFIDENTIAL");
      final boolean globex = contents.contains("GLOBEX-CONFIDENTIAL");
      assertThat(acme && globex).as("segment %s holds both tenants' content", segment).isFalse();
    }
  }

  @Test
  void deletingOneTenantsPartitionLeavesTheOtherIntact(@TempDir final Path root)
      throws IOException {
    // The operational consequence of a filesystem boundary: offboarding a tenant is removing a
    // directory, and it cannot take anything else with it.
    try (JournalMemoryStore store = new JournalMemoryStore(root)) {
      store.put(record("mine", tenantScope(), "x"), "k1");
      store.put(record("theirs", MemoryScope.ofTenant(OTHER_TENANT), "y"), "k2");
    }
    final Path victim = segments(root).get(0).getParent();
    try (Stream<Path> files = Files.walk(victim)) {
      files.sorted(java.util.Comparator.reverseOrder()).forEach(path -> path.toFile().delete());
    }
    try (JournalMemoryStore reopened = new JournalMemoryStore(root)) {
      assertThat(reopened.partitionCount()).isEqualTo(1);
      assertThat(reopened.size()).isEqualTo(1);
    }
  }

  @Test
  void aTenantWithNoRecordsHasNoPartitionToProbe(@TempDir final Path root) {
    try (JournalMemoryStore store = new JournalMemoryStore(root)) {
      assertThat(store.search(scopeQuery(MemoryScope.ofTenant(OTHER_TENANT), MemoryType.LONG_TERM)))
          .isEmpty();
      assertThat(store.partitionCount()).isZero();
    }
  }

  // ---- enumeration ------------------------------------------------------------------------------

  @Test
  void aRecordInAnotherTenantIsReportedExactlyAsOneThatDoesNotExist(@TempDir final Path root) {
    // AD-026 §10.3. Distinguishing them would make get an oracle for the existence of other
    // tenants'
    // records — the classic enumeration leak.
    try (JournalMemoryStore store = new JournalMemoryStore(root)) {
      store.put(record("real-id", MemoryScope.ofTenant(OTHER_TENANT), "theirs"), "k1");

      final var forbidden = store.get(tenantScope(), MemoryRecordId.of("real-id"));
      final var absent = store.get(tenantScope(), MemoryRecordId.of("no-such-id"));
      assertThat(forbidden).isEqualTo(absent).isEmpty();
    }
  }

  @Test
  void aRecordOwnedByAnotherPrincipalIsReportedExactlyAsAbsent(@TempDir final Path root) {
    try (JournalMemoryStore store = new JournalMemoryStore(root)) {
      store.put(record("alices", MemoryScope.ofUser(TENANT, ALICE), "private"), "k1");

      final MemoryScope bob = MemoryScope.ofUser(TENANT, BOB);
      assertThat(store.get(bob, MemoryRecordId.of("alices")))
          .isEqualTo(store.get(bob, MemoryRecordId.of("nothing-here")))
          .isEmpty();
    }
  }

  @Test
  void deletingAnotherPrincipalsRecordReportsTheSameAsDeletingNothing(@TempDir final Path root) {
    try (JournalMemoryStore store = new JournalMemoryStore(root)) {
      store.put(record("alices", MemoryScope.ofUser(TENANT, ALICE), "private"), "k1");
      final MemoryScope bob = MemoryScope.ofUser(TENANT, BOB);

      assertThat(store.delete(bob, MemoryRecordId.of("alices"))).isFalse();
      assertThat(store.delete(bob, MemoryRecordId.of("never-existed"))).isFalse();
    }
  }

  @Test
  void aStampCannotBeUsedToConfirmAnotherPrincipalsRecordExists(@TempDir final Path root) {
    // The optimistic-concurrency surface is an information surface too, and it is scoped like the
    // rest.
    try (JournalMemoryStore store = new JournalMemoryStore(root)) {
      store.put(record("alices", MemoryScope.ofUser(TENANT, ALICE), "private"), "k1");
      assertThat(store.stampOf(MemoryScope.ofUser(TENANT, BOB), MemoryRecordId.of("alices")))
          .isEmpty();
    }
  }

  @Test
  void touchingAnotherTenantsRecordChangesNothingAndRevealsNothing(@TempDir final Path root) {
    try (JournalMemoryStore store = new JournalMemoryStore(root)) {
      store.put(record("theirs", MemoryScope.ofTenant(OTHER_TENANT), "x"), "k1");
      store.touch(tenantScope(), MemoryRecordId.of("theirs"), T0.plusSeconds(5));

      assertThat(
              store
                  .get(MemoryScope.ofTenant(OTHER_TENANT), MemoryRecordId.of("theirs"))
                  .orElseThrow()
                  .accessCount())
          .isZero();
    }
  }

  // ---- leakage
  // --------------------------------------------------------------------------------------

  @Test
  void noFileNameContainsRecordContentOrARecordIdentifier(@TempDir final Path root)
      throws IOException {
    // A filesystem layout is readable by anyone with disk access, including a backup process.
    // Encoding
    // content or identifiers into names would leak them outside every access control above.
    try (JournalMemoryStore store = new JournalMemoryStore(root)) {
      store.put(record("SENSITIVE-ID", tenantScope(), "SENSITIVE-BODY"), "SENSITIVE-KEY");
    }
    try (Stream<Path> files = Files.walk(root)) {
      final List<String> names = files.map(path -> path.getFileName().toString()).toList();
      assertThat(names).noneMatch(name -> name.contains("SENSITIVE"));
    }
  }

  @Test
  void aDirectoryNameDoesNotRevealAWorkspaceOwnerOrSession(@TempDir final Path root)
      throws IOException {
    // The partition is keyed by tenant only. Putting narrower dimensions in the path would create a
    // directory per user, which leaks the user list to anyone who can list the directory.
    try (JournalMemoryStore store = new JournalMemoryStore(root)) {
      store.put(record("r1", MemoryScope.ofPartition(TENANT, ALICE, "session-XYZ"), "x"), "k1");
    }
    try (Stream<Path> files = Files.walk(root)) {
      final List<String> names = files.map(path -> path.getFileName().toString()).toList();
      assertThat(names).noneMatch(name -> name.contains("alice") || name.contains("session-XYZ"));
    }
  }

  @Test
  void aSealedBodyIsNeverWrittenInTheClear(@TempDir final Path root) throws IOException {
    // The adapter receives ciphertext and stores ciphertext. If plaintext ever reached disk,
    // sealing
    // would be decorative.
    try (JournalMemoryStore store = new JournalMemoryStore(root)) {
      store.put(sealedRecord("sealed", tenantScope(), "THE-SECRET-PLAINTEXT"), "k1");
    }
    for (final Path segment : segments(root)) {
      assertThat(Files.readString(segment, StandardCharsets.UTF_8))
          .doesNotContain("THE-SECRET-PLAINTEXT");
    }
  }

  @Test
  void aDeletedRecordsContentIsGoneAfterCompaction(@TempDir final Path root) throws IOException {
    // Before compaction the tombstone hides it but the bytes remain, which is honest and worth
    // being
    // explicit about: erasure needs compaction to reach the disk (AD-027 §6).
    try (JournalMemoryStore store = new JournalMemoryStore(root)) {
      store.put(record("doomed", tenantScope(), "ERASE-ME-COMPLETELY"), "k1");
      store.delete(tenantScope(), MemoryRecordId.of("doomed"));

      boolean stillOnDisk = false;
      for (final Path segment : segments(root)) {
        stillOnDisk |=
            Files.readString(segment, StandardCharsets.UTF_8).contains("ERASE-ME-COMPLETELY");
      }
      assertThat(stillOnDisk).as("tombstone alone does not remove bytes").isTrue();

      store.compact();
      for (final Path segment : segments(root)) {
        assertThat(Files.readString(segment, StandardCharsets.UTF_8))
            .doesNotContain("ERASE-ME-COMPLETELY");
      }
    }
  }

  @Test
  void metadataOfOneTenantNeverAppearsInAnothersResults(@TempDir final Path root) {
    try (JournalMemoryStore store = new JournalMemoryStore(root)) {
      store.put(
          record(
              "theirs",
              MemoryScope.ofTenant(OTHER_TENANT),
              MemoryType.TASK,
              "x",
              Map.of("secret-key", "secret-value"),
              null,
              T0),
          "k1");

      final var hits =
          store.search(
              StoreFixtures.metadataQuery(
                  tenantScope(), MemoryType.TASK, Map.of("secret-key", "secret-value")));
      assertThat(hits).isEmpty();
    }
  }

  @Test
  void theJournalContainsNoControlBytes(@TempDir final Path root) throws IOException {
    // A line-oriented format with stray control bytes is a corruption hazard: it breaks the newline
    // scan recovery depends on, and it makes every tool that reads the file treat it as binary. An
    // earlier version of the codec used a NUL as an in-band sentinel and did exactly that, which is
    // why this is a test rather than a convention.
    try (JournalMemoryStore store = new JournalMemoryStore(root)) {
      store.put(
          record(
              "r1",
              new MemoryScope(TenantScope.of("acme", "core"), java.util.Optional.empty(), ""),
              "a body"),
          "k1");
      store.put(record("r2", MemoryScope.ofUser(TENANT, ALICE), "another"), "k2");
      store.put(
          record(
              "r3",
              new MemoryScope(
                  new TenantScope("acme", "core", "ws", "proj"),
                  java.util.Optional.of(ALICE),
                  "sess"),
              "third"),
          "k3");
      store.delete(TENANT_SCOPE, MemoryRecordId.of("r1"));
      store.touch(MemoryScope.ofUser(TENANT, ALICE), MemoryRecordId.of("r2"), T0);
    }
    for (final Path segment : segments(root)) {
      final byte[] bytes = Files.readAllBytes(segment);
      for (final byte value : bytes) {
        final int unsigned = value & 0xFF;
        assertThat(
                unsigned == 0 || (unsigned > 13 && unsigned < 32) || (unsigned > 0 && unsigned < 9))
            .as("control byte 0x%02x in %s", unsigned, segment)
            .isFalse();
      }
    }
  }

  @Test
  void aNullWorkspaceAndAnEmptyWorkspaceStayDistinctAcrossARestart(@TempDir final Path root) {
    // The reason the sentinel existed at all. Replacing it with a presence prefix must not lose the
    // distinction it was there to preserve.
    final MemoryScope nullWorkspace =
        new MemoryScope(
            new TenantScope("acme", "core", null, null), java.util.Optional.empty(), "");
    final MemoryScope emptyWorkspace =
        new MemoryScope(new TenantScope("acme", "core", "", null), java.util.Optional.empty(), "");

    try (JournalMemoryStore store = new JournalMemoryStore(root)) {
      store.put(record("n", nullWorkspace, "null ws"), "k1");
      store.put(record("e", emptyWorkspace, "empty ws"), "k2");
    }
    try (JournalMemoryStore reopened = new JournalMemoryStore(root)) {
      assertThat(
              reopened
                  .get(nullWorkspace, MemoryRecordId.of("n"))
                  .orElseThrow()
                  .scope()
                  .tenant()
                  .workspace())
          .isNull();
      assertThat(
              reopened
                  .get(emptyWorkspace, MemoryRecordId.of("e"))
                  .orElseThrow()
                  .scope()
                  .tenant()
                  .workspace())
          .isEmpty();
    }
  }

  // ---- timing
  // ---------------------------------------------------------------------------------------

  @Test
  void aForbiddenLookupIsNotDetectablyFasterThanAnAbsentOne(@TempDir final Path root) {
    // A timing gap between "exists but you may not see it" and "does not exist" is an oracle. Both
    // paths do the same partition lookup and the same visibility filter, so they should be
    // indistinguishable. Measured loosely — a unit test cannot police nanoseconds without flaking —
    // and asserted only against an order-of-magnitude difference, which is what an exploitable gap
    // would look like.
    try (JournalMemoryStore store = new JournalMemoryStore(root)) {
      store.put(record("exists", MemoryScope.ofUser(TENANT, ALICE), "x"), "k1");
      final MemoryScope bob = MemoryScope.ofUser(TENANT, BOB);

      for (int i = 0; i < 50_000; i++) {
        store.get(bob, MemoryRecordId.of("exists"));
        store.get(bob, MemoryRecordId.of("absent"));
      }

      final int iterations = 200_000;
      final long forbiddenStart = System.nanoTime();
      for (int i = 0; i < iterations; i++) {
        store.get(bob, MemoryRecordId.of("exists"));
      }
      final long forbidden = System.nanoTime() - forbiddenStart;

      final long absentStart = System.nanoTime();
      for (int i = 0; i < iterations; i++) {
        store.get(bob, MemoryRecordId.of("absent"));
      }
      final long absent = System.nanoTime() - absentStart;

      final double ratio = (double) Math.max(forbidden, absent) / Math.min(forbidden, absent);
      System.out.printf(
          "  PERF  %-38s forbidden=%,d ns  absent=%,d ns  ratio=%.2f%n",
          "timing: forbidden vs absent lookup", forbidden / iterations, absent / iterations, ratio);
      assertThat(ratio).isLessThan(10.0d);
    }
  }

  @Test
  void theStoreExposesNoIndexOrInternalStructureThroughItsPublicSurface(@TempDir final Path root) {
    // Everything public is either the port, a lifecycle operation, or a counter. Nothing hands out
    // a
    // map, an offset, a file handle or a segment — an adapter that leaked its index would let a
    // caller
    // bypass every scope check above it.
    final List<String> leaky = new ArrayList<>();
    for (final var method : JournalMemoryStore.class.getDeclaredMethods()) {
      if (!java.lang.reflect.Modifier.isPublic(method.getModifiers())) {
        continue;
      }
      final String returned = method.getReturnType().getName();
      if (returned.contains("Map") || returned.contains("Path") || returned.contains("Channel")) {
        leaky.add(method.getName() + " -> " + returned);
      }
    }
    assertThat(leaky).isEmpty();
  }
}

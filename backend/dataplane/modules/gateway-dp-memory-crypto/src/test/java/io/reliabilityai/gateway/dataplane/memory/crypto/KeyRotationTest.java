package io.reliabilityai.gateway.dataplane.memory.crypto;

import static io.reliabilityai.gateway.dataplane.memory.crypto.CryptoFixtures.ACME_ALICE;
import static io.reliabilityai.gateway.dataplane.memory.crypto.CryptoFixtures.ACME_BOB;
import static io.reliabilityai.gateway.dataplane.memory.crypto.CryptoFixtures.GLOBEX_TENANT;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.reliabilityai.gateway.dataplane.memory.api.MemoryCryptoPort;
import io.reliabilityai.gateway.dataplane.memory.api.MemoryStoreUnavailableException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Key versioning, rotation, and migrating records between versions. */
@DisplayName("key lifecycle")
final class KeyRotationTest {

  private CryptoFixtures.TestKeys keys;
  private InProcessCryptoMetrics metrics;
  private AeadMemoryCrypto crypto;

  @BeforeEach
  void setUp() {
    keys = new CryptoFixtures.TestKeys("2026-01");
    metrics = new InProcessCryptoMetrics();
    crypto = CryptoFixtures.crypto(keys, metrics);
  }

  @Test
  @DisplayName("new writes use the new version after rotation")
  void newWritesUseTheNewVersionAfterRotation() {
    keys.add("2026-07");
    crypto.rotateTo("2026-07");

    assertThat(crypto.seal(ACME_ALICE, "after").keyRef()).isEqualTo("2026-07");
    assertThat(metrics.count("rotated")).isEqualTo(1);
  }

  @Test
  @DisplayName("records written before a rotation still open afterwards")
  void recordsWrittenBeforeARotationStillOpenAfterwards() {
    final MemoryCryptoPort.Sealed old = crypto.seal(ACME_ALICE, "written in January");
    keys.add("2026-07");
    crypto.rotateTo("2026-07");

    // Rotation is not re-encryption. If it were, every rotation would be an outage for every record
    // not yet migrated.
    assertThat(crypto.unseal(ACME_ALICE, old.ciphertext(), old.keyRef()))
        .contains("written in January");
  }

  @Test
  @DisplayName("records survive a chain of rotations")
  void recordsSurviveAChainOfRotations() {
    final Map<String, MemoryCryptoPort.Sealed> written = new LinkedHashMap<>();
    written.put("2026-01", crypto.seal(ACME_ALICE, "body 2026-01"));
    for (final String version : List.of("2026-02", "2026-03", "2026-04", "2026-05")) {
      keys.add(version);
      crypto.rotateTo(version);
      written.put(version, crypto.seal(ACME_ALICE, "body " + version));
    }

    for (final Map.Entry<String, MemoryCryptoPort.Sealed> entry : written.entrySet()) {
      assertThat(
              crypto.unseal(ACME_ALICE, entry.getValue().ciphertext(), entry.getValue().keyRef()))
          .as("record from %s", entry.getKey())
          .contains("body " + entry.getKey());
    }
    assertThat(crypto.readableVersions()).hasSize(5);
  }

  @Test
  @DisplayName("rotating to a version with no key material is refused")
  void rotatingToAVersionWithNoKeyMaterialIsRefused() {
    assertThatThrownBy(() -> crypto.rotateTo("2027-01"))
        .isInstanceOf(KeyUnavailableException.class);

    // The refusal must leave the primary alone, or a failed rotation becomes a write outage.
    assertThat(crypto.primaryVersion()).isEqualTo("2026-01");
    assertThat(crypto.seal(ACME_ALICE, "still works").keyRef()).isEqualTo("2026-01");
  }

  @Test
  @DisplayName("a rewrap moves a record to the current version and keeps its content")
  void aRewrapMovesARecordToTheCurrentVersionAndKeepsItsContent() {
    final MemoryCryptoPort.Sealed old = crypto.seal(ACME_ALICE, "content to migrate");
    keys.add("2026-07");
    crypto.rotateTo("2026-07");

    final Optional<MemoryCryptoPort.Sealed> moved =
        crypto.rewrap(ACME_ALICE, old.ciphertext(), old.keyRef());

    assertThat(moved).isPresent();
    assertThat(moved.get().keyRef()).isEqualTo("2026-07");
    assertThat(moved.get().ciphertext()).isNotEqualTo(old.ciphertext());
    assertThat(crypto.unseal(ACME_ALICE, moved.get().ciphertext(), moved.get().keyRef()))
        .contains("content to migrate");
    assertThat(metrics.count("rewrapped")).isEqualTo(1);
  }

  @Test
  @DisplayName("a rewrapped record no longer depends on the retired key")
  void aRewrappedRecordNoLongerDependsOnTheRetiredKey() {
    final MemoryCryptoPort.Sealed old = crypto.seal(ACME_ALICE, "content to migrate");
    keys.add("2026-07");
    crypto.rotateTo("2026-07");
    final MemoryCryptoPort.Sealed moved =
        crypto.rewrap(ACME_ALICE, old.ciphertext(), old.keyRef()).orElseThrow();

    keys.retire("2026-01");

    // This is the point of the whole migration: only once this holds can the old key be destroyed.
    assertThat(crypto.unseal(ACME_ALICE, moved.ciphertext(), moved.keyRef()))
        .contains("content to migrate");
    assertThat(crypto.unseal(ACME_ALICE, old.ciphertext(), old.keyRef())).isEmpty();
  }

  @Test
  @DisplayName("a rewrap preserves the scope binding")
  void aRewrapPreservesTheScopeBinding() {
    final MemoryCryptoPort.Sealed old = crypto.seal(ACME_ALICE, "alice content");
    keys.add("2026-07");
    crypto.rotateTo("2026-07");
    final MemoryCryptoPort.Sealed moved =
        crypto.rewrap(ACME_ALICE, old.ciphertext(), old.keyRef()).orElseThrow();

    // A migration that quietly widened access would be the worst possible bug to ship, because it
    // would look like a successful maintenance job.
    assertThat(crypto.unseal(ACME_BOB, moved.ciphertext(), moved.keyRef())).isEmpty();
    assertThat(crypto.unseal(GLOBEX_TENANT, moved.ciphertext(), moved.keyRef())).isEmpty();
  }

  @Test
  @DisplayName("a rewrap of an unopenable record is skipped rather than fatal")
  void aRewrapOfAnUnopenableRecordIsSkippedRatherThanFatal() {
    // A migration over a million records must not abort on the one that is corrupt.
    assertThat(crypto.rewrap(ACME_ALICE, "not-an-envelope", "2026-01")).isEmpty();
  }

  @Test
  @DisplayName("a rewrap under the wrong scope is refused, not silently re-sealed")
  void aRewrapUnderTheWrongScopeIsRefusedNotSilentlyReSealed() {
    final MemoryCryptoPort.Sealed old = crypto.seal(ACME_ALICE, "alice content");

    assertThat(crypto.rewrap(ACME_BOB, old.ciphertext(), old.keyRef())).isEmpty();
  }

  @Test
  @DisplayName("a whole corpus migrates across a rotation")
  void aWholeCorpusMigratesAcrossARotation() {
    final List<MemoryCryptoPort.Sealed> corpus = new ArrayList<>();
    for (int i = 0; i < 500; i++) {
      corpus.add(crypto.seal(ACME_ALICE, "record " + i));
    }
    keys.add("2026-07");
    crypto.rotateTo("2026-07");

    final List<MemoryCryptoPort.Sealed> migrated = new ArrayList<>();
    for (final MemoryCryptoPort.Sealed record : corpus) {
      migrated.add(crypto.rewrap(ACME_ALICE, record.ciphertext(), record.keyRef()).orElseThrow());
    }
    keys.retire("2026-01");

    for (int i = 0; i < migrated.size(); i++) {
      assertThat(crypto.unseal(ACME_ALICE, migrated.get(i).ciphertext(), migrated.get(i).keyRef()))
          .as("migrated record %d", i)
          .contains("record " + i);
    }
  }

  @Test
  @DisplayName("a migration interrupted halfway leaves both halves readable")
  void aMigrationInterruptedHalfwayLeavesBothHalvesReadable() {
    final List<MemoryCryptoPort.Sealed> corpus = new ArrayList<>();
    for (int i = 0; i < 20; i++) {
      corpus.add(crypto.seal(ACME_ALICE, "record " + i));
    }
    keys.add("2026-07");
    crypto.rotateTo("2026-07");

    // Migrating half, then stopping, is the normal state of any migration at any given moment. A
    // design that only works once it has finished is a design that cannot be run.
    for (int i = 0; i < 10; i++) {
      corpus.set(
          i,
          crypto
              .rewrap(ACME_ALICE, corpus.get(i).ciphertext(), corpus.get(i).keyRef())
              .orElseThrow());
    }

    for (int i = 0; i < 20; i++) {
      assertThat(crypto.unseal(ACME_ALICE, corpus.get(i).ciphertext(), corpus.get(i).keyRef()))
          .as("record %d mid-migration", i)
          .contains("record " + i);
    }
    assertThat(corpus.subList(0, 10)).allMatch(s -> s.keyRef().equals("2026-07"));
    assertThat(corpus.subList(10, 20)).allMatch(s -> s.keyRef().equals("2026-01"));
  }

  @Test
  @DisplayName("a rewrap is idempotent in effect if run twice")
  void aRewrapIsIdempotentInEffectIfRunTwice() {
    final MemoryCryptoPort.Sealed old = crypto.seal(ACME_ALICE, "content");
    keys.add("2026-07");
    crypto.rotateTo("2026-07");
    final MemoryCryptoPort.Sealed once =
        crypto.rewrap(ACME_ALICE, old.ciphertext(), old.keyRef()).orElseThrow();
    final MemoryCryptoPort.Sealed twice =
        crypto.rewrap(ACME_ALICE, once.ciphertext(), once.keyRef()).orElseThrow();

    // Byte-identical it is not, and must not be — a fresh nonce every time is the invariant. What
    // has to hold is that a resumed migration re-running a record changes nothing observable.
    assertThat(twice.keyRef()).isEqualTo("2026-07");
    assertThat(twice.ciphertext()).isNotEqualTo(once.ciphertext());
    assertThat(crypto.unseal(ACME_ALICE, twice.ciphertext(), twice.keyRef())).contains("content");
  }

  @Test
  @DisplayName("the wrap budget is charged per version and fails closed when spent")
  void theWrapBudgetIsChargedPerVersionAndFailsClosedWhenSpent() {
    final AeadMemoryCrypto bounded =
        new AeadMemoryCrypto(keys, metrics, new java.security.SecureRandom(), 3);

    for (int i = 0; i < 3; i++) {
      bounded.seal(ACME_ALICE, "within budget " + i);
    }

    assertThatThrownBy(() -> bounded.seal(ACME_ALICE, "over budget"))
        .isInstanceOf(MemoryStoreUnavailableException.class)
        .hasMessageContaining("nonce budget");
    assertThat(metrics.count("wrapBudgetExceeded")).isEqualTo(1);
  }

  @Test
  @DisplayName("rotating restores writes after a version has spent its budget")
  void rotatingRestoresWritesAfterAVersionHasSpentItsBudget() {
    final AeadMemoryCrypto bounded =
        new AeadMemoryCrypto(keys, metrics, new java.security.SecureRandom(), 2);
    bounded.seal(ACME_ALICE, "one");
    bounded.seal(ACME_ALICE, "two");
    keys.add("2026-07");
    bounded.rotateTo("2026-07");

    // The budget belongs to the key, not to the process, so the fresh version starts fresh.
    assertThat(bounded.seal(ACME_ALICE, "three").keyRef()).isEqualTo("2026-07");
    assertThat(bounded.wrapsUnder("2026-01")).isEqualTo(2);
    assertThat(bounded.wrapsUnder("2026-07")).isEqualTo(1);
  }

  @Test
  @DisplayName("the readable version set follows the provider")
  void theReadableVersionSetFollowsTheProvider() {
    assertThat(crypto.readableVersions()).containsExactly("2026-01");
    keys.add("2026-07");
    assertThat(crypto.readableVersions()).containsExactlyInAnyOrder("2026-01", "2026-07");
    keys.retire("2026-01");
    assertThat(crypto.readableVersions()).containsExactly("2026-07");
  }
}

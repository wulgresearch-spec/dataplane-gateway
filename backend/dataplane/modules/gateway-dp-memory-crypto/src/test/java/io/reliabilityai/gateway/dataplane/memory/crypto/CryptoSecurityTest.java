package io.reliabilityai.gateway.dataplane.memory.crypto;

import static io.reliabilityai.gateway.dataplane.memory.crypto.CryptoFixtures.ACME_ALICE;
import static io.reliabilityai.gateway.dataplane.memory.crypto.CryptoFixtures.ACME_BOB;
import static io.reliabilityai.gateway.dataplane.memory.crypto.CryptoFixtures.ACME_TENANT;
import static io.reliabilityai.gateway.dataplane.memory.crypto.CryptoFixtures.GLOBEX_TENANT;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.reliabilityai.gateway.dataplane.memory.api.MemoryCryptoPort;
import io.reliabilityai.gateway.dataplane.memory.api.MemoryStoreUnavailableException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The threat model of AD-028 §7, one test per threat, including the ones that are not covered. */
@DisplayName("threat model")
final class CryptoSecurityTest {

  private CryptoFixtures.TestKeys keys;
  private InProcessCryptoMetrics metrics;
  private AeadMemoryCrypto crypto;

  @BeforeEach
  void setUp() {
    keys = new CryptoFixtures.TestKeys("2026-07");
    metrics = new InProcessCryptoMetrics();
    crypto = CryptoFixtures.crypto(keys, metrics);
  }

  @Test
  @DisplayName("T1 stolen disk: sealed bodies on disk reveal no plaintext")
  void t1StolenDiskSealedBodiesOnDiskRevealNoPlaintext(@TempDir final Path directory)
      throws Exception {
    final List<String> secrets =
        List.of("account 4111111111111111", "diagnosis: hypertension", "password hunter2");
    final Path file = directory.resolve("segment.log");
    final StringBuilder disk = new StringBuilder();
    for (final String secret : secrets) {
      disk.append(crypto.seal(ACME_ALICE, secret).ciphertext()).append('\n');
    }
    Files.writeString(file, disk.toString());

    final String onDisk = Files.readString(file);
    for (final String secret : secrets) {
      assertThat(onDisk).doesNotContain(secret);
      for (final String word : secret.split(" ")) {
        assertThat(onDisk.toLowerCase(Locale.ROOT))
            .as("word %s leaked", word)
            .doesNotContain(word.toLowerCase(Locale.ROOT));
      }
    }
  }

  @Test
  @DisplayName("T2 partial disclosure: a fragment of an envelope yields nothing")
  void t2PartialDisclosureAFragmentOfAnEnvelopeYieldsNothing() {
    final MemoryCryptoPort.Sealed sealed =
        crypto.seal(ACME_ALICE, "a body whose middle must not be recoverable on its own");
    final byte[] raw = CryptoFixtures.rawOf(sealed.ciphertext());

    // A recovered disk sector is a slice, not a file. Every slice must be useless by itself.
    for (int start = 1; start < raw.length; start += 7) {
      final byte[] fragment = java.util.Arrays.copyOfRange(raw, start, raw.length);
      assertThat(crypto.unseal(ACME_ALICE, CryptoFixtures.encodeRaw(fragment), sealed.keyRef()))
          .as("fragment from byte %d", start)
          .isEmpty();
    }
  }

  @Test
  @DisplayName("T3 record modification: any edit to the body is refused")
  void t3RecordModificationAnyEditToTheBodyIsRefused() {
    final MemoryCryptoPort.Sealed sealed = crypto.seal(ACME_ALICE, "balance: 100");
    final byte[] raw = CryptoFixtures.rawOf(sealed.ciphertext());
    raw[raw.length - 20] ^= 0x40;

    assertThat(crypto.unseal(ACME_ALICE, CryptoFixtures.encodeRaw(raw), sealed.keyRef())).isEmpty();
    assertThat(metrics.count("openFailed.AUTHENTICATION_FAILED")).isEqualTo(1);
  }

  @Test
  @DisplayName("T4 record swapping across scopes is refused")
  void t4RecordSwappingAcrossScopesIsRefused() {
    final MemoryCryptoPort.Sealed alice = crypto.seal(ACME_ALICE, "alice salary");
    final MemoryCryptoPort.Sealed bob = crypto.seal(ACME_BOB, "bob salary");

    assertThat(crypto.unseal(ACME_ALICE, bob.ciphertext(), bob.keyRef())).isEmpty();
    assertThat(crypto.unseal(ACME_BOB, alice.ciphertext(), alice.keyRef())).isEmpty();
  }

  @Test
  @DisplayName("T5 record swapping WITHIN one scope is NOT detected — the known gap, B36")
  void t5RecordSwappingWithinOneScopeIsNotDetected() {
    final MemoryCryptoPort.Sealed first = crypto.seal(ACME_ALICE, "the original answer");
    final MemoryCryptoPort.Sealed second = crypto.seal(ACME_ALICE, "the substituted answer");

    // This asserts the limitation rather than a defence, so that it is impossible to believe the
    // suite covers it. MemoryCryptoPort.seal is handed a scope and a body and no record identity,
    // so
    // the tag cannot bind one. An attacker with write access to storage can exchange two sealed
    // bodies inside a single scope and both will open cleanly under the other's identifier.
    //
    // Closing this needs a record identifier in the port signature, which is a Memory Runtime
    // change
    // and therefore outside B24. If this test ever starts failing, the gap has been closed and this
    // test should be replaced with its inverse.
    assertThat(crypto.unseal(ACME_ALICE, second.ciphertext(), second.keyRef()))
        .contains("the substituted answer");
    assertThat(crypto.unseal(ACME_ALICE, first.ciphertext(), first.keyRef()))
        .contains("the original answer");
  }

  @Test
  @DisplayName("T6 metadata tampering: editing the key version in the envelope is refused")
  void t6MetadataTamperingEditingTheKeyVersionInTheEnvelopeIsRefused() {
    keys.add("2026-04");
    final MemoryCryptoPort.Sealed sealed = crypto.seal(ACME_ALICE, "body");
    final byte[] raw = CryptoFixtures.rawOf(sealed.ciphertext());
    // Overwrite the seven-byte version label in place with another loadable version.
    System.arraycopy("2026-04".getBytes(StandardCharsets.US_ASCII), 0, raw, 5, 7);

    // Both the reference cross-check and the tag stand in the way. Either alone would do; having
    // both means a mistake in one is not a breach.
    assertThat(crypto.unseal(ACME_ALICE, CryptoFixtures.encodeRaw(raw), "2026-04")).isEmpty();
    assertThat(crypto.unseal(ACME_ALICE, CryptoFixtures.encodeRaw(raw), sealed.keyRef())).isEmpty();
  }

  @Test
  @DisplayName(
      "T7 replay: a ciphertext replayed into its own scope still opens — the known gap, B36")
  void t7ReplayACiphertextReplayedIntoItsOwnScopeStillOpens() {
    final MemoryCryptoPort.Sealed sealed = crypto.seal(ACME_ALICE, "transfer approved");

    // Stated plainly: this layer offers no replay protection within a scope, for the same reason it
    // offers no swap protection — there is no record identity and no sequence in the port. Replay
    // across scopes IS refused, which is the part the scope binding can reach.
    assertThat(crypto.unseal(ACME_ALICE, sealed.ciphertext(), sealed.keyRef()))
        .contains("transfer approved");
    assertThat(crypto.unseal(ACME_BOB, sealed.ciphertext(), sealed.keyRef())).isEmpty();
  }

  @Test
  @DisplayName("T8 rollback: an old ciphertext is refused once its key is destroyed")
  void t8RollbackAnOldCiphertextIsRefusedOnceItsKeyIsDestroyed() {
    final MemoryCryptoPort.Sealed old = crypto.seal(ACME_ALICE, "superseded content");
    keys.add("2026-10");
    crypto.rotateTo("2026-10");
    keys.retire("2026-07");

    // Key destruction is the only rollback defence this layer has, and it is coarse: it invalidates
    // every record still on that version, not just the one being rolled back.
    assertThat(crypto.unseal(ACME_ALICE, old.ciphertext(), old.keyRef())).isEmpty();
    assertThat(metrics.count("openFailed.UNKNOWN_KEY_VERSION")).isEqualTo(1);
  }

  @Test
  @DisplayName("T9 nonce reuse: a stuck random source is detected and the write refused")
  void t9NonceReuseAStuckRandomSourceIsDetectedAndTheWriteRefused() {
    final AeadMemoryCrypto broken =
        new AeadMemoryCrypto(keys, metrics, CryptoFixtures.stuckRandom((byte) 0x5a), 1_000);

    assertThatThrownBy(() -> broken.seal(ACME_ALICE, "body"))
        .isInstanceOf(MemoryStoreUnavailableException.class)
        .hasMessageContaining("repeated a nonce");
    assertThat(metrics.count("nonceCollisionSuspected")).isPositive();
  }

  @Test
  @DisplayName("T9 a healthy random source never trips the canary over many writes")
  void t9AHealthyRandomSourceNeverTripsTheCanaryOverManyWrites() {
    for (int i = 0; i < 20_000; i++) {
      crypto.seal(ACME_ALICE, "body " + i);
    }

    // A canary that cries wolf gets disabled, and then it is not a canary. Forty thousand nonces
    // through a 64Ki-slot window must produce no alarms at all.
    assertThat(metrics.count("nonceCollisionSuspected")).isZero();
  }

  @Test
  @DisplayName("T10 bit flips: every byte position is covered by the tag")
  void t10BitFlipsEveryBytePositionIsCoveredByTheTag() {
    final MemoryCryptoPort.Sealed sealed = crypto.seal(ACME_ALICE, "content of consequence");
    final int length = CryptoFixtures.rawOf(sealed.ciphertext()).length;
    int refused = 0;

    for (int index = 0; index < length; index++) {
      for (int bit = 0; bit < 8; bit++) {
        final byte[] raw = CryptoFixtures.rawOf(sealed.ciphertext());
        raw[index] ^= (byte) (1 << bit);
        if (crypto.unseal(ACME_ALICE, CryptoFixtures.encodeRaw(raw), sealed.keyRef()).isEmpty()) {
          refused++;
        }
      }
    }

    assertThat(refused).isEqualTo(length * 8);
  }

  @Test
  @DisplayName("T11 invalid ciphertext: random bytes are never accepted")
  void t11InvalidCiphertextRandomBytesAreNeverAccepted() {
    final java.security.SecureRandom random = new java.security.SecureRandom();
    for (int i = 0; i < 500; i++) {
      final byte[] noise = new byte[1 + random.nextInt(200)];
      random.nextBytes(noise);
      assertThat(crypto.unseal(ACME_ALICE, CryptoFixtures.encodeRaw(noise), "2026-07"))
          .as("random input %d", i)
          .isEmpty();
    }
  }

  @Test
  @DisplayName("T12 old key versions cannot be forced onto new writes")
  void t12OldKeyVersionsCannotBeForcedOntoNewWrites() {
    keys.add("2026-01");
    keys.add("2026-10");
    crypto.rotateTo("2026-10");

    // Nothing a caller passes influences which key is used. The scope is data, not a key selector.
    assertThat(crypto.seal(ACME_ALICE, "a").keyRef()).isEqualTo("2026-10");
    assertThat(crypto.seal(GLOBEX_TENANT, "b").keyRef()).isEqualTo("2026-10");
    assertThat(crypto.seal(ACME_TENANT, "c").keyRef()).isEqualTo("2026-10");
  }

  @Test
  @DisplayName("T13 cross-tenant: no ciphertext opens under any other tenant, over a corpus")
  void t13CrossTenantNoCiphertextOpensUnderAnyOtherTenant() {
    final List<MemoryCryptoPort.Sealed> acme = new ArrayList<>();
    for (int i = 0; i < 100; i++) {
      acme.add(crypto.seal(ACME_TENANT, "acme record " + i));
    }

    for (final MemoryCryptoPort.Sealed sealed : acme) {
      assertThat(crypto.unseal(GLOBEX_TENANT, sealed.ciphertext(), sealed.keyRef())).isEmpty();
    }
    assertThat(metrics.count("openFailed.AUTHENTICATION_FAILED")).isEqualTo(100);
  }

  @Test
  @DisplayName("T14 key material never reaches an exception message")
  void t14KeyMaterialNeverReachesAnExceptionMessage() {
    keys.retire("2026-07");

    assertThatThrownBy(() -> crypto.seal(ACME_ALICE, "body"))
        .isInstanceOf(MemoryStoreUnavailableException.class)
        .satisfies(
            thrown ->
                assertThat(thrown.getMessage())
                    .isEqualTo("master key version 2026-07 is not loadable"));
  }

  @Test
  @DisplayName("T14 key material never reaches a provider's toString")
  void t14KeyMaterialNeverReachesAProvidersToString(@TempDir final Path directory)
      throws Exception {
    final byte[] material = new byte[32];
    new java.security.SecureRandom().nextBytes(material);
    final String encoded = Base64.getEncoder().encodeToString(material);
    final Path keyring = directory.resolve("keys");
    Files.writeString(keyring, "primary = v1\nv1 = " + encoded + "\n");

    final FileMasterKeyProvider provider = new FileMasterKeyProvider(keyring);

    assertThat(provider.toString()).doesNotContain(encoded);
    assertThat(provider.toString()).contains("primary=v1");
  }

  @Test
  @DisplayName("T14 key material never reaches a metric name or value")
  void t14KeyMaterialNeverReachesAMetricNameOrValue() {
    final Set<String> observed = new HashSet<>();
    final CryptoMetricsPort recorder =
        new CryptoMetricsPort() {
          @Override
          public void sealed(final String keyVersion, final int plaintextBytes) {
            observed.add(keyVersion);
          }

          @Override
          public void opened(final String keyVersion) {
            observed.add(keyVersion);
          }

          @Override
          public void openFailed(final String keyVersion, final OpenFailure reason) {
            observed.add(keyVersion);
            observed.add(reason.name());
          }

          @Override
          public void nonceCollisionSuspected(final String keyVersion) {
            observed.add(keyVersion);
          }

          @Override
          public void wrapBudgetExceeded(final String keyVersion) {
            observed.add(keyVersion);
          }

          @Override
          public void rotated(final String fromVersion, final String toVersion) {
            observed.add(fromVersion);
            observed.add(toVersion);
          }

          @Override
          public void rewrapped(final String fromVersion, final String toVersion) {
            observed.add(fromVersion);
            observed.add(toVersion);
          }
        };
    final AeadMemoryCrypto watched = CryptoFixtures.crypto(keys, new InProcessCryptoMetrics());
    final AeadMemoryCrypto instrumented = new AeadMemoryCrypto(keys, recorder);
    final MemoryCryptoPort.Sealed sealed = instrumented.seal(ACME_ALICE, "a secret body");
    instrumented.unseal(ACME_ALICE, sealed.ciphertext(), sealed.keyRef());
    instrumented.unseal(GLOBEX_TENANT, sealed.ciphertext(), sealed.keyRef());

    // Everything the port can carry, collected. Nothing in it is content, nonce, tag or key.
    assertThat(observed).containsExactlyInAnyOrder("2026-07", "AUTHENTICATION_FAILED");
    assertThat(watched.primaryVersion()).isEqualTo("2026-07");
  }

  @Test
  @DisplayName("T14 the sealed body carries no scope in the clear")
  void t14TheSealedBodyCarriesNoScopeInTheClear() {
    final MemoryCryptoPort.Sealed sealed = crypto.seal(ACME_ALICE, "body");
    final String raw =
        new String(CryptoFixtures.rawOf(sealed.ciphertext()), StandardCharsets.ISO_8859_1);

    // The scope is authenticated, not stored. An envelope on its own must not tell a disk thief
    // which tenant or which user it belongs to.
    assertThat(raw).doesNotContain("acme").doesNotContain("alice");
  }

  @Test
  @DisplayName("opening a foreign ciphertext costs the same as opening a corrupt one")
  void openingAForeignCiphertextCostsTheSameAsOpeningACorruptOne() {
    final MemoryCryptoPort.Sealed sealed = crypto.seal(ACME_ALICE, "x".repeat(4096));
    final String corrupted = CryptoFixtures.flipBit(sealed.ciphertext(), 40);

    for (int i = 0; i < 2000; i++) {
      crypto.unseal(GLOBEX_TENANT, sealed.ciphertext(), sealed.keyRef());
      crypto.unseal(ACME_ALICE, corrupted, sealed.keyRef());
    }

    final long foreign =
        timeOf(() -> crypto.unseal(GLOBEX_TENANT, sealed.ciphertext(), sealed.keyRef()));
    final long corrupt = timeOf(() -> crypto.unseal(ACME_ALICE, corrupted, sealed.keyRef()));
    final double ratio = (double) Math.max(foreign, corrupt) / Math.min(foreign, corrupt);

    // Both take the same path and fail at the same tag check, so the wall clock must not separate
    // "not yours" from "damaged". The bound is loose because this is a shared, unpinned machine —
    // it catches an order-of-magnitude difference, not a nanosecond one, and is not a substitute
    // for
    // a proper side-channel review (B39).
    assertThat(ratio).isLessThan(3.0);
  }

  /**
   * Times a block over enough repetitions to be readable.
   *
   * @param work the operation to time
   * @return elapsed nanoseconds for the batch
   */
  private static long timeOf(final Runnable work) {
    final long started = System.nanoTime();
    for (int i = 0; i < 5000; i++) {
      work.run();
    }
    return System.nanoTime() - started;
  }
}

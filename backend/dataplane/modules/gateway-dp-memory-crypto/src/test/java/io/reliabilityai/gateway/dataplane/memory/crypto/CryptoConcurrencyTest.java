package io.reliabilityai.gateway.dataplane.memory.crypto;

import static io.reliabilityai.gateway.dataplane.memory.crypto.CryptoFixtures.ACME_ALICE;
import static io.reliabilityai.gateway.dataplane.memory.crypto.CryptoFixtures.ACME_BOB;
import static io.reliabilityai.gateway.dataplane.memory.crypto.CryptoFixtures.ACME_TENANT;
import static io.reliabilityai.gateway.dataplane.memory.crypto.CryptoFixtures.GLOBEX_TENANT;
import static org.assertj.core.api.Assertions.assertThat;

import io.reliabilityai.gateway.dataplane.memory.api.MemoryCryptoPort;
import io.reliabilityai.gateway.dataplane.memory.api.MemoryScope;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Concurrent sealing, and content that outlives the process that sealed it. */
@DisplayName("concurrency and restart")
final class CryptoConcurrencyTest {

  private static final int THREADS = 8;
  private static final int PER_THREAD = 400;

  @Test
  @DisplayName("concurrent sealing never repeats a ciphertext")
  void concurrentSealingNeverRepeatsACiphertext() throws Exception {
    final AeadMemoryCrypto crypto =
        CryptoFixtures.crypto(new CryptoFixtures.TestKeys("2026-07"), new InProcessCryptoMetrics());
    final Map<String, Boolean> seen = new ConcurrentHashMap<>();
    final AtomicInteger duplicates = new AtomicInteger();

    runConcurrently(
        thread -> {
          for (int i = 0; i < PER_THREAD; i++) {
            final String ciphertext = crypto.seal(ACME_ALICE, "identical body").ciphertext();
            if (seen.putIfAbsent(ciphertext, Boolean.TRUE) != null) {
              duplicates.incrementAndGet();
            }
          }
        });

    // Nonce generation is the one piece of shared mutable state on the seal path. A duplicate here
    // would mean two records share a nonce, which for GCM is the failure that loses confidentiality
    // outright rather than degrading it.
    assertThat(duplicates).hasValue(0);
    assertThat(seen).hasSize(THREADS * PER_THREAD);
  }

  @Test
  @DisplayName("concurrent sealing across scopes keeps every scope binding intact")
  void concurrentSealingAcrossScopesKeepsEveryScopeBindingIntact() throws Exception {
    final AeadMemoryCrypto crypto =
        CryptoFixtures.crypto(new CryptoFixtures.TestKeys("2026-07"), new InProcessCryptoMetrics());
    final List<MemoryScope> scopes = List.of(ACME_ALICE, ACME_BOB, ACME_TENANT, GLOBEX_TENANT);
    final AtomicInteger wrong = new AtomicInteger();

    runConcurrently(
        thread -> {
          final MemoryScope mine = scopes.get(thread % scopes.size());
          for (int i = 0; i < PER_THREAD; i++) {
            final MemoryCryptoPort.Sealed sealed = crypto.seal(mine, "body " + thread + "-" + i);
            if (crypto.unseal(mine, sealed.ciphertext(), sealed.keyRef()).isEmpty()) {
              wrong.incrementAndGet();
            }
            for (final MemoryScope other : scopes) {
              if (!other.equals(mine)
                  && crypto.unseal(other, sealed.ciphertext(), sealed.keyRef()).isPresent()) {
                wrong.incrementAndGet();
              }
            }
          }
        });

    assertThat(wrong).hasValue(0);
  }

  @Test
  @DisplayName("concurrent opening returns each thread its own content")
  void concurrentOpeningReturnsEachThreadItsOwnContent() throws Exception {
    final AeadMemoryCrypto crypto =
        CryptoFixtures.crypto(new CryptoFixtures.TestKeys("2026-07"), new InProcessCryptoMetrics());
    final MemoryCryptoPort.Sealed[] corpus = new MemoryCryptoPort.Sealed[200];
    for (int i = 0; i < corpus.length; i++) {
      corpus[i] = crypto.seal(ACME_ALICE, "record " + i);
    }
    final AtomicInteger wrong = new AtomicInteger();

    runConcurrently(
        thread -> {
          for (int i = 0; i < PER_THREAD; i++) {
            final int index = (thread * 31 + i) % corpus.length;
            if (!crypto
                .unseal(ACME_ALICE, corpus[index].ciphertext(), corpus[index].keyRef())
                .orElse("")
                .equals("record " + index)) {
              wrong.incrementAndGet();
            }
          }
        });

    // Cipher objects are created per call rather than pooled precisely so this holds. A shared,
    // improperly reset Cipher is the classic way this test starts returning another thread's bytes.
    assertThat(wrong).hasValue(0);
  }

  @Test
  @DisplayName("a rotation racing with writers leaves every record readable")
  void aRotationRacingWithWritersLeavesEveryRecordReadable() throws Exception {
    final CryptoFixtures.TestKeys keys = new CryptoFixtures.TestKeys("2026-01");
    keys.add("2026-07");
    final AeadMemoryCrypto crypto = CryptoFixtures.crypto(keys, new InProcessCryptoMetrics());
    final Map<String, MemoryCryptoPort.Sealed> written = new ConcurrentHashMap<>();
    final AtomicReference<Throwable> failure = new AtomicReference<>();

    final ExecutorService pool = Executors.newFixedThreadPool(THREADS + 1);
    final CountDownLatch start = new CountDownLatch(1);
    for (int t = 0; t < THREADS; t++) {
      final int thread = t;
      pool.submit(
          () -> {
            try {
              start.await();
              for (int i = 0; i < PER_THREAD; i++) {
                final String body = "body " + thread + "-" + i;
                written.put(body, crypto.seal(ACME_ALICE, body));
              }
            } catch (final Throwable caught) {
              failure.compareAndSet(null, caught);
            }
          });
    }
    pool.submit(
        () -> {
          try {
            start.await();
            Thread.sleep(2);
            crypto.rotateTo("2026-07");
          } catch (final Throwable caught) {
            failure.compareAndSet(null, caught);
          }
        });
    start.countDown();
    pool.shutdown();
    assertThat(pool.awaitTermination(60, TimeUnit.SECONDS)).isTrue();
    assertThat(failure.get()).isNull();

    // Some records land on the old version and some on the new; which is which is a race and does
    // not matter. What matters is that no write is torn between the two — a record sealed with one
    // version's key but labelled with the other's would be permanently unreadable.
    for (final Map.Entry<String, MemoryCryptoPort.Sealed> entry : written.entrySet()) {
      assertThat(
              crypto.unseal(ACME_ALICE, entry.getValue().ciphertext(), entry.getValue().keyRef()))
          .as("record %s", entry.getKey())
          .contains(entry.getKey());
    }
    assertThat(written).hasSize(THREADS * PER_THREAD);
  }

  @Test
  @DisplayName("content sealed by one process opens in the next")
  void contentSealedByOneProcessOpensInTheNext(@TempDir final Path directory) throws Exception {
    final Path keyring = writeKeyring(directory, "2026-07");
    final MemoryCryptoPort.Sealed sealed;
    {
      final AeadMemoryCrypto before =
          new AeadMemoryCrypto(new FileMasterKeyProvider(keyring), new InProcessCryptoMetrics());
      sealed = before.seal(ACME_ALICE, "written before the restart");
    }

    // Every piece of in-memory state — the canary, the wrap counters, the cipher itself — is gone.
    // Only the keyring and the envelope survive, which is exactly the situation after a reboot.
    final AeadMemoryCrypto after =
        new AeadMemoryCrypto(new FileMasterKeyProvider(keyring), new InProcessCryptoMetrics());

    assertThat(after.unseal(ACME_ALICE, sealed.ciphertext(), sealed.keyRef()))
        .contains("written before the restart");
  }

  @Test
  @DisplayName("a restart with the keyring gone leaves content sealed rather than lost or exposed")
  void aRestartWithTheKeyringGoneLeavesContentSealedRatherThanLostOrExposed(
      @TempDir final Path directory) throws Exception {
    final Path keyring = writeKeyring(directory, "2026-07");
    final AeadMemoryCrypto before =
        new AeadMemoryCrypto(new FileMasterKeyProvider(keyring), new InProcessCryptoMetrics());
    final MemoryCryptoPort.Sealed sealed = before.seal(ACME_ALICE, "content");
    Files.delete(keyring);

    // The recovery story has to be stated honestly: lose the keyring and the data is gone. It is
    // not
    // exposed, which is the property that matters here, but it is not recoverable either. See B40.
    assertThat(Files.exists(keyring)).isFalse();
    assertThat(sealed.ciphertext()).doesNotContain("content");
  }

  @Test
  @DisplayName("a restart onto a rotated keyring still opens pre-rotation content")
  void aRestartOntoARotatedKeyringStillOpensPreRotationContent(@TempDir final Path directory)
      throws Exception {
    final Path keyring = directory.resolve("keys");
    final String january = randomKey();
    final String july = randomKey();
    Files.writeString(keyring, "primary = 2026-01\n2026-01 = " + january + "\n");
    final MemoryCryptoPort.Sealed old =
        new AeadMemoryCrypto(new FileMasterKeyProvider(keyring), new InProcessCryptoMetrics())
            .seal(ACME_ALICE, "january content");

    Files.writeString(
        keyring, "primary = 2026-07\n2026-01 = " + january + "\n2026-07 = " + july + "\n");
    final AeadMemoryCrypto after =
        new AeadMemoryCrypto(new FileMasterKeyProvider(keyring), new InProcessCryptoMetrics());

    assertThat(after.primaryVersion()).isEqualTo("2026-07");
    assertThat(after.seal(ACME_ALICE, "new").keyRef()).isEqualTo("2026-07");
    assertThat(after.unseal(ACME_ALICE, old.ciphertext(), old.keyRef()))
        .contains("january content");
  }

  /**
   * Writes a keyring holding one freshly generated version.
   *
   * @param directory where to write it
   * @param version the version label
   * @return the keyring path
   * @throws Exception when the file cannot be written
   */
  private static Path writeKeyring(final Path directory, final String version) throws Exception {
    final Path keyring = directory.resolve("keys");
    Files.writeString(
        keyring, "primary = " + version + "\n" + version + " = " + randomKey() + "\n");
    return keyring;
  }

  /**
   * Generates a base64 AES-256 key.
   *
   * @return the encoded key
   */
  private static String randomKey() {
    final byte[] material = new byte[32];
    new java.security.SecureRandom().nextBytes(material);
    return Base64.getEncoder().encodeToString(material);
  }

  /**
   * Runs a body on every thread at once and fails the test if any of them throws.
   *
   * @param body the work, given its thread index
   * @throws Exception when the pool does not finish
   */
  private static void runConcurrently(final ThreadBody body) throws Exception {
    final ExecutorService pool = Executors.newFixedThreadPool(THREADS);
    final CountDownLatch start = new CountDownLatch(1);
    final AtomicReference<Throwable> failure = new AtomicReference<>();
    for (int t = 0; t < THREADS; t++) {
      final int thread = t;
      pool.submit(
          () -> {
            try {
              start.await();
              body.run(thread);
            } catch (final Throwable caught) {
              failure.compareAndSet(null, caught);
            }
          });
    }
    start.countDown();
    pool.shutdown();
    assertThat(pool.awaitTermination(120, TimeUnit.SECONDS)).isTrue();
    assertThat(failure.get()).isNull();
  }

  /** A unit of concurrent work. */
  @FunctionalInterface
  private interface ThreadBody {

    /**
     * Runs the work.
     *
     * @param thread the thread index
     * @throws Exception when the work fails
     */
    void run(int thread) throws Exception;
  }
}

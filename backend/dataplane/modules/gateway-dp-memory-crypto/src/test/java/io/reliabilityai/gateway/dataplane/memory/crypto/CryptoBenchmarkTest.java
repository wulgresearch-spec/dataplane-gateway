package io.reliabilityai.gateway.dataplane.memory.crypto;

import static io.reliabilityai.gateway.dataplane.memory.crypto.CryptoFixtures.ACME_ALICE;
import static org.assertj.core.api.Assertions.assertThat;

import io.reliabilityai.gateway.dataplane.memory.api.MemoryCryptoPort;
import java.lang.management.ManagementFactory;
import java.lang.management.ThreadMXBean;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Measurements, printed so they can be read and checked rather than asserted into invisibility.
 *
 * <p>These are wall-clock numbers from one developer machine — a shared, unpinned, thermally
 * unconstrained Windows laptop running every other test in this repository. They are the right
 * order of magnitude and they are not a substitute for JMH on representative hardware. Every number
 * in AD-028 §9 came from a run of this class; none of them was estimated.
 *
 * <p>The assertions are deliberately loose. Their job is to fail if something becomes
 * pathologically slow — an accidental {@code SecureRandom.getInstanceStrong()} on the hot path,
 * say, which blocks on entropy — not to encode a performance target that a slower CI machine would
 * breach.
 */
@DisplayName("cryptography benchmarks")
final class CryptoBenchmarkTest {

  private static final int WARMUP = 5_000;
  private static final int MEASURED = 20_000;

  private AeadMemoryCrypto crypto;

  @BeforeEach
  void setUp() {
    crypto =
        CryptoFixtures.crypto(new CryptoFixtures.TestKeys("2026-07"), new InProcessCryptoMetrics());
  }

  @Test
  @DisplayName("seal latency by body size")
  void sealLatencyByBodySize() {
    for (final int size : new int[] {64, 512, 4_096, 32_768, 131_072}) {
      final String body = "x".repeat(size);
      for (int i = 0; i < WARMUP; i++) {
        crypto.seal(ACME_ALICE, body);
      }
      final int iterations = size > 32_768 ? MEASURED / 10 : MEASURED;
      final long started = System.nanoTime();
      for (int i = 0; i < iterations; i++) {
        crypto.seal(ACME_ALICE, body);
      }
      final long elapsed = System.nanoTime() - started;
      report("seal " + size + "B", elapsed, iterations);
      assertThat(elapsed / iterations).isLessThan(TimeUnit.MILLISECONDS.toNanos(50));
    }
  }

  @Test
  @DisplayName("unseal latency by body size")
  void unsealLatencyByBodySize() {
    for (final int size : new int[] {64, 512, 4_096, 32_768, 131_072}) {
      final MemoryCryptoPort.Sealed sealed = crypto.seal(ACME_ALICE, "x".repeat(size));
      for (int i = 0; i < WARMUP; i++) {
        crypto.unseal(ACME_ALICE, sealed.ciphertext(), sealed.keyRef());
      }
      final int iterations = size > 32_768 ? MEASURED / 10 : MEASURED;
      final long started = System.nanoTime();
      for (int i = 0; i < iterations; i++) {
        crypto.unseal(ACME_ALICE, sealed.ciphertext(), sealed.keyRef());
      }
      final long elapsed = System.nanoTime() - started;
      report("unseal " + size + "B", elapsed, iterations);
      assertThat(elapsed / iterations).isLessThan(TimeUnit.MILLISECONDS.toNanos(50));
    }
  }

  @Test
  @DisplayName("single-threaded throughput at a realistic body size")
  void singleThreadedThroughputAtARealisticBodySize() {
    final String body = "x".repeat(1_024);
    for (int i = 0; i < WARMUP; i++) {
      crypto.seal(ACME_ALICE, body);
    }
    final long started = System.nanoTime();
    for (int i = 0; i < MEASURED; i++) {
      crypto.seal(ACME_ALICE, body);
    }
    final long elapsed = System.nanoTime() - started;
    report("seal 1KiB single-threaded", elapsed, MEASURED);
  }

  @Test
  @DisplayName("concurrent throughput scales with cores")
  void concurrentThroughputScalesWithCores() throws Exception {
    final int threads = 8;
    final int perThread = 10_000;
    final String body = "x".repeat(1_024);
    for (int i = 0; i < WARMUP; i++) {
      crypto.seal(ACME_ALICE, body);
    }

    final ExecutorService pool = Executors.newFixedThreadPool(threads);
    final CountDownLatch start = new CountDownLatch(1);
    final AtomicLong nanos = new AtomicLong();
    for (int t = 0; t < threads; t++) {
      pool.submit(
          () -> {
            try {
              start.await();
              final long began = System.nanoTime();
              for (int i = 0; i < perThread; i++) {
                crypto.seal(ACME_ALICE, body);
              }
              nanos.addAndGet(System.nanoTime() - began);
            } catch (final InterruptedException interrupted) {
              Thread.currentThread().interrupt();
            }
          });
    }
    final long wallStarted = System.nanoTime();
    start.countDown();
    pool.shutdown();
    assertThat(pool.awaitTermination(300, TimeUnit.SECONDS)).isTrue();
    final long wall = System.nanoTime() - wallStarted;

    report("seal 1KiB across " + threads + " threads (wall)", wall, threads * perThread);
    System.out.printf(
        "  BENCH  cpu-time per op across %d threads: %.2f us%n",
        threads, nanos.get() / (double) (threads * perThread) / 1000.0);
  }

  @Test
  @DisplayName("allocation per seal and per unseal")
  void allocationPerSealAndPerUnseal() {
    final ThreadMXBean bean = ManagementFactory.getThreadMXBean();
    if (!(bean instanceof com.sun.management.ThreadMXBean sun)
        || !sun.isThreadAllocatedMemoryEnabled()) {
      System.out.println("  BENCH  allocation measurement unavailable on this JVM; skipped");
      return;
    }
    final long id = Thread.currentThread().threadId();
    final String body = "x".repeat(1_024);
    final MemoryCryptoPort.Sealed sealed = crypto.seal(ACME_ALICE, body);
    for (int i = 0; i < WARMUP; i++) {
      crypto.unseal(ACME_ALICE, crypto.seal(ACME_ALICE, body).ciphertext(), sealed.keyRef());
    }

    final int iterations = 20_000;
    long before = sun.getThreadAllocatedBytes(id);
    for (int i = 0; i < iterations; i++) {
      crypto.seal(ACME_ALICE, body);
    }
    final long perSeal = (sun.getThreadAllocatedBytes(id) - before) / iterations;

    before = sun.getThreadAllocatedBytes(id);
    for (int i = 0; i < iterations; i++) {
      crypto.unseal(ACME_ALICE, sealed.ciphertext(), sealed.keyRef());
    }
    final long perUnseal = (sun.getThreadAllocatedBytes(id) - before) / iterations;

    System.out.printf("  BENCH  allocation per seal (1KiB body):   %d bytes%n", perSeal);
    System.out.printf("  BENCH  allocation per unseal (1KiB body): %d bytes%n", perUnseal);

    // A Cipher instance per call is a deliberate choice — see AD-028 §9.2 — but if it ever starts
    // costing tens of kilobytes per operation, that choice needs revisiting.
    assertThat(perSeal).isLessThan(65_536);
    assertThat(perUnseal).isLessThan(65_536);
  }

  @Test
  @DisplayName("storage overhead per record")
  void storageOverheadPerRecord() {
    for (final int size : new int[] {0, 64, 1_024, 32_768}) {
      final String body = "x".repeat(size);
      final String ciphertext = crypto.seal(ACME_ALICE, body).ciphertext();
      final int rawBytes = CryptoFixtures.rawOf(ciphertext).length;
      System.out.printf(
          "  BENCH  body %6dB -> envelope %6dB raw, %6dB base64 (overhead %d raw, %.1f%% encoded)%n",
          size,
          rawBytes,
          ciphertext.length(),
          rawBytes - size,
          size == 0 ? 0.0 : 100.0 * (ciphertext.length() - size) / size);

      // The fixed overhead is the envelope header plus two GCM tags plus the wrapped key. It must
      // not
      // grow with the body, or large records pay twice.
      assertThat(rawBytes - size).isEqualTo(102);
    }
  }

  @Test
  @DisplayName("rotation cost measured as rewrap throughput")
  void rotationCostMeasuredAsRewrapThroughput() {
    final CryptoFixtures.TestKeys keys = new CryptoFixtures.TestKeys("2026-01");
    final AeadMemoryCrypto rotating = CryptoFixtures.crypto(keys, new InProcessCryptoMetrics());
    final int records = 20_000;
    final List<MemoryCryptoPort.Sealed> corpus = new ArrayList<>(records);
    final String body = "x".repeat(1_024);
    for (int i = 0; i < records; i++) {
      corpus.add(rotating.seal(ACME_ALICE, body));
    }
    keys.add("2026-07");

    final long rotateStarted = System.nanoTime();
    rotating.rotateTo("2026-07");
    final long rotateElapsed = System.nanoTime() - rotateStarted;

    final long started = System.nanoTime();
    long rewrapped = 0L;
    for (int i = 0; i < records; i++) {
      // The result is consumed rather than discarded. A benchmark loop whose return value is
      // thrown away can be optimised into nothing, which would measure an empty loop and report it
      // as a rewrap rate.
      rewrapped +=
          rotating
              .rewrap(ACME_ALICE, corpus.get(i).ciphertext(), corpus.get(i).keyRef())
              .orElseThrow()
              .ciphertext()
              .length();
    }
    final long elapsed = System.nanoTime() - started;
    assertThat(rewrapped).isPositive();

    System.out.printf(
        "  BENCH  rotateTo itself: %.1f us (a volatile write)%n", rotateElapsed / 1000.0);
    report("rewrap 1KiB record", elapsed, records);
    System.out.printf(
        "  BENCH  projected migration of 1,000,000 records: %.1f s single-threaded%n",
        elapsed / (double) records * 1_000_000 / 1e9);
  }

  /**
   * Prints one measurement.
   *
   * @param label what was measured
   * @param elapsedNanos how long the batch took
   * @param iterations how many operations were in it
   */
  private static void report(final String label, final long elapsedNanos, final int iterations) {
    final double perOp = elapsedNanos / (double) iterations;
    System.out.printf(
        "  BENCH  %-40s %8.2f us/op  %12.0f ops/sec%n", label, perOp / 1000.0, 1e9 / perOp);
  }
}

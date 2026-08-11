package io.reliabilityai.gateway.dataplane.memory.pii;

import static org.assertj.core.api.Assertions.assertThat;

import io.reliabilityai.gateway.canonical.identity.TenantScope;
import java.lang.management.ManagementFactory;
import java.lang.management.ThreadMXBean;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Measurements, printed rather than asserted into invisibility.
 *
 * <p>Wall-clock numbers from one 16-core Windows laptop running every other test in this
 * repository. The right order of magnitude, not a JMH result. Every figure in AD-029 §9 came from a
 * run of this class; none was estimated.
 */
@DisplayName("detection benchmarks")
final class PiiBenchmarkTest {

  private static final int WARMUP = 2_000;
  private static final int MEASURED = 10_000;

  private static final String CLEAN =
      "The quarterly report covers operations across all regions and summarises the "
          + "position at the close of the period without reference to any individual.";

  private static final String DENSE =
      "Contact alice@example.com or call 555-123-4567. Card 4111 1111 1111 1111 on file. "
          + "Server 10.0.0.7. DOB 1985-03-21. Patient id: PT-99321. MRN: A9928311.";

  private final PiiDetectionEngine engine =
      new PiiDetectionEngine(PiiRuleCompiler.builtIn(), PiiMetricsPort.NOOP);

  @Test
  @DisplayName("detection latency by body size and density")
  void detectionLatencyByBodySizeAndDensity() {
    for (final int size : new int[] {128, 1_024, 8_192, 65_536}) {
      report("clean " + size + "B", body(CLEAN, size));
      report("dense " + size + "B", body(DENSE, size));
    }
  }

  @Test
  @DisplayName("throughput in characters per second")
  void throughputInCharactersPerSecond() {
    final String body = body(CLEAN, 65_536);
    for (int i = 0; i < 200; i++) {
      engine.detect(body);
    }
    final int iterations = 200;
    final long started = System.nanoTime();
    for (int i = 0; i < iterations; i++) {
      engine.detect(body);
    }
    final long elapsed = System.nanoTime() - started;
    System.out.printf(
        "  BENCH  scan throughput: %.1f MB/s%n",
        (double) body.length() * iterations / (elapsed / 1e9) / 1e6);
  }

  @Test
  @DisplayName("allocation per scan")
  void allocationPerScan() {
    final ThreadMXBean bean = ManagementFactory.getThreadMXBean();
    if (!(bean instanceof com.sun.management.ThreadMXBean sun)
        || !sun.isThreadAllocatedMemoryEnabled()) {
      System.out.println("  BENCH  allocation measurement unavailable on this JVM; skipped");
      return;
    }
    final long id = Thread.currentThread().threadId();
    for (final String label : new String[] {"clean", "dense"}) {
      final String body = body("clean".equals(label) ? CLEAN : DENSE, 1_024);
      for (int i = 0; i < WARMUP; i++) {
        engine.detect(body);
      }
      final long before = sun.getThreadAllocatedBytes(id);
      for (int i = 0; i < MEASURED; i++) {
        engine.detect(body);
      }
      final long perScan = (sun.getThreadAllocatedBytes(id) - before) / MEASURED;
      System.out.printf("  BENCH  allocation per %s 1KiB scan: %d bytes%n", label, perScan);
      assertThat(perScan).isLessThan(1_048_576);
    }
  }

  @Test
  @DisplayName("rule compilation cost")
  void ruleCompilationCost() {
    for (int i = 0; i < 50; i++) {
      PiiRuleCompiler.builtIn();
    }
    final int iterations = 200;
    final long started = System.nanoTime();
    for (int i = 0; i < iterations; i++) {
      PiiRuleCompiler.builtIn();
    }
    final long elapsed = System.nanoTime() - started;
    final PiiRuleSet set = PiiRuleCompiler.builtIn();
    System.out.printf(
        "  BENCH  compiling %d built-in rules: %.2f us%n",
        set.size(), elapsed / (double) iterations / 1000.0);

    // Compilation happens at install time, never on the scan path. This measures what an operator
    // waits for when installing rules, not what a write costs.
    final List<PiiRule> many = new ArrayList<>(BuiltInRules.all());
    for (int i = 0; i < 500; i++) {
      many.add(
          PiiRule.detecting(
              "bulk." + i, PiiType.CUSTOM, "MARKER-" + i + "(?![0-9])", PiiValidator.NONE));
    }
    final long bulkStarted = System.nanoTime();
    final PiiRuleSet bulk = PiiRuleCompiler.compile(many);
    System.out.printf(
        "  BENCH  compiling %d rules: %.2f ms%n",
        bulk.size(), (System.nanoTime() - bulkStarted) / 1e6);
  }

  @Test
  @DisplayName("scan cost as the rule count grows")
  void scanCostAsTheRuleCountGrows() {
    final String body = body(CLEAN, 4_096);
    for (final int extra : new int[] {0, 100, 300}) {
      final List<PiiRule> rules = new ArrayList<>(BuiltInRules.all());
      for (int i = 0; i < extra; i++) {
        rules.add(
            PiiRule.detecting(
                "bulk." + i, PiiType.CUSTOM, "MARKER-" + i + "(?![0-9])", PiiValidator.NONE));
      }
      final PiiDetectionEngine wide =
          new PiiDetectionEngine(PiiRuleCompiler.compile(rules), PiiMetricsPort.NOOP);
      for (int i = 0; i < 200; i++) {
        wide.detect(body);
      }
      final int iterations = 300;
      final long started = System.nanoTime();
      for (int i = 0; i < iterations; i++) {
        wide.detect(body);
      }
      // Linear in rule count: every rule is a separate pass. AD-029 §10.5 records that a combined
      // automaton would not be, and that it was not built.
      System.out.printf(
          "  BENCH  scan 4KiB with %d rules: %.2f us/op%n",
          BuiltInRules.all().size() + extra,
          (System.nanoTime() - started) / (double) iterations / 1000.0);
    }
  }

  @Test
  @DisplayName("concurrent scan throughput")
  void concurrentScanThroughput() throws Exception {
    final String body = body(DENSE, 4_096);
    for (int i = 0; i < WARMUP; i++) {
      engine.detect(body);
    }
    final int threads = 8;
    final int perThread = 1_000;
    final ExecutorService pool = Executors.newFixedThreadPool(threads);
    final CountDownLatch start = new CountDownLatch(1);
    for (int t = 0; t < threads; t++) {
      pool.submit(
          () -> {
            try {
              start.await();
              for (int i = 0; i < perThread; i++) {
                engine.detect(body);
              }
            } catch (final InterruptedException interrupted) {
              Thread.currentThread().interrupt();
            }
          });
    }
    final long started = System.nanoTime();
    start.countDown();
    pool.shutdown();
    assertThat(pool.awaitTermination(300, TimeUnit.SECONDS)).isTrue();
    final long elapsed = System.nanoTime() - started;
    System.out.printf(
        "  BENCH  scan 4KiB across %d threads: %.2f us/op  %.0f scans/sec%n",
        threads,
        elapsed / (double) (threads * perThread) / 1000.0,
        (double) threads * perThread / (elapsed / 1e9));
  }

  @Test
  @DisplayName("registry lookup cost on the write path")
  void registryLookupCostOnTheWritePath() {
    final PiiRuleRegistry registry = new PiiRuleRegistry(PiiMetricsPort.NOOP);
    for (int i = 0; i < 200; i++) {
      registry.installTenantRules(TenantScope.of("org" + i, "core"), List.of());
    }
    final TenantScope tenant = TenantScope.of("org100", "core");
    for (int i = 0; i < 100_000; i++) {
      registry.engineFor(tenant);
    }
    final int iterations = 2_000_000;
    final long started = System.nanoTime();
    for (int i = 0; i < iterations; i++) {
      registry.engineFor(tenant);
    }
    System.out.printf(
        "  BENCH  registry lookup: %.1f ns/op%n",
        (System.nanoTime() - started) / (double) iterations);
  }

  /**
   * Times a scan and prints it.
   *
   * @param label what is being measured
   * @param body the text to scan
   */
  private void report(final String label, final String body) {
    for (int i = 0; i < 200; i++) {
      engine.detect(body);
    }
    final int iterations = body.length() > 16_000 ? 200 : MEASURED;
    final long started = System.nanoTime();
    for (int i = 0; i < iterations; i++) {
      engine.detect(body);
    }
    final long elapsed = System.nanoTime() - started;
    final double perOp = elapsed / (double) iterations;
    System.out.printf(
        "  BENCH  %-20s %9.2f us/op  %10.0f scans/sec%n", label, perOp / 1000.0, 1e9 / perOp);
    assertThat(perOp).isLessThan(TimeUnit.SECONDS.toNanos(1));
  }

  /**
   * Repeats a seed until it reaches roughly the requested length.
   *
   * @param seed the text to repeat
   * @param size the target length
   * @return the body
   */
  private static String body(final String seed, final int size) {
    final StringBuilder builder = new StringBuilder(size + seed.length());
    while (builder.length() < size) {
      builder.append(seed).append(' ');
    }
    return builder.toString();
  }
}

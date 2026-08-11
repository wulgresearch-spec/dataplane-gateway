package io.reliabilityai.gateway.dataplane.memory.pii;

import static org.assertj.core.api.Assertions.assertThat;

import io.reliabilityai.gateway.canonical.identity.TenantScope;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Concurrent scanning, rule installation under load, and reproducibility across engine instances.
 */
@DisplayName("concurrency and reproducibility")
final class PiiConcurrencyTest {

  private static final int THREADS = 8;
  private static final int PER_THREAD = 500;
  private static final TenantScope ACME = TenantScope.of("acme", "core");
  private static final TenantScope GLOBEX = TenantScope.of("globex", "core");

  private static final String BODY =
      "alice@example.com, 555-123-4567, card 4111 1111 1111 1111, host 10.0.0.7, dob 1985-03-21";

  @Test
  @DisplayName("concurrent scans of one body all agree")
  void concurrentScansOfOneBodyAllAgree() throws Exception {
    final PiiDetectionEngine engine =
        new PiiDetectionEngine(PiiRuleCompiler.builtIn(), PiiMetricsPort.NOOP);
    final PiiDetection expected = engine.detect(BODY);
    final AtomicInteger disagreements = new AtomicInteger();

    run(
        thread -> {
          for (int i = 0; i < PER_THREAD; i++) {
            final PiiDetection actual = engine.detect(BODY);
            if (!actual.spans().equals(expected.spans())
                || actual.severity() != expected.severity()) {
              disagreements.incrementAndGet();
            }
          }
        });

    // The engine holds no mutable state after construction, so this is really a test that it stays
    // that way — a cached Matcher or a shared StringBuilder would surface here and nowhere else.
    assertThat(disagreements).hasValue(0);
  }

  @Test
  @DisplayName("concurrent scans of different bodies do not contaminate each other")
  void concurrentScansOfDifferentBodiesDoNotContaminateEachOther() throws Exception {
    final PiiDetectionEngine engine =
        new PiiDetectionEngine(PiiRuleCompiler.builtIn(), PiiMetricsPort.NOOP);
    final AtomicInteger wrong = new AtomicInteger();

    run(
        thread -> {
          final String mine =
              switch (thread % 4) {
                case 0 -> "alice@example.com only";
                case 1 -> "card 4111 1111 1111 1111 only";
                case 2 -> "host 10.0.0.7 only";
                default -> "nothing sensitive here at all";
              };
          final Set<PiiType> expected =
              switch (thread % 4) {
                case 0 -> Set.of(PiiType.EMAIL);
                case 1 -> Set.of(PiiType.CREDIT_CARD);
                case 2 -> Set.of(PiiType.IP_ADDRESS);
                default -> Set.of();
              };
          for (int i = 0; i < PER_THREAD; i++) {
            if (!engine.detect(mine).types().equals(expected)) {
              wrong.incrementAndGet();
            }
          }
        });

    assertThat(wrong).hasValue(0);
  }

  @Test
  @DisplayName("installing rules for one tenant does not disturb scans running for another")
  void installingRulesForOneTenantDoesNotDisturbScansRunningForAnother() throws Exception {
    final PiiRuleRegistry registry = new PiiRuleRegistry(PiiMetricsPort.NOOP);
    final AtomicInteger wrong = new AtomicInteger();
    final AtomicReference<Throwable> failure = new AtomicReference<>();
    final ExecutorService pool = Executors.newFixedThreadPool(THREADS + 1);
    final CountDownLatch start = new CountDownLatch(1);

    for (int t = 0; t < THREADS; t++) {
      pool.submit(
          () -> {
            try {
              start.await();
              for (int i = 0; i < PER_THREAD; i++) {
                if (!registry
                    .engineFor(GLOBEX)
                    .detect("alice@example.com")
                    .types()
                    .equals(Set.of(PiiType.EMAIL))) {
                  wrong.incrementAndGet();
                }
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
            for (int i = 0; i < 50; i++) {
              registry.installTenantRules(
                  ACME,
                  List.of(
                      PiiRule.detecting(
                              "acme.ticket." + i,
                              PiiType.CUSTOM,
                              "(?<![A-Za-z0-9])TKT-\\d{5}(?![A-Za-z0-9])",
                              PiiValidator.NONE)
                          .withOrigin(PiiRule.Origin.TENANT, 0)));
            }
          } catch (final Throwable caught) {
            failure.compareAndSet(null, caught);
          }
        });

    start.countDown();
    pool.shutdown();
    assertThat(pool.awaitTermination(120, TimeUnit.SECONDS)).isTrue();
    assertThat(failure.get()).isNull();
    // Rules compile at install time and swap by reference. A write must never block behind an
    // administrator installing rules for someone else.
    assertThat(wrong).hasValue(0);
  }

  @Test
  @DisplayName("a scan already running keeps the rule set it started with")
  void aScanAlreadyRunningKeepsTheRuleSetItStartedWith() {
    final PiiRuleRegistry registry = new PiiRuleRegistry(PiiMetricsPort.NOOP);
    final PiiDetectionEngine before = registry.engineFor(ACME);
    final long versionBefore = before.rules().version();

    registry.installTenantRules(
        ACME,
        List.of(
            PiiRule.detecting("acme.new", PiiType.CUSTOM, "CANARY", PiiValidator.NONE)
                .withOrigin(PiiRule.Origin.TENANT, 0)));

    // The old engine is still consistent and still says what it said; the new one differs. This is
    // why PiiDetection carries the rule set version — two records written seconds apart may
    // legitimately have been classified by different rules, and the record says which.
    assertThat(before.detect("CANARY").any()).isFalse();
    assertThat(before.rules().version()).isEqualTo(versionBefore);
    assertThat(registry.engineFor(ACME).detect("CANARY").any()).isTrue();
  }

  @Test
  @DisplayName("a freshly compiled engine reproduces an earlier one exactly")
  void aFreshlyCompiledEngineReproducesAnEarlierOneExactly() {
    final PiiDetection first =
        new PiiDetectionEngine(PiiRuleCompiler.builtIn(), PiiMetricsPort.NOOP).detect(BODY);
    final PiiDetection second =
        new PiiDetectionEngine(PiiRuleCompiler.builtIn(), PiiMetricsPort.NOOP).detect(BODY);

    // The restart case. Rule set versions differ because they are per-compilation, but every
    // classification decision must be identical — otherwise a record re-classified after a restart
    // could change severity with no rule having changed.
    assertThat(second.spans()).isEqualTo(first.spans());
    assertThat(second.severity()).isEqualTo(first.severity());
    assertThat(second.categories()).isEqualTo(first.categories());
    assertThat(second.countsByType()).isEqualTo(first.countsByType());
  }

  @Test
  @DisplayName("many distinct tenants each keep their own rules")
  void manyDistinctTenantsEachKeepTheirOwnRules() throws Exception {
    final PiiRuleRegistry registry = new PiiRuleRegistry(PiiMetricsPort.NOOP);
    final ConcurrentHashMap<Integer, Boolean> wrong = new ConcurrentHashMap<>();
    for (int i = 0; i < 64; i++) {
      registry.installTenantRules(
          TenantScope.of("org" + i, "core"),
          List.of(
              PiiRule.detecting(
                      "t.marker", PiiType.CUSTOM, "MARKER-" + i + "(?![0-9])", PiiValidator.NONE)
                  .withOrigin(PiiRule.Origin.TENANT, 0)));
    }

    run(
        thread -> {
          for (int i = thread; i < 64; i += THREADS) {
            final var engine = registry.engineFor(TenantScope.of("org" + i, "core"));
            if (!engine.detect("MARKER-" + i).any()) {
              wrong.put(i, true);
            }
          }
        });

    assertThat(wrong).isEmpty();
    assertThat(registry.tenantCount()).isEqualTo(64);
  }

  /**
   * Runs a body on every thread at once and fails the test if any of them throws.
   *
   * @param body the work, given its thread index
   * @throws Exception when the pool does not finish
   */
  private static void run(final ThreadBody body) throws Exception {
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
    assertThat(pool.awaitTermination(180, TimeUnit.SECONDS)).isTrue();
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

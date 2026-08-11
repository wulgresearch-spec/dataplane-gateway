package io.reliabilityai.gateway.dataplane.memory.pii;

import static org.assertj.core.api.Assertions.assertThat;

import io.reliabilityai.gateway.canonical.identity.TenantScope;
import io.reliabilityai.gateway.dataplane.memory.api.DataClassification;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Denial of service, leakage, and what happens when a scan cannot finish. */
@DisplayName("engine security")
final class PiiSecurityTest {

  private static final TenantScope ACME = TenantScope.of("acme", "core");

  private InProcessPiiMetrics metrics;
  private PiiRuleRegistry registry;

  @BeforeEach
  void setUp() {
    metrics = new InProcessPiiMetrics();
    registry = new PiiRuleRegistry(metrics);
  }

  /**
   * A rule whose pattern backtracks exponentially.
   *
   * @return the hostile rule
   */
  private static PiiRule catastrophicRule() {
    return new PiiRule(
        "tenant.evil",
        1,
        0,
        PiiRule.Origin.TENANT,
        PiiRule.Effect.DENY,
        PiiType.CUSTOM,
        PiiCategory.BUSINESS_CONFIDENTIAL,
        PiiSeverity.LOW,
        0.9,
        // Measured, not assumed. Java 21 dispatches the textbook (a+)+$ in about five
        // milliseconds -- its engine has optimisations that defeat that particular shape --
        // so using it here would have tested nothing. This one does not finish at all
        // unbounded: a probe against the same input ran for over three minutes before being
        // killed.
        "(.*a){12}$",
        false,
        PiiValidator.NONE,
        Set.of());
  }

  @Test
  @DisplayName("a catastrophically backtracking tenant rule is bounded rather than hanging")
  void aCatastrophicallyBacktrackingTenantRuleIsBoundedRatherThanHanging() {
    registry.installTenantRules(ACME, List.of(catastrophicRule()));
    final String hostile = "a".repeat(40) + "!";

    final long started = System.nanoTime();
    final PiiDetection detection = registry.engineFor(ACME).detect(hostile);
    final long elapsed = System.nanoTime() - started;

    // Unbounded, this pattern against this input does not finish in any human timeframe. The step
    // budget converts a permanent hang into a bounded, reported failure. Without it, any tenant
    // able
    // to install a rule can stop every write on the node.
    assertThat(elapsed).isLessThan(TimeUnit.SECONDS.toNanos(5));
    assertThat(detection.budgetExhausted()).isTrue();
    assertThat(metrics.count("budgetExhausted")).isEqualTo(1);
  }

  @Test
  @DisplayName("one runaway rule does not abandon the other rules")
  void oneRunawayRuleDoesNotAbandonTheOtherRules() {
    registry.installTenantRules(ACME, List.of(catastrophicRule()));

    final PiiDetection detection =
        registry.engineFor(ACME).detect("a".repeat(40) + "! mail alice@example.com");

    // The budget is per rule, not per document: a document with forty rules must not fail the
    // fortieth because one of the first thirty-nine misbehaved.
    assertThat(detection.types()).contains(PiiType.EMAIL);
    assertThat(detection.budgetExhausted()).isTrue();
  }

  @Test
  @DisplayName("an incomplete scan classifies as unclassified, never as clean")
  void anIncompleteScanClassifiesAsUnclassifiedNeverAsClean() {
    registry.installTenantRules(ACME, List.of(catastrophicRule()));
    final RuleBasedPiiClassifier classifier = new RuleBasedPiiClassifier(registry);

    final var result = classifier.classify(ACME, "a".repeat(40) + "!");

    // The fail-open version of this bug is subtle and total: a body engineered to be long enough,
    // or
    // to trip a pathological rule, would come back "nothing found" and sail past governance.
    // UNCLASSIFIED fails the write closed instead (MEM-21).
    assertThat(result.classification()).isEqualTo(DataClassification.UNCLASSIFIED);
    assertThat(result.classification().established()).isFalse();
    assertThat(result.confident()).isFalse();
  }

  @Test
  @DisplayName("a body longer than the scan bound is marked truncated, not clean")
  void aBodyLongerThanTheScanBoundIsMarkedTruncatedNotClean() {
    final PiiDetectionEngine bounded =
        new PiiDetectionEngine(PiiRuleCompiler.builtIn(), metrics, 64, 0.5);

    final PiiDetection detection = bounded.detect("x".repeat(200) + " alice@example.com");

    assertThat(detection.truncated()).isTrue();
    assertThat(detection.complete()).isFalse();
    assertThat(metrics.count("truncated")).isEqualTo(1);
    // The email past the bound was never read. Reporting "clean" here is exactly the fail-open
    // above.
    assertThat(new RuleBasedPiiClassifier(registry).classify(null, "y".repeat(10)).classification())
        .isNotEqualTo(DataClassification.UNCLASSIFIED);
  }

  @Test
  @DisplayName("a large document is scanned in bounded time")
  void aLargeDocumentIsScannedInBoundedTime() {
    final StringBuilder large = new StringBuilder();
    while (large.length() < 100_000) {
      large.append("The quarterly report covers operations across all regions. ");
    }
    large.append(" contact alice@example.com");

    final long started = System.nanoTime();
    final PiiDetection detection = registry.defaultEngine().detect(large.toString());
    final long elapsed = System.nanoTime() - started;

    assertThat(detection.types()).contains(PiiType.EMAIL);
    assertThat(elapsed).isLessThan(TimeUnit.SECONDS.toNanos(10));
  }

  @Test
  @DisplayName("a document of pure digits does not explode")
  void aDocumentOfPureDigitsDoesNotExplode() {
    final String digits = "1234567890".repeat(1_000);

    final long started = System.nanoTime();
    registry.defaultEngine().detect(digits);
    final long elapsed = System.nanoTime() - started;

    // Digit runs are the adversarial input for this rule set — every numeric pattern engages.
    assertThat(elapsed).isLessThan(TimeUnit.SECONDS.toNanos(10));
  }

  @Test
  @DisplayName("metrics carry no scanned content")
  void metricsCarryNoScannedContent() {
    final StringBuilder observed = new StringBuilder();
    final PiiMetricsPort recorder =
        new PiiMetricsPort() {
          @Override
          public void scanned(final int characters, final long nanos) {}

          @Override
          public void detected(final PiiType type) {
            observed.append(type.name()).append(' ');
          }

          @Override
          public void validatorRejected(final PiiType type) {
            observed.append(type.name()).append(' ');
          }

          @Override
          public void droppedBelowConfidence(final PiiType type) {
            observed.append(type.name()).append(' ');
          }

          @Override
          public void suppressedByAllowRule(final String ruleId) {
            observed.append(ruleId).append(' ');
          }

          @Override
          public void budgetExhausted(final String ruleId) {
            observed.append(ruleId).append(' ');
          }

          @Override
          public void truncated() {}
        };

    new PiiDetectionEngine(PiiRuleCompiler.builtIn(), recorder)
        .detect("alice@example.com card 4111 1111 1111 1111 and 4111 1111 1111 1112");

    // Everything the port can emit, collected. A metrics port that could carry "what was found"
    // would export the very data the engine exists to protect, to the least protected destination a
    // service has.
    assertThat(observed.toString()).doesNotContain("alice").doesNotContain("4111");
  }

  @Test
  @DisplayName("a compilation failure names the rule and never the scanned data")
  void aCompilationFailureNamesTheRuleAndNeverTheScannedData() {
    final PiiRule broken =
        PiiRule.detecting("tenant.broken", PiiType.CUSTOM, "([", PiiValidator.NONE);

    assertThat(
            org.assertj.core.api.Assertions.catchThrowable(
                    () -> PiiRuleCompiler.compile(List.of(broken)))
                .getMessage())
        .contains("tenant.broken");
  }

  @Test
  @DisplayName("one tenant's rules never reach another tenant")
  void oneTenantsRulesNeverReachAnotherTenant() {
    registry.installTenantRules(
        TenantScope.of("acme", "core"),
        List.of(
            new PiiRule(
                "acme.secret",
                1,
                0,
                PiiRule.Origin.TENANT,
                PiiRule.Effect.DENY,
                PiiType.CUSTOM,
                PiiCategory.LEGAL,
                PiiSeverity.CRITICAL,
                0.9,
                "PROJECT-VULCAN",
                false,
                PiiValidator.NONE,
                Set.of())));

    assertThat(registry.engineFor(TenantScope.of("acme", "core")).detect("PROJECT-VULCAN").any())
        .isTrue();
    // A tenant's rule list is itself sensitive: it names what that tenant considers secret.
    assertThat(registry.engineFor(TenantScope.of("globex", "core")).detect("PROJECT-VULCAN").any())
        .isFalse();
    assertThat(registry.engineFor(TenantScope.of("acme", "staging")).detect("PROJECT-VULCAN").any())
        .isFalse();
  }

  @Test
  @DisplayName("tenant keys cannot be confused by cunningly chosen names")
  void tenantKeysCannotBeConfusedByCunninglyChosenNames() {
    final PiiRule rule =
        new PiiRule(
            "t.rule",
            1,
            0,
            PiiRule.Origin.TENANT,
            PiiRule.Effect.DENY,
            PiiType.CUSTOM,
            PiiCategory.LEGAL,
            PiiSeverity.HIGH,
            0.9,
            "CANARY",
            false,
            PiiValidator.NONE,
            Set.of());
    registry.installTenantRules(TenantScope.of("a", "bc"), List.of(rule));

    // "a" + "bc" and "ab" + "c" concatenate identically. Length-prefixing keeps them apart; without
    // it, one tenant's rules would silently apply to another's data.
    assertThat(registry.engineFor(TenantScope.of("a", "bc")).detect("CANARY").any()).isTrue();
    assertThat(registry.engineFor(TenantScope.of("ab", "c")).detect("CANARY").any()).isFalse();
  }
}

package io.reliabilityai.gateway.dataplane.memory.pii;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.reliabilityai.gateway.canonical.identity.TenantScope;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Layered rule sets: who overrides whom, and what the compiler refuses. */
@DisplayName("rule engine")
final class PiiRuleEngineTest {

  private static final TenantScope ACME = TenantScope.of("acme", "core");
  private static final TenantScope GLOBEX = TenantScope.of("globex", "core");

  private InProcessPiiMetrics metrics;
  private PiiRuleRegistry registry;

  @BeforeEach
  void setUp() {
    metrics = new InProcessPiiMetrics();
    registry = new PiiRuleRegistry(metrics);
  }

  /**
   * A rule matching an internal ticket reference.
   *
   * @param id the rule identifier
   * @param priority the declared priority
   * @return the rule
   */
  private static PiiRule ticketRule(final String id, final int priority) {
    return new PiiRule(
        id,
        1,
        priority,
        PiiRule.Origin.TENANT,
        PiiRule.Effect.DENY,
        PiiType.CUSTOM,
        PiiCategory.BUSINESS_CONFIDENTIAL,
        PiiSeverity.HIGH,
        0.9,
        "(?<![A-Za-z0-9])TKT-\\d{5}(?![A-Za-z0-9])",
        false,
        PiiValidator.NONE,
        Set.of());
  }

  @Test
  @DisplayName("a tenant rule applies only to that tenant")
  void aTenantRuleAppliesOnlyToThatTenant() {
    registry.installTenantRules(ACME, List.of(ticketRule("acme.ticket", 0)));

    assertThat(registry.engineFor(ACME).detect("see TKT-99321 for detail").types())
        .contains(PiiType.CUSTOM);
    assertThat(registry.engineFor(GLOBEX).detect("see TKT-99321 for detail").any()).isFalse();
  }

  @Test
  @DisplayName("an organization rule applies to every tenant")
  void anOrganizationRuleAppliesToEveryTenant() {
    registry.installOrganizationRules(
        List.of(ticketRule("org.ticket", 0).withOrigin(PiiRule.Origin.ORGANIZATION, 0)));

    assertThat(registry.engineFor(ACME).detect("see TKT-99321").types()).contains(PiiType.CUSTOM);
    assertThat(registry.engineFor(GLOBEX).detect("see TKT-99321").types()).contains(PiiType.CUSTOM);
    assertThat(registry.defaultEngine().detect("see TKT-99321").types()).contains(PiiType.CUSTOM);
  }

  @Test
  @DisplayName("an organization rule installed later reaches tenants already registered")
  void anOrganizationRuleInstalledLaterReachesTenantsAlreadyRegistered() {
    registry.installTenantRules(ACME, List.of(ticketRule("acme.ticket", 0)));
    registry.installOrganizationRules(
        List.of(
            new PiiRule(
                "org.badge",
                1,
                0,
                PiiRule.Origin.ORGANIZATION,
                PiiRule.Effect.DENY,
                PiiType.EMPLOYEE_ID,
                PiiCategory.BUSINESS_CONFIDENTIAL,
                PiiSeverity.MEDIUM,
                0.9,
                "(?<![A-Za-z0-9])BADGE-\\d{4}(?![A-Za-z0-9])",
                false,
                PiiValidator.NONE,
                Set.of())));

    // An organization rule that only reached tenants onboarded after it was installed would be an
    // organization rule in name only.
    final PiiDetection detection = registry.engineFor(ACME).detect("BADGE-4471 and TKT-99321");
    assertThat(detection.types()).containsExactlyInAnyOrder(PiiType.EMPLOYEE_ID, PiiType.CUSTOM);
  }

  @Test
  @DisplayName("a tenant rule outranks an organization rule whatever priority each declares")
  void aTenantRuleOutranksAnOrganizationRuleWhateverPriorityEachDeclares() {
    final String pattern = "(?<![A-Za-z0-9])REF-\\d{4}(?![A-Za-z0-9])";
    registry.installOrganizationRules(
        List.of(
            new PiiRule(
                "org.ref",
                1,
                Integer.MAX_VALUE - 1,
                PiiRule.Origin.ORGANIZATION,
                PiiRule.Effect.DENY,
                PiiType.CUSTOM,
                PiiCategory.LEGAL,
                PiiSeverity.CRITICAL,
                0.9,
                pattern,
                false,
                PiiValidator.NONE,
                Set.of())));
    registry.installTenantRules(
        ACME,
        List.of(
            new PiiRule(
                "acme.ref",
                1,
                0,
                PiiRule.Origin.TENANT,
                PiiRule.Effect.DENY,
                PiiType.CUSTOM,
                PiiCategory.BUSINESS_CONFIDENTIAL,
                PiiSeverity.LOW,
                0.9,
                pattern,
                false,
                PiiValidator.NONE,
                Set.of())));

    // The origin floor is what makes layering total. Without it, whoever picked the larger integer
    // wins, and an organization could silently overrule a tenant inside that tenant's own data.
    final PiiDetection detection = registry.engineFor(ACME).detect("REF-1234 attached");
    assertThat(detection.spans()).hasSize(1);
    assertThat(detection.spans().get(0).ruleId()).isEqualTo("acme.ref");
    assertThat(detection.severity()).isEqualTo(PiiSeverity.LOW);
  }

  @Test
  @DisplayName("origin dominates across the whole integer priority range")
  void originDominatesAcrossTheWholeIntegerPriorityRange() {
    // A regression test for a defect the AD-029 sabotage run exposed. Effective priority was first
    // computed as an origin floor of 0/1000/2000 added to the declared priority, in int arithmetic.
    // Both halves were wrong: Integer.MAX_VALUE + 1000 overflows to a large negative number, and
    // once that was fixed to long arithmetic the floors turned out to be far too small to dominate
    // a declared priority anywhere near the int limit. Either way an organization rule could
    // outrank
    // a tenant rule inside that tenant's own data.
    //
    // The extremes are the whole point of this test. A version that only checks small priorities
    // passes against both broken implementations.
    for (final int organizationPriority :
        new int[] {Integer.MIN_VALUE, -1, 0, 1, Integer.MAX_VALUE}) {
      for (final int tenantPriority : new int[] {Integer.MIN_VALUE, -1, 0, 1, Integer.MAX_VALUE}) {
        final PiiRule organizationRule =
            ticketRule("org.ref", organizationPriority)
                .withOrigin(PiiRule.Origin.ORGANIZATION, organizationPriority)
                .classifiedAs(PiiCategory.LEGAL, PiiSeverity.CRITICAL);
        final PiiRule tenantRule =
            ticketRule("acme.ref", tenantPriority)
                .withOrigin(PiiRule.Origin.TENANT, tenantPriority)
                .classifiedAs(PiiCategory.BUSINESS_CONFIDENTIAL, PiiSeverity.LOW);

        assertThat(tenantRule.effectivePriority())
            .as("tenant %d must outrank organization %d", tenantPriority, organizationPriority)
            .isGreaterThan(organizationRule.effectivePriority());

        final PiiDetection detection =
            new PiiDetectionEngine(
                    PiiRuleCompiler.compile(List.of(organizationRule, tenantRule)),
                    PiiMetricsPort.NOOP)
                .detect("TKT-99321 raised");
        assertThat(detection.spans()).hasSize(1);
        assertThat(detection.spans().get(0).ruleId()).isEqualTo("acme.ref");
      }
    }
  }

  @Test
  @DisplayName("declared priority still orders rules within one origin at the extremes")
  void declaredPriorityStillOrdersRulesWithinOneOriginAtTheExtremes() {
    final PiiRule low =
        ticketRule("acme.low", Integer.MIN_VALUE)
            .withOrigin(PiiRule.Origin.TENANT, Integer.MIN_VALUE);
    final PiiRule high =
        ticketRule("acme.high", Integer.MAX_VALUE)
            .withOrigin(PiiRule.Origin.TENANT, Integer.MAX_VALUE)
            .classifiedAs(PiiCategory.LEGAL, PiiSeverity.CRITICAL);

    // Biasing the declared priority into the low bits must preserve its own ordering, including
    // across the sign boundary where the first implementation lost it.
    final PiiDetection detection =
        new PiiDetectionEngine(PiiRuleCompiler.compile(List.of(low, high)), PiiMetricsPort.NOOP)
            .detect("TKT-99321 raised");
    assertThat(detection.spans()).hasSize(1);
    assertThat(detection.spans().get(0).ruleId()).isEqualTo("acme.high");
  }

  @Test
  @DisplayName("a tenant rule outranks a built-in on the same text")
  void aTenantRuleOutranksABuiltInOnTheSameText() {
    registry.installTenantRules(
        ACME,
        List.of(
            new PiiRule(
                "acme.internal.mail",
                1,
                0,
                PiiRule.Origin.TENANT,
                PiiRule.Effect.DENY,
                PiiType.CUSTOM,
                PiiCategory.BUSINESS_CONFIDENTIAL,
                PiiSeverity.LOW,
                0.9,
                "(?<![A-Za-z0-9._%+-])[a-z]+@acme\\.internal(?![A-Za-z0-9.-])",
                false,
                PiiValidator.NONE,
                Set.of())));

    final PiiDetection detection = registry.engineFor(ACME).detect("mail ops@acme.internal today");
    assertThat(detection.spans()).hasSize(1);
    assertThat(detection.spans().get(0).ruleId()).isEqualTo("acme.internal.mail");
    assertThat(detection.severity()).isEqualTo(PiiSeverity.LOW);
  }

  @Test
  @DisplayName("within one layer the declared priority decides")
  void withinOneLayerTheDeclaredPriorityDecides() {
    registry.installTenantRules(
        ACME,
        List.of(
            ticketRule("acme.low", 1),
            ticketRule("acme.high", 500).classifiedAs(PiiCategory.LEGAL, PiiSeverity.CRITICAL)));

    final PiiDetection detection = registry.engineFor(ACME).detect("TKT-99321 raised");
    assertThat(detection.spans()).hasSize(1);
    assertThat(detection.spans().get(0).ruleId()).isEqualTo("acme.high");
  }

  @Test
  @DisplayName("an allow rule exempts text a built-in would otherwise flag")
  void anAllowRuleExemptsTextABuiltInWouldOtherwiseFlag() {
    registry.installTenantRules(
        ACME,
        List.of(
            new PiiRule(
                "acme.docs.example.card",
                1,
                0,
                PiiRule.Origin.TENANT,
                PiiRule.Effect.ALLOW,
                PiiType.CREDIT_CARD,
                PiiCategory.FINANCIAL,
                PiiSeverity.NONE,
                1.0,
                "4111[ -]?1111[ -]?1111[ -]?1111",
                false,
                PiiValidator.NONE,
                Set.of())));

    // Without exemptions, the only way to stop a false positive is to weaken the detector for
    // everyone. A documentation corpus full of the canonical test card is the standard case.
    assertThat(registry.engineFor(ACME).detect("use 4111 1111 1111 1111 in examples").any())
        .isFalse();
    assertThat(registry.engineFor(GLOBEX).detect("use 4111 1111 1111 1111 in examples").types())
        .contains(PiiType.CREDIT_CARD);
    assertThat(metrics.count("suppressedByAllowRule")).isEqualTo(1);
  }

  @Test
  @DisplayName("an allow rule exempts only the text it covers")
  void anAllowRuleExemptsOnlyTheTextItCovers() {
    registry.installTenantRules(
        ACME,
        List.of(
            new PiiRule(
                "acme.example.domain",
                1,
                0,
                PiiRule.Origin.TENANT,
                PiiRule.Effect.ALLOW,
                PiiType.EMAIL,
                PiiCategory.PERSONAL,
                PiiSeverity.NONE,
                1.0,
                "(?<![A-Za-z0-9._%+-])[A-Za-z0-9._%+-]+@example\\.com(?![A-Za-z0-9.-])",
                false,
                PiiValidator.NONE,
                Set.of())));

    final PiiDetection detection =
        registry.engineFor(ACME).detect("fixture alice@example.com but real bob@acme.co.uk");
    assertThat(detection.spans()).hasSize(1);
    assertThat(detection.severity()).isEqualTo(PiiSeverity.MEDIUM);
  }

  @Test
  @DisplayName("clearing tenant rules returns that tenant to the organization set")
  void clearingTenantRulesReturnsThatTenantToTheOrganizationSet() {
    registry.installTenantRules(ACME, List.of(ticketRule("acme.ticket", 0)));
    assertThat(registry.tenantCount()).isEqualTo(1);

    registry.clearTenantRules(ACME);

    assertThat(registry.tenantCount()).isZero();
    assertThat(registry.engineFor(ACME).detect("TKT-99321").any()).isFalse();
  }

  @Test
  @DisplayName("installing rules produces a new version every time")
  void installingRulesProducesANewVersionEveryTime() {
    final long before = registry.engineFor(ACME).rules().version();
    registry.installTenantRules(ACME, List.of(ticketRule("acme.ticket", 0)));
    final long after = registry.engineFor(ACME).rules().version();

    assertThat(after).isGreaterThan(before);
  }

  @Test
  @DisplayName("rules are ordered deterministically regardless of the order they arrive in")
  void rulesAreOrderedDeterministicallyRegardlessOfTheOrderTheyArriveIn() {
    final PiiRule first = ticketRule("a.rule", 5);
    final PiiRule second = ticketRule("b.rule", 5);

    final List<String> forward = PiiRuleCompiler.compile(List.of(first, second)).ruleIds();
    final List<String> backward = PiiRuleCompiler.compile(List.of(second, first)).ruleIds();

    // On a priority tie the identifier decides, so behaviour never depends on iteration order of
    // whatever store the rules came out of.
    assertThat(forward).isEqualTo(backward);
  }

  @Test
  @DisplayName("a duplicate rule identifier is refused at compile time")
  void aDuplicateRuleIdentifierIsRefusedAtCompileTime() {
    assertThatThrownBy(
            () -> PiiRuleCompiler.compile(List.of(ticketRule("dup", 0), ticketRule("dup", 1))))
        .isInstanceOf(PiiRuleCompiler.PiiRuleCompilationException.class)
        .hasMessageContaining("duplicate rule identifier");
  }

  @Test
  @DisplayName("a malformed pattern is refused at compile time and names its rule")
  void aMalformedPatternIsRefusedAtCompileTimeAndNamesItsRule() {
    final PiiRule broken =
        PiiRule.detecting("bad.rule", PiiType.CUSTOM, "([unclosed", PiiValidator.NONE);

    assertThatThrownBy(() -> PiiRuleCompiler.compile(List.of(broken)))
        .isInstanceOf(PiiRuleCompiler.PiiRuleCompilationException.class)
        .satisfies(
            thrown ->
                assertThat(((PiiRuleCompiler.PiiRuleCompilationException) thrown).ruleId())
                    .isEqualTo("bad.rule"));
  }

  @Test
  @DisplayName("a pattern that matches the empty string is refused")
  void aPatternThatMatchesTheEmptyStringIsRefused() {
    final PiiRule empty = PiiRule.detecting("empty.rule", PiiType.CUSTOM, "x*", PiiValidator.NONE);

    // It would produce a zero-width span at every offset and spend the whole document doing it.
    assertThatThrownBy(() -> PiiRuleCompiler.compile(List.of(empty)))
        .isInstanceOf(PiiRuleCompiler.PiiRuleCompilationException.class)
        .hasMessageContaining("empty string");
  }

  @Test
  @DisplayName("a rule may not declare the MULTIPLE summary category")
  void aRuleMayNotDeclareTheMultipleSummaryCategory() {
    assertThatThrownBy(
            () ->
                new PiiRule(
                    "bad",
                    1,
                    0,
                    PiiRule.Origin.TENANT,
                    PiiRule.Effect.DENY,
                    PiiType.CUSTOM,
                    PiiCategory.MULTIPLE,
                    PiiSeverity.LOW,
                    0.9,
                    "x",
                    false,
                    PiiValidator.NONE,
                    Set.of()))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  @DisplayName("a rule version below one is refused")
  void aRuleVersionBelowOneIsRefused() {
    assertThatThrownBy(
            () ->
                new PiiRule(
                    "bad",
                    0,
                    0,
                    PiiRule.Origin.TENANT,
                    PiiRule.Effect.DENY,
                    PiiType.CUSTOM,
                    PiiCategory.LEGAL,
                    PiiSeverity.LOW,
                    0.9,
                    "x",
                    false,
                    PiiValidator.NONE,
                    Set.of()))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  @DisplayName("a case-insensitive rule matches either case")
  void aCaseInsensitiveRuleMatchesEitherCase() {
    registry.installTenantRules(
        ACME,
        List.of(
            new PiiRule(
                "acme.code",
                1,
                0,
                PiiRule.Origin.TENANT,
                PiiRule.Effect.DENY,
                PiiType.CUSTOM,
                PiiCategory.BUSINESS_CONFIDENTIAL,
                PiiSeverity.MEDIUM,
                0.9,
                "(?<![A-Za-z0-9])proj-[a-z]{4}(?![A-Za-z0-9])",
                true,
                PiiValidator.NONE,
                Set.of())));

    assertThat(registry.engineFor(ACME).detect("PROJ-ABCD started").types())
        .contains(PiiType.CUSTOM);
    assertThat(registry.engineFor(ACME).detect("proj-abcd started").types())
        .contains(PiiType.CUSTOM);
  }

  @Test
  @DisplayName("a rule version travels onto every span it produces")
  void aRuleVersionTravelsOntoEverySpanItProduces() {
    registry.installTenantRules(
        ACME,
        List.of(
            new PiiRule(
                "acme.ticket",
                7,
                0,
                PiiRule.Origin.TENANT,
                PiiRule.Effect.DENY,
                PiiType.CUSTOM,
                PiiCategory.BUSINESS_CONFIDENTIAL,
                PiiSeverity.HIGH,
                0.9,
                "(?<![A-Za-z0-9])TKT-\\d{5}(?![A-Za-z0-9])",
                false,
                PiiValidator.NONE,
                Set.of())));

    assertThat(registry.engineFor(ACME).detect("TKT-99321").spans().get(0).ruleVersion())
        .isEqualTo(7);
  }
}

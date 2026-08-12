package io.reliabilityai.gateway.dataplane.governance;

import static io.reliabilityai.gateway.dataplane.governance.PolicyFixture.GLOBAL;
import static io.reliabilityai.gateway.dataplane.governance.PolicyFixture.MODEL;
import static io.reliabilityai.gateway.dataplane.governance.PolicyFixture.NOW;
import static io.reliabilityai.gateway.dataplane.governance.PolicyFixture.PROJECT_REF;
import static io.reliabilityai.gateway.dataplane.governance.PolicyFixture.REGION;
import static io.reliabilityai.gateway.dataplane.governance.PolicyFixture.document;
import static io.reliabilityai.gateway.dataplane.governance.PolicyFixture.request;
import static io.reliabilityai.gateway.dataplane.governance.PolicyFixture.rule;
import static io.reliabilityai.gateway.dataplane.governance.PolicyFixture.snapshot;
import static io.reliabilityai.gateway.dataplane.governance.PolicyFixture.usage;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.params.provider.Arguments.arguments;

import io.reliabilityai.gateway.canonical.identity.CanonicalModelId;
import io.reliabilityai.gateway.canonical.identity.Region;
import io.reliabilityai.gateway.dataplane.governance.api.DenialReason;
import io.reliabilityai.gateway.dataplane.governance.api.EnforcementLevel;
import io.reliabilityai.gateway.dataplane.governance.api.PolicyDecision;
import io.reliabilityai.gateway.dataplane.governance.api.PolicyDomain;
import io.reliabilityai.gateway.dataplane.governance.api.PolicyRequest;
import io.reliabilityai.gateway.dataplane.governance.api.PolicyRule;
import io.reliabilityai.gateway.dataplane.governance.api.PolicyScope;
import io.reliabilityai.gateway.dataplane.governance.api.PolicyScopeRef;
import io.reliabilityai.gateway.dataplane.governance.api.PolicyType;
import io.reliabilityai.gateway.dataplane.governance.api.PolicyUsage;
import io.reliabilityai.gateway.dataplane.governance.api.PolicyValue;
import io.reliabilityai.gateway.dataplane.governance.api.ResolvedPolicyContext;
import io.reliabilityai.gateway.dataplane.governance.api.TickerPort;
import io.reliabilityai.gateway.dataplane.governance.api.Verdict;
import io.reliabilityai.gateway.dataplane.governance.domain.PolicyEvaluator;
import io.reliabilityai.gateway.dataplane.governance.domain.PolicySnapshot;
import io.reliabilityai.gateway.dataplane.governance.domain.UsageLookup;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.Set;
import java.util.function.UnaryOperator;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Tests the decision core: every policy type, the verdict ladder, deny-overrides and determinism.
 */
class PolicyEvaluatorTest {

  private static final Duration TOLERANCE = Duration.ofSeconds(30);
  private static final Duration TTL = Duration.ofMinutes(5);
  private static final PolicyEvaluator EVALUATOR = new PolicyEvaluator(TOLERANCE, TTL);
  private static final UsageLookup FRESH = scope -> Optional.of(usage());

  private static PolicyDecision decide(final PolicyRequest request, final PolicyRule... rules) {
    return decide(request, FRESH, rules);
  }

  private static PolicyDecision decide(
      final PolicyRequest request, final UsageLookup usage, final PolicyRule... rules) {
    final PolicySnapshot snapshot = snapshot(1L, document(GLOBAL, 1L, rules));
    return EVALUATOR.evaluate(
        request, snapshot.effectiveFor(request.scopeChain()), usage, NOW, TickerPort.FROZEN);
  }

  // ---- baseline -------------------------------------------------------------------------------

  @Test
  void aPolicyThatSaysNothingViolatesNothing() {
    final PolicyDecision decision = decide(request().build());

    assertThat(decision.verdict()).isEqualTo(Verdict.ALLOW);
    assertThat(decision.violations()).isEmpty();
    assertThat(decision.reasonCode()).isEqualTo(PolicyDecision.PERMITTED);
  }

  @Test
  void anAllowCarriesTheGenerationItWasDecidedAgainst() {
    final PolicyDecision decision = decide(request().build());

    assertThat(decision.policyVersion().sequence()).isEqualTo(1L);
    assertThat(decision.context().policyVersion()).isEqualTo(decision.policyVersion());
  }

  @Test
  void anAdmittedRequestCarriesAValidityDeadline() {
    final PolicyDecision decision = decide(request().build());

    assertThat(decision.context().validUntil()).isEqualTo(NOW.plus(TTL));
    assertThat(decision.context().isExpiredAt(NOW)).isFalse();
    assertThat(decision.context().isExpiredAt(NOW.plus(TTL))).isTrue();
  }

  // ---- tier 1-2: security and tenant ----------------------------------------------------------

  @Test
  void theKillSwitchRefusesEverything() {
    final PolicyDecision decision =
        decide(
            request().build(),
            rule("kill", PolicyType.EMERGENCY_KILL_SWITCH, PolicyValue.Flag.TRUE));

    assertThat(decision.verdict()).isEqualTo(Verdict.DENY);
    assertThat(decision.reasonCode()).isEqualTo(DenialReason.SECURITY_DENIED.code());
  }

  @Test
  void anUnpulledKillSwitchRefusesNothing() {
    assertThat(
            decide(
                    request().build(),
                    rule("kill", PolicyType.EMERGENCY_KILL_SWITCH, PolicyValue.Flag.FALSE))
                .verdict())
        .isEqualTo(Verdict.ALLOW);
  }

  @Test
  void aSuspendedTenantIsRefused() {
    final PolicyDecision decision =
        decide(
            request().build(), rule("susp", PolicyType.TENANT_SUSPENSION, PolicyValue.Flag.TRUE));

    assertThat(decision.reasonCode()).isEqualTo(DenialReason.TENANT_DISABLED.code());
  }

  @Test
  void aRequestInsideAMaintenanceWindowIsRefused() {
    final PolicyValue.Windows window =
        PolicyValue.Windows.of(
            new PolicyValue.TimeWindow(NOW.minusSeconds(60), NOW.plusSeconds(60)));

    assertThat(
            decide(request().build(), rule("maint", PolicyType.MAINTENANCE_WINDOW, window))
                .verdict())
        .isEqualTo(Verdict.DENY);
  }

  @Test
  void aRequestOutsideEveryMaintenanceWindowProceeds() {
    final PolicyValue.Windows window =
        PolicyValue.Windows.of(
            new PolicyValue.TimeWindow(NOW.plusSeconds(60), NOW.plusSeconds(120)));

    assertThat(
            decide(request().build(), rule("maint", PolicyType.MAINTENANCE_WINDOW, window))
                .verdict())
        .isEqualTo(Verdict.ALLOW);
  }

  @Test
  void aMaintenanceWindowEndsExclusivelySoTheClosingInstantIsAdmitted() {
    final PolicyValue.Windows window =
        PolicyValue.Windows.of(new PolicyValue.TimeWindow(NOW.minusSeconds(60), NOW));

    assertThat(
            decide(request().build(), rule("maint", PolicyType.MAINTENANCE_WINDOW, window))
                .verdict())
        .isEqualTo(Verdict.ALLOW);
  }

  // ---- tier 3: compliance ---------------------------------------------------------------------

  @Test
  void aRegimeTheScopeIsNotAttestedForCannotBeSatisfied() {
    final PolicyDecision decision =
        decide(
            request().requiredComplianceRegimes(Set.of("HIPAA")).build(),
            rule("comp", PolicyType.COMPLIANCE_MODE, PolicyValue.Values.of("SOC2")));

    assertThat(decision.reasonCode()).isEqualTo(DenialReason.COMPLIANCE_CONFLICT.code());
  }

  @Test
  void anAttestedRegimeIsSatisfied() {
    assertThat(
            decide(
                    request().requiredComplianceRegimes(Set.of("SOC2")).build(),
                    rule("comp", PolicyType.COMPLIANCE_MODE, PolicyValue.Values.of("SOC2", "GDPR")))
                .verdict())
        .isEqualTo(Verdict.ALLOW);
  }

  @Test
  void aPiiClassifiedRequestIsRefusedWhereePiiIsProhibited() {
    final PolicyDecision decision =
        decide(
            request().containsPii(true).build(),
            rule("pii", PolicyType.PII_RESTRICTION, PolicyValue.Flag.TRUE));

    assertThat(decision.reasonCode()).isEqualTo(DenialReason.COMPLIANCE_CONFLICT.code());
  }

  @Test
  void aPiiProhibitionDoesNotAffectARequestCarryingNoPii() {
    assertThat(
            decide(
                    request().containsPii(false).build(),
                    rule("pii", PolicyType.PII_RESTRICTION, PolicyValue.Flag.TRUE))
                .verdict())
        .isEqualTo(Verdict.ALLOW);
  }

  // ---- tier 4: residency ----------------------------------------------------------------------

  @Test
  void aRegionOutsideTheResidencyScopeIsRefused() {
    final PolicyDecision decision =
        decide(
            request().region(new Region("us-east-1")).build(),
            rule("res", PolicyType.REGION_RESTRICTION, PolicyValue.Values.of("eu-west-1")));

    assertThat(decision.reasonCode()).isEqualTo(DenialReason.REGION_NOT_ALLOWED.code());
  }

  @Test
  void thePermittedRegionsBecomeTheResolvedResidencyScope() {
    final PolicyDecision decision =
        decide(
            request().build(),
            rule(
                "res",
                PolicyType.REGION_RESTRICTION,
                PolicyValue.Values.of("eu-west-1", "eu-central-1")));

    assertThat(decision.context().residencyScope()).containsExactly("eu-central-1", "eu-west-1");
  }

  // ---- tier 5: authorization ------------------------------------------------------------------

  @Test
  void aModelOutsideTheAllowListIsRefused() {
    final PolicyDecision decision =
        decide(
            request().build(),
            rule("m", PolicyType.MODEL_ALLOW_LIST, PolicyValue.Values.of("model.other.v1")));

    assertThat(decision.reasonCode()).isEqualTo(DenialReason.MODEL_NOT_ALLOWED.code());
  }

  @Test
  void aModelOnTheDenyListIsRefusedEvenWhenTheAllowListPermitsIt() {
    final PolicyDecision decision =
        decide(
            request().build(),
            rule("allow", PolicyType.MODEL_ALLOW_LIST, PolicyValue.Values.of(MODEL.value())),
            rule("deny", PolicyType.MODEL_DENY_LIST, PolicyValue.Values.of(MODEL.value())));

    assertThat(decision.verdict()).isEqualTo(Verdict.DENY);
    assertThat(decision.binding()).get().extracting("ruleId").isEqualTo("deny");
  }

  @Test
  void aRequestAddressingNoModelIsNotJudgedByModelPolicy() {
    final PolicyRequest noModel =
        PolicyRequest.builder()
            .requestContext(PolicyFixture.requestContext())
            .principal(PolicyFixture.principal())
            .tenant(PolicyFixture.tenant())
            .scopeChain(PolicyFixture.chain())
            .region(REGION)
            .build();

    assertThat(
            decide(noModel, rule("m", PolicyType.MODEL_ALLOW_LIST, PolicyValue.Values.of("other")))
                .verdict())
        .isEqualTo(Verdict.ALLOW);
  }

  @Test
  void aToolOutsideTheAllowListIsRefused() {
    final PolicyDecision decision =
        decide(
            request().tools(Set.of("web-search")).build(),
            rule("t", PolicyType.TOOL_ALLOW_LIST, PolicyValue.Values.of("calculator")));

    assertThat(decision.reasonCode()).isEqualTo(DenialReason.TOOL_UNAUTHORIZED.code());
  }

  @Test
  void aDeniedToolIsRefused() {
    final PolicyDecision decision =
        decide(
            request().tools(Set.of("shell")).build(),
            rule("t", PolicyType.TOOL_DENY_LIST, PolicyValue.Values.of("shell")));

    assertThat(decision.reasonCode()).isEqualTo(DenialReason.TOOL_UNAUTHORIZED.code());
  }

  @Test
  void requestingNoToolsSatisfiesBothToolLists() {
    assertThat(
            decide(
                    request().build(),
                    rule("allow", PolicyType.TOOL_ALLOW_LIST, PolicyValue.Values.of("calculator")),
                    rule("deny", PolicyType.TOOL_DENY_LIST, PolicyValue.Values.of("shell")))
                .verdict())
        .isEqualTo(Verdict.ALLOW);
  }

  @Test
  void aRequestProceedsWhileAnyCandidateProviderSurvivesTheAllowList() {
    assertThat(
            decide(
                    request().candidateProviders(Set.of("p1", "p2")).build(),
                    rule("p", PolicyType.PROVIDER_ALLOW_LIST, PolicyValue.Values.of("p2")))
                .verdict())
        .isEqualTo(Verdict.ALLOW);
  }

  @Test
  void aRequestIsRefusedOnlyWhenEveryCandidateProviderIsExcluded() {
    final PolicyDecision decision =
        decide(
            request().candidateProviders(Set.of("p1", "p2")).build(),
            rule("p", PolicyType.PROVIDER_ALLOW_LIST, PolicyValue.Values.of("p3")));

    assertThat(decision.reasonCode()).isEqualTo(DenialReason.UNAUTHORIZED.code());
  }

  @Test
  void aRequestIsRefusedWhenTheDenyListRemovesEveryCandidateProvider() {
    final PolicyDecision decision =
        decide(
            request().candidateProviders(Set.of("p1")).build(),
            rule("p", PolicyType.PROVIDER_DENY_LIST, PolicyValue.Values.of("p1")));

    assertThat(decision.verdict()).isEqualTo(Verdict.DENY);
  }

  @Test
  void theSurvivingProvidersBecomeTheResolvedProviderScope() {
    final PolicyDecision decision =
        decide(
            request().candidateProviders(Set.of("p1", "p2", "p3")).build(),
            rule("deny", PolicyType.PROVIDER_DENY_LIST, PolicyValue.Values.of("p2")));

    assertThat(decision.context().allowedProviders()).containsExactly("p1", "p3");
  }

  // ---- tier 6: capability ---------------------------------------------------------------------

  @Test
  void streamingIsRefusedWhereItIsNotPermitted() {
    final PolicyDecision decision =
        decide(
            request().streaming(true).build(),
            rule("s", PolicyType.STREAMING_ALLOWED, PolicyValue.Flag.FALSE));

    assertThat(decision.reasonCode()).isEqualTo(DenialReason.FEATURE_DISABLED.code());
  }

  @Test
  void aNonStreamingRequestIsUnaffectedByAStreamingProhibition() {
    assertThat(
            decide(
                    request().streaming(false).build(),
                    rule("s", PolicyType.STREAMING_ALLOWED, PolicyValue.Flag.FALSE))
                .verdict())
        .isEqualTo(Verdict.ALLOW);
  }

  @Test
  void aNonStreamingRequestIsRefusedWhereStreamingIsRequired() {
    final PolicyDecision decision =
        decide(
            request().streaming(false).build(),
            rule("s", PolicyType.STREAMING_REQUIRED, PolicyValue.Flag.TRUE));

    assertThat(decision.verdict()).isEqualTo(Verdict.DENY);
  }

  @Test
  void aRequestWithoutAPinnedSchemaIsRefusedWhereJsonModeIsRequired() {
    final PolicyDecision decision =
        decide(
            request().jsonMode(false).build(),
            rule("j", PolicyType.JSON_MODE_REQUIRED, PolicyValue.Flag.TRUE));

    assertThat(decision.verdict()).isEqualTo(Verdict.DENY);
  }

  @Test
  void anAdmittedRequestCarriesTheRequirementsItSatisfied() {
    final PolicyDecision decision =
        decide(
            request().streaming(true).jsonMode(true).build(),
            rule("s", PolicyType.STREAMING_REQUIRED, PolicyValue.Flag.TRUE),
            rule("j", PolicyType.JSON_MODE_REQUIRED, PolicyValue.Flag.TRUE));

    assertThat(decision.verdict()).isEqualTo(Verdict.ALLOW);
    assertThat(decision.context().streamingRequired()).isTrue();
    assertThat(decision.context().jsonModeRequired()).isTrue();
  }

  @Test
  void reasoningIsRefusedWhereItIsNotPermitted() {
    assertThat(
            decide(
                    request().reasoning(true).build(),
                    rule("r", PolicyType.REASONING_ALLOWED, PolicyValue.Flag.FALSE))
                .verdict())
        .isEqualTo(Verdict.DENY);
  }

  @Test
  void visionIsRefusedWhereItIsNotPermitted() {
    assertThat(
            decide(
                    request().vision(true).build(),
                    rule("v", PolicyType.VISION_ALLOWED, PolicyValue.Flag.FALSE))
                .verdict())
        .isEqualTo(Verdict.DENY);
  }

  @Test
  void imageGenerationIsRefusedWhereItIsNotPermitted() {
    assertThat(
            decide(
                    request().imageGeneration(true).build(),
                    rule("i", PolicyType.IMAGE_GENERATION_ALLOWED, PolicyValue.Flag.FALSE))
                .verdict())
        .isEqualTo(Verdict.DENY);
  }

  @Test
  void audioIsRefusedWhereItIsNotPermitted() {
    assertThat(
            decide(
                    request().audio(true).build(),
                    rule("a", PolicyType.AUDIO_ALLOWED, PolicyValue.Flag.FALSE))
                .verdict())
        .isEqualTo(Verdict.DENY);
  }

  @Test
  void embeddingIsRefusedWhereItIsNotPermitted() {
    assertThat(
            decide(
                    request().embedding(true).build(),
                    rule("e", PolicyType.EMBEDDING_ALLOWED, PolicyValue.Flag.FALSE))
                .verdict())
        .isEqualTo(Verdict.DENY);
  }

  @Test
  void fineTuningIsRefusedWhereItIsNotPermitted() {
    assertThat(
            decide(
                    request().fineTuning(true).build(),
                    rule("f", PolicyType.FINE_TUNING_ALLOWED, PolicyValue.Flag.FALSE))
                .verdict())
        .isEqualTo(Verdict.DENY);
  }

  @Test
  void batchIsRefusedWhereItIsNotPermitted() {
    assertThat(
            decide(
                    request().batch(true).build(),
                    rule("b", PolicyType.BATCH_ALLOWED, PolicyValue.Flag.FALSE))
                .verdict())
        .isEqualTo(Verdict.DENY);
  }

  // ---- tier 7: quota --------------------------------------------------------------------------

  @Test
  void anOversizedContextIsRefused() {
    final PolicyDecision decision =
        decide(
            request().contextTokens(5000).build(),
            rule("c", PolicyType.MAX_CONTEXT, PolicyValue.Limit.of(4096)));

    assertThat(decision.reasonCode()).isEqualTo(DenialReason.QUOTA_EXCEEDED.code());
  }

  @Test
  void aContextExactlyOnTheCeilingIsAdmitted() {
    assertThat(
            decide(
                    request().contextTokens(4096).build(),
                    rule("c", PolicyType.MAX_CONTEXT, PolicyValue.Limit.of(4096)))
                .verdict())
        .isEqualTo(Verdict.ALLOW);
  }

  @Test
  void anOversizedOutputRequestIsRefused() {
    assertThat(
            decide(
                    request().outputTokens(2000).build(),
                    rule("o", PolicyType.MAX_OUTPUT_TOKENS, PolicyValue.Limit.of(1000)))
                .verdict())
        .isEqualTo(Verdict.DENY);
  }

  @Test
  void exhaustedRequestQuotaIsRefused() {
    final UsageLookup exhausted = scope -> Optional.of(usage(100, 0));

    final PolicyDecision decision =
        decide(
            request().build(),
            exhausted,
            rule("q", PolicyType.MAX_REQUESTS, PolicyValue.Limit.of(100)));

    assertThat(decision.reasonCode()).isEqualTo(DenialReason.QUOTA_EXCEEDED.code());
  }

  @Test
  void quotaCountsTheRequestBeingAdmittedNotJustThoseAlreadyCounted() {
    final UsageLookup atNinetyNine = scope -> Optional.of(usage(99, 0));

    // 99 consumed against a ceiling of 100 admits; 100 consumed does not. The request being decided
    // has to count, or the ceiling is off by one in the permissive direction.
    assertThat(
            decide(
                    request().build(),
                    atNinetyNine,
                    rule("q", PolicyType.MAX_REQUESTS, PolicyValue.Limit.of(100)))
                .verdict())
        .isEqualTo(Verdict.ALLOW);
    assertThat(
            decide(
                    request().build(),
                    scope -> Optional.of(usage(100, 0)),
                    rule("q", PolicyType.MAX_REQUESTS, PolicyValue.Limit.of(100)))
                .verdict())
        .isEqualTo(Verdict.DENY);
  }

  @Test
  void anExceededRequestRateIsReportedAsRateLimitedNotQuotaExceeded() {
    final PolicyDecision decision =
        decide(
            request().build(),
            scope -> Optional.of(usage(60, 0)),
            rule("rpm", PolicyType.MAX_RPM, PolicyValue.Limit.of(60)));

    assertThat(decision.reasonCode()).isEqualTo(DenialReason.RATE_LIMITED.code());
  }

  @Test
  void tokenRateCountsBothTheContextAndTheRequestedOutput() {
    final UsageLookup used = scope -> Optional.of(new PolicyUsage(0, 0, 900, 0, 0, 0, NOW));

    assertThat(
            decide(
                    request().contextTokens(60).outputTokens(50).build(),
                    used,
                    rule("tpm", PolicyType.MAX_TPM, PolicyValue.Limit.of(1000)))
                .verdict())
        .isEqualTo(Verdict.DENY);
  }

  @Test
  void anExhaustedConcurrencyLimitIsRefused() {
    final UsageLookup busy = scope -> Optional.of(new PolicyUsage(0, 0, 0, 8, 0, 0, NOW));

    assertThat(
            decide(
                    request().build(),
                    busy,
                    rule("c", PolicyType.CONCURRENCY_LIMIT, PolicyValue.Limit.of(8)))
                .verdict())
        .isEqualTo(Verdict.DENY);
  }

  // ---- tier 8: budget -------------------------------------------------------------------------

  @Test
  void aRequestCostingMoreThanTheSingleRequestCeilingIsRefused() {
    final PolicyDecision decision =
        decide(
            request().projectedCostMicros(5_000).build(),
            rule("cost", PolicyType.MAX_COST, PolicyValue.Limit.of(1_000)));

    assertThat(decision.reasonCode()).isEqualTo(DenialReason.BUDGET_EXCEEDED.code());
  }

  @Test
  void aRequestThatWouldBreachTheDailyBudgetIsRefused() {
    final UsageLookup spent = scope -> Optional.of(usage(0, 9_500));

    assertThat(
            decide(
                    request().projectedCostMicros(1_000).build(),
                    spent,
                    rule("d", PolicyType.DAILY_BUDGET, PolicyValue.Limit.of(10_000)))
                .verdict())
        .isEqualTo(Verdict.DENY);
  }

  @Test
  void aRequestThatFitsExactlyInsideTheDailyBudgetIsAdmitted() {
    final UsageLookup spent = scope -> Optional.of(usage(0, 9_000));

    assertThat(
            decide(
                    request().projectedCostMicros(1_000).build(),
                    spent,
                    rule("d", PolicyType.DAILY_BUDGET, PolicyValue.Limit.of(10_000)))
                .verdict())
        .isEqualTo(Verdict.ALLOW);
  }

  @Test
  void aRequestThatWouldBreachTheMonthlyBudgetIsRefused() {
    final UsageLookup spent = scope -> Optional.of(usage(0, 99_999));

    assertThat(
            decide(
                    request().projectedCostMicros(2).build(),
                    spent,
                    rule("m", PolicyType.MONTHLY_BUDGET, PolicyValue.Limit.of(100_000)))
                .verdict())
        .isEqualTo(Verdict.DENY);
  }

  @Test
  void aCeilingNearTheNumericMaximumDoesNotOverflowIntoAdmission() {
    final UsageLookup spent = scope -> Optional.of(usage(0, Long.MAX_VALUE - 5));

    // Computed naively as consumed + increment > ceiling, this wraps negative and admits.
    assertThat(
            decide(
                    request().projectedCostMicros(100).build(),
                    spent,
                    rule("m", PolicyType.MONTHLY_BUDGET, PolicyValue.Limit.of(Long.MAX_VALUE)))
                .verdict())
        .isEqualTo(Verdict.DENY);
  }

  // ---- fail-closed on unverifiable consumption ------------------------------------------------

  @Test
  void aCeilingWithNoConsumptionReadingIsRefusedRatherThanWavedThrough() {
    final PolicyDecision decision =
        decide(
            request().build(),
            UsageLookup.UNKNOWN,
            rule("q", PolicyType.MAX_REQUESTS, PolicyValue.Limit.of(100)));

    assertThat(decision.verdict()).isEqualTo(Verdict.DENY);
    assertThat(decision.reasonCode()).isEqualTo(DenialReason.POLICY_UNAVAILABLE.code());
  }

  @Test
  void aConsumptionReadingPastItsFreshnessToleranceIsRefused() {
    final Instant stale = NOW.minus(TOLERANCE).minusSeconds(1);
    final UsageLookup old = scope -> Optional.of(PolicyUsage.none(stale));

    final PolicyDecision decision =
        decide(
            request().build(), old, rule("q", PolicyType.MAX_REQUESTS, PolicyValue.Limit.of(100)));

    assertThat(decision.reasonCode()).isEqualTo(DenialReason.POLICY_UNAVAILABLE.code());
  }

  @Test
  void aConsumptionReadingExactlyAtTheToleranceBoundaryIsStillTrusted() {
    final UsageLookup borderline = scope -> Optional.of(PolicyUsage.none(NOW.minus(TOLERANCE)));

    assertThat(
            decide(
                    request().build(),
                    borderline,
                    rule("q", PolicyType.MAX_REQUESTS, PolicyValue.Limit.of(100)))
                .verdict())
        .isEqualTo(Verdict.ALLOW);
  }

  @Test
  void anUnverifiableCeilingDoesNotAffectCeilingsThatNeedNoConsumption() {
    // MAX_CONTEXT is checked against the request itself, so an absent counter must not taint it.
    assertThat(
            decide(
                    request().contextTokens(10).build(),
                    UsageLookup.UNKNOWN,
                    rule("c", PolicyType.MAX_CONTEXT, PolicyValue.Limit.of(4096)))
                .verdict())
        .isEqualTo(Verdict.ALLOW);
  }

  // ---- enforcement ladder ---------------------------------------------------------------------

  @Test
  void anAdvisoryViolationAdmitsTheRequestAndRecordsTheBreach() {
    final PolicyDecision decision =
        decide(
            request().contextTokens(5000).build(),
            rule("c", PolicyType.MAX_CONTEXT, PolicyValue.Limit.of(10), EnforcementLevel.ADVISORY));

    assertThat(decision.verdict()).isEqualTo(Verdict.SOFT_DENY);
    assertThat(decision.admits()).isTrue();
    assertThat(decision.violations()).hasSize(1);
    assertThat(decision.binding()).get().extracting("ruleId").isEqualTo("c");
  }

  @Test
  void aShadowViolationAdmitsTheRequestAndRecordsWhatWouldHaveHappened() {
    final PolicyDecision decision =
        decide(
            request().contextTokens(5000).build(),
            rule("c", PolicyType.MAX_CONTEXT, PolicyValue.Limit.of(10), EnforcementLevel.SHADOW));

    assertThat(decision.verdict()).isEqualTo(Verdict.DRY_RUN);
    assertThat(decision.admits()).isTrue();
    assertThat(decision.violations()).hasSize(1);
  }

  @Test
  void aShadowRuleDoesNotConstrainWhatAnAdmittedRequestCarriesDownstream() {
    final PolicyDecision decision =
        decide(
            request().build(),
            rule(
                "o",
                PolicyType.MAX_OUTPUT_TOKENS,
                PolicyValue.Limit.of(16),
                EnforcementLevel.SHADOW));

    // Shadow mode exists to be measured, not to have effect. A shadow ceiling that narrowed the
    // resolved context would change production behaviour, which is the one thing it promises not
    // to.
    assertThat(decision.context().maxOutputTokens()).isEqualTo(ResolvedPolicyContext.UNBOUNDED);
  }

  @Test
  void aMandatoryRuleDoesConstrainWhatAnAdmittedRequestCarriesDownstream() {
    final PolicyDecision decision =
        decide(
            request().outputTokens(8).build(),
            rule("o", PolicyType.MAX_OUTPUT_TOKENS, PolicyValue.Limit.of(16)));

    assertThat(decision.verdict()).isEqualTo(Verdict.ALLOW);
    assertThat(decision.context().maxOutputTokens()).isEqualTo(16L);
  }

  @Test
  void aMandatoryViolationOutranksAnAdvisoryOne() {
    final PolicyDecision decision =
        decide(
            request().contextTokens(5000).outputTokens(5000).build(),
            rule(
                "soft",
                PolicyType.MAX_CONTEXT,
                PolicyValue.Limit.of(10),
                EnforcementLevel.ADVISORY),
            rule("hard", PolicyType.MAX_OUTPUT_TOKENS, PolicyValue.Limit.of(10)));

    assertThat(decision.verdict()).isEqualTo(Verdict.DENY);
    assertThat(decision.binding()).get().extracting("ruleId").isEqualTo("hard");
  }

  @Test
  void anAdvisoryViolationOutranksAShadowOne() {
    final PolicyDecision decision =
        decide(
            request().contextTokens(5000).outputTokens(5000).build(),
            rule(
                "shadow",
                PolicyType.MAX_CONTEXT,
                PolicyValue.Limit.of(10),
                EnforcementLevel.SHADOW),
            rule(
                "advisory",
                PolicyType.MAX_OUTPUT_TOKENS,
                PolicyValue.Limit.of(10),
                EnforcementLevel.ADVISORY));

    assertThat(decision.verdict()).isEqualTo(Verdict.SOFT_DENY);
    assertThat(decision.binding()).get().extracting("ruleId").isEqualTo("advisory");
    assertThat(decision.observed()).hasSize(1);
  }

  @ParameterizedTest
  @EnumSource(EnforcementLevel.class)
  void everyEnforcementLevelMapsToExactlyOneVerdict(final EnforcementLevel level) {
    assertThat(level.verdict()).isNotNull();
    assertThat(level.verdict().admits()).isEqualTo(level != EnforcementLevel.MANDATORY);
  }

  // ---- deny-overrides and precedence ----------------------------------------------------------

  @Test
  void theHighestPrecedenceViolationIsTheOneReported() {
    final PolicyDecision decision =
        decide(
            request().region(new Region("us-east-1")).contextTokens(9999).build(),
            rule("res", PolicyType.REGION_RESTRICTION, PolicyValue.Values.of("eu-west-1")),
            rule("ctx", PolicyType.MAX_CONTEXT, PolicyValue.Limit.of(10)));

    // Residency outranks quota, so residency is what the caller is told — and, because evaluation
    // short-circuits on the first refusal, the quota rule is never even reached.
    assertThat(decision.reasonCode()).isEqualTo(DenialReason.REGION_NOT_ALLOWED.code());
    assertThat(decision.violations()).hasSize(1);
  }

  @Test
  void evaluationStopsAtTheFirstMandatoryRefusal() {
    final PolicyDecision decision =
        decide(
            request().contextTokens(9999).outputTokens(9999).build(),
            rule("kill", PolicyType.EMERGENCY_KILL_SWITCH, PolicyValue.Flag.TRUE),
            rule("ctx", PolicyType.MAX_CONTEXT, PolicyValue.Limit.of(1)),
            rule("out", PolicyType.MAX_OUTPUT_TOKENS, PolicyValue.Limit.of(1)));

    assertThat(decision.violations()).hasSize(1);
    assertThat(decision.reasonCode()).isEqualTo(DenialReason.SECURITY_DENIED.code());
  }

  @Test
  void nonMandatoryViolationsAreAllCollectedRatherThanShortCircuited() {
    final PolicyDecision decision =
        decide(
            request().contextTokens(9999).outputTokens(9999).build(),
            rule("a", PolicyType.MAX_CONTEXT, PolicyValue.Limit.of(1), EnforcementLevel.ADVISORY),
            rule(
                "b",
                PolicyType.MAX_OUTPUT_TOKENS,
                PolicyValue.Limit.of(1),
                EnforcementLevel.SHADOW));

    assertThat(decision.violations()).hasSize(2);
    assertThat(decision.verdict()).isEqualTo(Verdict.SOFT_DENY);
  }

  @Test
  void aRefusedRequestCarriesNothingForward() {
    final PolicyDecision decision =
        decide(
            request().build(),
            rule("kill", PolicyType.EMERGENCY_KILL_SWITCH, PolicyValue.Flag.TRUE));

    final ResolvedPolicyContext context = decision.context();
    assertThat(context.residencyScope()).isEmpty();
    assertThat(context.allowedProviders()).isEmpty();
    assertThat(context.maxCostMicros()).isZero();
  }

  @ParameterizedTest
  @EnumSource(PolicyDomain.class)
  void everyPrecedenceTierHasAtLeastOnePolicyType(final PolicyDomain domain) {
    assertThat(java.util.Arrays.stream(PolicyType.values()).anyMatch(t -> t.domain() == domain))
        .isTrue();
  }

  @Test
  void policyTypesAreDeclaredInPrecedenceOrder() {
    // Declaration order is evaluation order, so a reordering here silently changes which violation
    // a
    // caller is told about. Pinning it makes that a test failure rather than a support mystery.
    int previous = -1;
    for (final PolicyType type : PolicyType.values()) {
      assertThat(type.domain().ordinal())
          .as("%s is out of tier order", type)
          .isGreaterThanOrEqualTo(previous);
      previous = type.domain().ordinal();
    }
  }

  @Test
  void everyHardTierTypeDemandsMandatoryEnforcement() {
    for (final PolicyType type : PolicyType.values()) {
      assertThat(type.requiresMandatoryEnforcement()).isEqualTo(type.domain().isHard());
    }
  }

  // ---- determinism ----------------------------------------------------------------------------

  @Test
  void identicalInputsProduceAnIdenticalDecisionEveryTime() {
    final PolicyRequest request = request().contextTokens(50).build();
    final PolicyRule[] rules = {
      rule("m", PolicyType.MODEL_ALLOW_LIST, PolicyValue.Values.of(MODEL.value())),
      rule("c", PolicyType.MAX_CONTEXT, PolicyValue.Limit.of(4096))
    };

    final PolicyDecision first = decide(request, rules);
    for (int i = 0; i < 25; i++) {
      assertThat(decide(request, rules)).isEqualTo(first);
    }
  }

  @Test
  void aRefusalIsReproducibleDownToItsExplanation() {
    final PolicyRequest request = request().build();
    final PolicyRule refusal =
        rule("m", PolicyType.MODEL_ALLOW_LIST, PolicyValue.Values.of("model.other.v1"));

    assertThat(decide(request, refusal)).isEqualTo(decide(request, refusal));
  }

  @Test
  void aDecisionRecordsItsOwnEvaluationCost() {
    final long[] tick = {0L};
    final TickerPort advancing = () -> tick[0] += 1_000L;
    final PolicySnapshot snapshot = snapshot(1L, document(GLOBAL, 1L));
    final PolicyRequest request = request().build();

    final PolicyDecision decision =
        EVALUATOR.evaluate(
            request, snapshot.effectiveFor(request.scopeChain()), FRESH, NOW, advancing);

    assertThat(decision.latencyNanos()).isEqualTo(1_000L);
  }

  // ---- consumption is read at the binding scope -----------------------------------------------

  @Test
  void aCeilingIsCheckedAgainstTheConsumptionOfTheScopeThatAuthoredIt() {
    final PolicySnapshot snapshot =
        snapshot(
            1L,
            document(
                PROJECT_REF, 1L, rule("p", PolicyType.MAX_REQUESTS, PolicyValue.Limit.of(10))));
    final PolicyRequest request = request().build();

    // The organization's counter is exhausted, the project's is not. A per-project cap measured
    // against its parent's counter would refuse a project that has consumed nothing.
    final UsageLookup perScope =
        scope -> Optional.of(scope.scope() == PolicyScope.PROJECT ? usage(0, 0) : usage(999, 0));

    assertThat(
            EVALUATOR
                .evaluate(
                    request,
                    snapshot.effectiveFor(request.scopeChain()),
                    perScope,
                    NOW,
                    TickerPort.FROZEN)
                .verdict())
        .isEqualTo(Verdict.ALLOW);
  }

  @Test
  void aRequestOutsideTheChainCannotReadAnotherTenantsPolicy() {
    final PolicySnapshot snapshot =
        snapshot(
            1L,
            document(
                PolicyScopeRef.of(PolicyScope.ORGANIZATION, "org-other"),
                1L,
                rule("other", PolicyType.EMERGENCY_KILL_SWITCH, PolicyValue.Flag.TRUE)));
    final PolicyRequest request = request().build();

    // Another organization's kill switch is not merely ignored — it is structurally unreachable,
    // because resolution only ever probes the nodes on this request's own chain.
    assertThat(
            EVALUATOR
                .evaluate(
                    request,
                    snapshot.effectiveFor(request.scopeChain()),
                    FRESH,
                    NOW,
                    TickerPort.FROZEN)
                .verdict())
        .isEqualTo(Verdict.ALLOW);
  }

  @Test
  void modelsSurvivingBothListsBecomeTheResolvedModelScope() {
    final PolicyDecision decision =
        decide(
            request().build(),
            rule("a", PolicyType.MODEL_ALLOW_LIST, PolicyValue.Values.of(MODEL.value(), "model.b")),
            rule("d", PolicyType.MODEL_DENY_LIST, PolicyValue.Values.of("model.b")));

    assertThat(decision.context().allowedModels()).containsExactly(MODEL.value());
  }

  @Test
  void anUnknownModelIsRejectedByAnAllowListEvenWhenTheDenyListIsSilent() {
    final PolicyDecision decision =
        decide(
            request().model(new CanonicalModelId("model.rogue")).build(),
            rule("a", PolicyType.MODEL_ALLOW_LIST, PolicyValue.Values.of(MODEL.value())));

    assertThat(decision.verdict()).isEqualTo(Verdict.DENY);
  }

  // ---- capability declaration -----------------------------------------------------------------

  @ParameterizedTest
  @MethodSource("capabilities")
  void aCapabilityTheRequestNeverAskedForCannotDenyIt(
      final PolicyType type, final UnaryOperator<PolicyRequest.Builder> declare) {
    // A capability rule reads "if the request uses this, policy must permit it". Only the
    // declaring side was tested, so nothing proved a request is judged on what it actually asks
    // for rather than on every capability the gateway knows about. Both directions are asserted
    // against the same switched-off rule, so the difference can only come from the declaration.
    final PolicyRule switchedOff = rule("cap", type, PolicyValue.Flag.FALSE);

    assertThat(decide(declare.apply(request()).build(), switchedOff).verdict())
        .isEqualTo(Verdict.DENY);
    assertThat(decide(request().build(), switchedOff).verdict()).isEqualTo(Verdict.ALLOW);
  }

  private static Stream<Arguments> capabilities() {
    return Stream.of(
        arguments(PolicyType.REASONING_ALLOWED, declaring(b -> b.reasoning(true))),
        arguments(PolicyType.VISION_ALLOWED, declaring(b -> b.vision(true))),
        arguments(PolicyType.IMAGE_GENERATION_ALLOWED, declaring(b -> b.imageGeneration(true))),
        arguments(PolicyType.AUDIO_ALLOWED, declaring(b -> b.audio(true))),
        arguments(PolicyType.EMBEDDING_ALLOWED, declaring(b -> b.embedding(true))),
        arguments(PolicyType.FINE_TUNING_ALLOWED, declaring(b -> b.fineTuning(true))),
        arguments(PolicyType.BATCH_ALLOWED, declaring(b -> b.batch(true))));
  }

  private static UnaryOperator<PolicyRequest.Builder> declaring(
      final UnaryOperator<PolicyRequest.Builder> declare) {
    return declare;
  }

  // ---- provider routing -----------------------------------------------------------------------

  @Test
  void aProviderAllowListRefusesOnlyWhenNothingTheRequestOffersSurvives() {
    // Provider lists decide what is left to route to, and no test had ever supplied a candidate
    // set, so both list evaluations were unexercised. The boundary that matters is "one survivor
    // is still routable" — refusing there would strand traffic that policy actually permits.
    final PolicyRule allow =
        rule("allow", PolicyType.PROVIDER_ALLOW_LIST, PolicyValue.Values.of("openai"));

    assertThat(decide(request().candidateProviders(Set.of("bedrock")).build(), allow).verdict())
        .isEqualTo(Verdict.DENY);
    assertThat(
            decide(request().candidateProviders(Set.of("bedrock", "openai")).build(), allow)
                .verdict())
        .isEqualTo(Verdict.ALLOW);
  }

  @Test
  void aProviderDenyListRefusesOnlyWhenItRemovesEveryCandidate() {
    final PolicyRule deny =
        rule("deny", PolicyType.PROVIDER_DENY_LIST, PolicyValue.Values.of("openai"));

    assertThat(decide(request().candidateProviders(Set.of("openai")).build(), deny).verdict())
        .isEqualTo(Verdict.DENY);
    assertThat(
            decide(request().candidateProviders(Set.of("openai", "bedrock")).build(), deny)
                .verdict())
        .isEqualTo(Verdict.ALLOW);
  }
}

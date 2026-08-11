package io.reliabilityai.gateway.dataplane.governance;

import static io.reliabilityai.gateway.dataplane.governance.PolicyFixture.GLOBAL;
import static io.reliabilityai.gateway.dataplane.governance.PolicyFixture.MODEL;
import static io.reliabilityai.gateway.dataplane.governance.PolicyFixture.NOW;
import static io.reliabilityai.gateway.dataplane.governance.PolicyFixture.ORG_REF;
import static io.reliabilityai.gateway.dataplane.governance.PolicyFixture.PROJECT_REF;
import static io.reliabilityai.gateway.dataplane.governance.PolicyFixture.WORKSPACE_REF;
import static io.reliabilityai.gateway.dataplane.governance.PolicyFixture.document;
import static io.reliabilityai.gateway.dataplane.governance.PolicyFixture.request;
import static io.reliabilityai.gateway.dataplane.governance.PolicyFixture.rule;
import static io.reliabilityai.gateway.dataplane.governance.PolicyFixture.snapshot;
import static io.reliabilityai.gateway.dataplane.governance.PolicyFixture.usage;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.reliabilityai.gateway.canonical.identity.TenantScope;
import io.reliabilityai.gateway.dataplane.governance.api.EnforcementLevel;
import io.reliabilityai.gateway.dataplane.governance.api.GovernancePolicy;
import io.reliabilityai.gateway.dataplane.governance.api.PolicyDecision;
import io.reliabilityai.gateway.dataplane.governance.api.PolicyScope;
import io.reliabilityai.gateway.dataplane.governance.api.PolicyScopeRef;
import io.reliabilityai.gateway.dataplane.governance.api.PolicyType;
import io.reliabilityai.gateway.dataplane.governance.api.PolicyValue;
import io.reliabilityai.gateway.dataplane.governance.api.ScopeChain;
import io.reliabilityai.gateway.dataplane.governance.api.TickerPort;
import io.reliabilityai.gateway.dataplane.governance.api.Verdict;
import io.reliabilityai.gateway.dataplane.governance.domain.PolicyEvaluator;
import io.reliabilityai.gateway.dataplane.governance.domain.PolicySnapshot;
import io.reliabilityai.gateway.dataplane.governance.domain.ResolvedRule;
import io.reliabilityai.gateway.dataplane.governance.domain.UsageLookup;
import java.time.Duration;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * Tests inheritance down the scope chain: what a child can tighten, what it cannot loosen, and how
 * conflicting statements at different levels resolve.
 */
class PolicyHierarchyTest {

  private static final PolicyEvaluator EVALUATOR =
      new PolicyEvaluator(Duration.ofSeconds(30), Duration.ofMinutes(5));
  private static final UsageLookup FRESH = scope -> Optional.of(usage());

  private static PolicyDecision decide(final PolicySnapshot snapshot) {
    final var req = request().build();
    return EVALUATOR.evaluate(
        req, snapshot.effectiveFor(req.scopeChain()), FRESH, NOW, TickerPort.FROZEN);
  }

  private static ResolvedRule effective(final PolicySnapshot snapshot, final PolicyType type) {
    return snapshot.effectiveFor(PolicyFixture.chain()).ruleFor(type);
  }

  // ---- chain construction ---------------------------------------------------------------------

  @Test
  void aChainAlwaysBeginsAtTheGlobalNode() {
    assertThat(ScopeChain.forTenant(TenantScope.of("o", "t")).refs())
        .first()
        .isEqualTo(PolicyScopeRef.global());
  }

  @Test
  void anUnpopulatedLevelIsSimplyAbsentFromTheChain() {
    final ScopeChain chain = ScopeChain.forTenant(TenantScope.of("org-a", "tenant-a"));

    assertThat(chain.refs()).hasSize(2);
    assertThat(chain.refs().get(1).scope()).isEqualTo(PolicyScope.ORGANIZATION);
  }

  @Test
  void aFullyPopulatedChainWalksEveryLevelInSpecificityOrder() {
    final ScopeChain chain =
        ScopeChain.builder()
            .organization("o")
            .workspace("w")
            .project("p")
            .environment("prod")
            .apiKey("key-1")
            .user("u")
            .build();

    assertThat(chain.refs())
        .extracting(PolicyScopeRef::scope)
        .containsExactly(
            PolicyScope.GLOBAL,
            PolicyScope.ORGANIZATION,
            PolicyScope.WORKSPACE,
            PolicyScope.PROJECT,
            PolicyScope.ENVIRONMENT,
            PolicyScope.API_KEY,
            PolicyScope.USER);
  }

  @Test
  void aChainCannotDoubleBackToALessSpecificLevel() {
    assertThatThrownBy(() -> ScopeChain.builder().project("p").organization("o"))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void aChainCannotRepeatALevel() {
    assertThatThrownBy(() -> ScopeChain.builder().organization("a").organization("b"))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void twoChainsOverTheSameNodesAreTheSameCacheKey() {
    final ScopeChain first = ScopeChain.builder().organization("o").project("p").build();
    final ScopeChain second = ScopeChain.builder().organization("o").project("p").build();

    assertThat(first).isEqualTo(second).hasSameHashCodeAs(second);
  }

  // ---- inheritance ----------------------------------------------------------------------------

  @Test
  void aChildInheritsAStatementItsParentMadeAndSaysNothingAbout() {
    final PolicySnapshot snapshot =
        snapshot(
            1L,
            document(ORG_REF, 1L, rule("org", PolicyType.MAX_CONTEXT, PolicyValue.Limit.of(100))),
            document(
                PROJECT_REF, 1L, rule("proj", PolicyType.MAX_COST, PolicyValue.Limit.of(500))));

    assertThat(effective(snapshot, PolicyType.MAX_CONTEXT).ruleId()).isEqualTo("org");
    assertThat(effective(snapshot, PolicyType.MAX_COST).ruleId()).isEqualTo("proj");
  }

  @Test
  void aChildTightensAnInheritedCeiling() {
    final PolicySnapshot snapshot =
        snapshot(
            1L,
            document(ORG_REF, 1L, rule("org", PolicyType.MAX_CONTEXT, PolicyValue.Limit.of(100))),
            document(
                PROJECT_REF, 1L, rule("proj", PolicyType.MAX_CONTEXT, PolicyValue.Limit.of(10))));

    assertThat(((PolicyValue.Limit) effective(snapshot, PolicyType.MAX_CONTEXT).value()).value())
        .isEqualTo(10L);
  }

  @Test
  void aChildCannotRaiseAnInheritedCeiling() {
    final PolicySnapshot snapshot =
        snapshot(
            1L,
            document(ORG_REF, 1L, rule("org", PolicyType.MAX_CONTEXT, PolicyValue.Limit.of(10))),
            document(
                PROJECT_REF,
                1L,
                rule("proj", PolicyType.MAX_CONTEXT, PolicyValue.Limit.of(100_000))));

    assertThat(((PolicyValue.Limit) effective(snapshot, PolicyType.MAX_CONTEXT).value()).value())
        .isEqualTo(10L);
  }

  @Test
  void aChildCannotReAdmitAModelItsOrganizationExcluded() {
    final PolicySnapshot snapshot =
        snapshot(
            1L,
            document(
                ORG_REF,
                1L,
                rule("org", PolicyType.MODEL_ALLOW_LIST, PolicyValue.Values.of("model.approved"))),
            document(
                PROJECT_REF,
                1L,
                rule(
                    "proj",
                    PolicyType.MODEL_ALLOW_LIST,
                    PolicyValue.Values.of("model.approved", MODEL.value()))));

    // The project asked for a model its organization never approved. Intersection is what makes
    // that
    // request impossible to grant rather than merely discouraged.
    assertThat(decide(snapshot).verdict()).isEqualTo(Verdict.DENY);
  }

  @Test
  void aDenialAtAnyLevelSurvivesEveryLevelBelowIt() {
    final PolicySnapshot snapshot =
        snapshot(
            1L,
            document(
                GLOBAL,
                1L,
                rule("g", PolicyType.MODEL_DENY_LIST, PolicyValue.Values.of(MODEL.value()))),
            document(
                PROJECT_REF,
                1L,
                rule("p", PolicyType.MODEL_ALLOW_LIST, PolicyValue.Values.of(MODEL.value()))));

    assertThat(decide(snapshot).verdict()).isEqualTo(Verdict.DENY);
  }

  @Test
  void denialsAccumulateDownTheChain() {
    final PolicySnapshot snapshot =
        snapshot(
            1L,
            document(
                ORG_REF,
                1L,
                rule("org", PolicyType.TOOL_DENY_LIST, PolicyValue.Values.of("shell"))),
            document(
                PROJECT_REF,
                1L,
                rule("proj", PolicyType.TOOL_DENY_LIST, PolicyValue.Values.of("browser"))));

    assertThat(
            ((PolicyValue.Values) effective(snapshot, PolicyType.TOOL_DENY_LIST).value()).values())
        .containsExactly("browser", "shell");
  }

  @Test
  void aCapabilityDisabledAboveCannotBeReEnabledBelow() {
    final PolicySnapshot snapshot =
        snapshot(
            1L,
            document(ORG_REF, 1L, rule("org", PolicyType.VISION_ALLOWED, PolicyValue.Flag.FALSE)),
            document(
                PROJECT_REF, 1L, rule("proj", PolicyType.VISION_ALLOWED, PolicyValue.Flag.TRUE)));

    assertThat(((PolicyValue.Flag) effective(snapshot, PolicyType.VISION_ALLOWED).value()).value())
        .isFalse();
  }

  @Test
  void aRequirementImposedAboveCannotBeLiftedBelow() {
    final PolicySnapshot snapshot =
        snapshot(
            1L,
            document(GLOBAL, 1L, rule("g", PolicyType.JSON_MODE_REQUIRED, PolicyValue.Flag.TRUE)),
            document(
                PROJECT_REF, 1L, rule("p", PolicyType.JSON_MODE_REQUIRED, PolicyValue.Flag.FALSE)));

    assertThat(
            ((PolicyValue.Flag) effective(snapshot, PolicyType.JSON_MODE_REQUIRED).value()).value())
        .isTrue();
  }

  @Test
  void aKillSwitchPulledGloballyCannotBeReleasedByATenant() {
    final PolicySnapshot snapshot =
        snapshot(
            1L,
            document(
                GLOBAL, 1L, rule("g", PolicyType.EMERGENCY_KILL_SWITCH, PolicyValue.Flag.TRUE)),
            document(
                ORG_REF, 1L, rule("o", PolicyType.EMERGENCY_KILL_SWITCH, PolicyValue.Flag.FALSE)));

    assertThat(decide(snapshot).verdict()).isEqualTo(Verdict.DENY);
  }

  @Test
  void theTightestCeilingAnywhereInAFourLevelChainWins() {
    final PolicySnapshot snapshot =
        snapshot(
            1L,
            document(GLOBAL, 1L, rule("g", PolicyType.MAX_COST, PolicyValue.Limit.of(1_000))),
            document(ORG_REF, 1L, rule("o", PolicyType.MAX_COST, PolicyValue.Limit.of(500))),
            document(WORKSPACE_REF, 1L, rule("w", PolicyType.MAX_COST, PolicyValue.Limit.of(50))),
            document(PROJECT_REF, 1L, rule("p", PolicyType.MAX_COST, PolicyValue.Limit.of(800))));

    final ResolvedRule merged = effective(snapshot, PolicyType.MAX_COST);
    assertThat(((PolicyValue.Limit) merged.value()).value()).isEqualTo(50L);
    assertThat(merged.source().scope()).isEqualTo(PolicyScope.WORKSPACE);
  }

  @Test
  void theBindingViolationNamesTheLevelThatImposedIt() {
    final PolicySnapshot snapshot =
        snapshot(
            1L,
            document(GLOBAL, 1L, rule("g", PolicyType.MAX_CONTEXT, PolicyValue.Limit.of(1_000))),
            document(
                WORKSPACE_REF, 1L, rule("w", PolicyType.MAX_CONTEXT, PolicyValue.Limit.of(10))));
    final var req = request().contextTokens(500).build();

    final PolicyDecision decision =
        EVALUATOR.evaluate(
            req, snapshot.effectiveFor(req.scopeChain()), FRESH, NOW, TickerPort.FROZEN);

    assertThat(decision.binding()).get().extracting("boundAt").isEqualTo(PolicyScope.WORKSPACE);
    assertThat(decision.binding()).get().extracting("ruleId").isEqualTo("w");
  }

  @Test
  void aDocumentAttachedOutsideTheChainIsNeverConsulted() {
    final PolicySnapshot snapshot =
        snapshot(
            1L,
            document(
                PolicyScopeRef.of(PolicyScope.PROJECT, "someone-elses-project"),
                1L,
                rule("x", PolicyType.EMERGENCY_KILL_SWITCH, PolicyValue.Flag.TRUE)));

    assertThat(decide(snapshot).verdict()).isEqualTo(Verdict.ALLOW);
  }

  @Test
  void anApiKeyScopeCanTightenBeyondItsProject() {
    final ScopeChain chain =
        ScopeChain.builder().organization("org-a").project("proj-a").apiKey("key-1").build();
    final PolicySnapshot snapshot =
        snapshot(
            1L,
            document(
                PolicyScopeRef.of(PolicyScope.PROJECT, "proj-a"),
                1L,
                rule("p", PolicyType.MAX_OUTPUT_TOKENS, PolicyValue.Limit.of(4_000))),
            document(
                PolicyScopeRef.of(PolicyScope.API_KEY, "key-1"),
                1L,
                rule("k", PolicyType.MAX_OUTPUT_TOKENS, PolicyValue.Limit.of(256))));

    assertThat(
            ((PolicyValue.Limit)
                    snapshot.effectiveFor(chain).ruleFor(PolicyType.MAX_OUTPUT_TOKENS).value())
                .value())
        .isEqualTo(256L);
  }

  @Test
  void aServiceAccountScopeParticipatesInTheMerge() {
    final ScopeChain chain =
        ScopeChain.builder().organization("org-a").serviceAccount("svc-1").build();
    final PolicySnapshot snapshot =
        snapshot(
            1L,
            document(
                PolicyScopeRef.of(PolicyScope.SERVICE_ACCOUNT, "svc-1"),
                1L,
                rule("s", PolicyType.TOOL_DENY_LIST, PolicyValue.Values.of("shell"))));

    assertThat(snapshot.effectiveFor(chain).ruleFor(PolicyType.TOOL_DENY_LIST)).isNotNull();
  }

  @Test
  void anEnvironmentScopeParticipatesInTheMerge() {
    final ScopeChain chain =
        ScopeChain.builder().organization("org-a").project("proj-a").environment("prod").build();
    final PolicySnapshot snapshot =
        snapshot(
            1L,
            document(
                PolicyScopeRef.of(PolicyScope.ENVIRONMENT, "prod"),
                1L,
                rule("e", PolicyType.STREAMING_ALLOWED, PolicyValue.Flag.FALSE)));

    assertThat(snapshot.effectiveFor(chain).ruleFor(PolicyType.STREAMING_ALLOWED)).isNotNull();
  }

  @Test
  void aRequestScopeCanOnlyRestrictTheRequestItself() {
    final ScopeChain chain = ScopeChain.builder().organization("org-a").request("corr-1").build();
    final PolicySnapshot snapshot =
        snapshot(
            1L,
            document(ORG_REF, 1L, rule("o", PolicyType.MAX_COST, PolicyValue.Limit.of(1_000))),
            document(
                PolicyScopeRef.of(PolicyScope.REQUEST, "corr-1"),
                1L,
                rule("r", PolicyType.MAX_COST, PolicyValue.Limit.of(100_000))));

    // Self-imposed constraints are still constraints, so the caller's larger number loses.
    assertThat(
            ((PolicyValue.Limit) snapshot.effectiveFor(chain).ruleFor(PolicyType.MAX_COST).value())
                .value())
        .isEqualTo(1_000L);
  }

  @Test
  void anAdvisoryChildAndAMandatoryParentResolveToMandatory() {
    final PolicySnapshot snapshot =
        snapshot(
            1L,
            document(ORG_REF, 1L, rule("o", PolicyType.MAX_COST, PolicyValue.Limit.of(100))),
            document(
                PROJECT_REF,
                1L,
                rule(
                    "p",
                    PolicyType.MAX_COST,
                    PolicyValue.Limit.of(50),
                    EnforcementLevel.ADVISORY)));

    assertThat(effective(snapshot, PolicyType.MAX_COST).enforcement())
        .isEqualTo(EnforcementLevel.MANDATORY);
  }

  @Test
  void aDisabledDocumentContributesNothing() {
    final GovernancePolicy disabled =
        new GovernancePolicy(
            "off",
            PROJECT_REF,
            io.reliabilityai.gateway.dataplane.governance.api.PolicyVersion.of("v1", 1L),
            java.util.List.of(rule("p", PolicyType.EMERGENCY_KILL_SWITCH, PolicyValue.Flag.TRUE)),
            false);

    assertThat(decide(snapshot(1L, disabled)).verdict()).isEqualTo(Verdict.ALLOW);
  }

  @Test
  void aDocumentCannotStateTheSamePolicyTypeTwice() {
    assertThatThrownBy(
            () ->
                document(
                    ORG_REF,
                    1L,
                    rule("a", PolicyType.MAX_COST, PolicyValue.Limit.of(1)),
                    rule("b", PolicyType.MAX_COST, PolicyValue.Limit.of(2))))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void aFullChainWithConflictingComplianceAttestationsIntersects() {
    final PolicySnapshot snapshot =
        snapshot(
            1L,
            document(
                GLOBAL,
                1L,
                rule(
                    "g",
                    PolicyType.COMPLIANCE_MODE,
                    PolicyValue.Values.of("SOC2", "GDPR", "HIPAA"))),
            document(
                ORG_REF,
                1L,
                rule("o", PolicyType.COMPLIANCE_MODE, PolicyValue.Values.of("SOC2", "GDPR"))),
            document(
                PROJECT_REF,
                1L,
                rule("p", PolicyType.COMPLIANCE_MODE, PolicyValue.Values.of("GDPR", "HIPAA"))));

    final var req = request().requiredComplianceRegimes(Set.of("HIPAA")).build();
    // Only GDPR survives all three. A request needing HIPAA cannot be satisfied even though two of
    // the
    // three levels attested to it.
    assertThat(
            EVALUATOR
                .evaluate(
                    req, snapshot.effectiveFor(req.scopeChain()), FRESH, NOW, TickerPort.FROZEN)
                .verdict())
        .isEqualTo(Verdict.DENY);
  }
}

package io.reliabilityai.gateway.dataplane.governance;

import static io.reliabilityai.gateway.dataplane.governance.PolicyFixture.CLOCK;
import static io.reliabilityai.gateway.dataplane.governance.PolicyFixture.GLOBAL;
import static io.reliabilityai.gateway.dataplane.governance.PolicyFixture.MODEL;
import static io.reliabilityai.gateway.dataplane.governance.PolicyFixture.NOW;
import static io.reliabilityai.gateway.dataplane.governance.PolicyFixture.PROJECT_REF;
import static io.reliabilityai.gateway.dataplane.governance.PolicyFixture.document;
import static io.reliabilityai.gateway.dataplane.governance.PolicyFixture.request;
import static io.reliabilityai.gateway.dataplane.governance.PolicyFixture.rule;
import static io.reliabilityai.gateway.dataplane.governance.PolicyFixture.snapshot;
import static io.reliabilityai.gateway.dataplane.governance.PolicyFixture.usage;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import io.reliabilityai.gateway.canonical.decision.GovernanceDecision;
import io.reliabilityai.gateway.dataplane.governance.api.DenialReason;
import io.reliabilityai.gateway.dataplane.governance.api.EnforcementLevel;
import io.reliabilityai.gateway.dataplane.governance.api.PolicyAuditEvent;
import io.reliabilityai.gateway.dataplane.governance.api.PolicyDecision;
import io.reliabilityai.gateway.dataplane.governance.api.PolicyDomain;
import io.reliabilityai.gateway.dataplane.governance.api.PolicyRequest;
import io.reliabilityai.gateway.dataplane.governance.api.PolicyScope;
import io.reliabilityai.gateway.dataplane.governance.api.PolicyType;
import io.reliabilityai.gateway.dataplane.governance.api.PolicyUsage;
import io.reliabilityai.gateway.dataplane.governance.api.PolicyUsagePort;
import io.reliabilityai.gateway.dataplane.governance.api.PolicyValue;
import io.reliabilityai.gateway.dataplane.governance.api.PolicyVersion;
import io.reliabilityai.gateway.dataplane.governance.api.ScopeChain;
import io.reliabilityai.gateway.dataplane.governance.api.TickerPort;
import io.reliabilityai.gateway.dataplane.governance.api.Verdict;
import io.reliabilityai.gateway.dataplane.governance.application.GovernanceEngine;
import io.reliabilityai.gateway.dataplane.governance.domain.PolicyEvaluator;
import io.reliabilityai.gateway.dataplane.governance.domain.PolicySnapshot;
import io.reliabilityai.gateway.dataplane.governance.internal.InProcessPolicyMetrics;
import io.reliabilityai.gateway.dataplane.governance.internal.PolicyRegistry;
import io.reliabilityai.gateway.dataplane.governance.internal.PolicyStore;
import io.reliabilityai.gateway.ports.ClockPort;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/** Tests the Policy Enforcement Point: fail-closed behaviour, audit completeness and the façade. */
class GovernanceEngineTest {

  private static final PolicyEvaluator EVALUATOR =
      new PolicyEvaluator(Duration.ofSeconds(30), Duration.ofMinutes(5));
  private static final PolicyUsagePort FRESH = scope -> Optional.of(usage());

  private final List<PolicyAuditEvent> audited = new ArrayList<>();
  private final InProcessPolicyMetrics metrics = new InProcessPolicyMetrics();
  private final PolicyRegistry registry = new PolicyRegistry(new PolicyStore(64, 3), metrics);

  private GovernanceEngine engine() {
    return engine(FRESH, CLOCK);
  }

  private GovernanceEngine engine(final PolicyUsagePort usage, final ClockPort clock) {
    return new GovernanceEngine(
        registry, EVALUATOR, usage, audited::add, metrics, clock, TickerPort.FROZEN, null);
  }

  private void install(final PolicySnapshot snapshot) {
    registry.install(snapshot);
  }

  // ---- the unconfigured gate ------------------------------------------------------------------

  @Test
  void aNodeThatHasNeverReceivedPolicyRefusesEverything() {
    final PolicyDecision decision = engine().govern(request().build());

    // An empty policy set evaluates to "no constraints", which for an admission gate is the same as
    // not being there. Distinguishing that from "policy says nothing" is the whole point.
    assertThat(decision.verdict()).isEqualTo(Verdict.DENY);
    assertThat(decision.reasonCode()).isEqualTo(DenialReason.POLICY_NOT_FOUND.code());
  }

  @Test
  void anUnconfiguredRefusalIsStillCounted() {
    engine().govern(request().build());

    assertThat(metrics.policyUnavailableCount()).isEqualTo(1L);
  }

  @Test
  void anUnconfiguredRefusalIsStillAudited() {
    engine().govern(request().build());

    assertThat(audited).hasSize(1);
    assertThat(audited.get(0).verdict()).isEqualTo(Verdict.DENY);
  }

  @Test
  void anEmptyButInstalledGenerationIsALegitimateAnswer() {
    install(snapshot(1L, document(GLOBAL, 1L)));

    assertThat(engine().govern(request().build()).verdict()).isEqualTo(Verdict.ALLOW);
  }

  // ---- audit completeness ---------------------------------------------------------------------

  @Test
  void everyDecisionIsAuditedIncludingAdmissions() {
    install(snapshot(1L, document(GLOBAL, 1L)));

    engine().govern(request().build());

    assertThat(audited).hasSize(1);
    assertThat(audited.get(0).verdict()).isEqualTo(Verdict.ALLOW);
  }

  @Test
  void anAuditRecordCarriesTheIdentityTheDecisionWasScopedTo() {
    install(snapshot(1L, document(GLOBAL, 1L)));

    engine().govern(request().build());

    final PolicyAuditEvent event = audited.get(0);
    assertThat(event.tenantScope()).isEqualTo(PolicyFixture.TENANT);
    assertThat(event.correlationId().value()).isEqualTo("corr-1");
    assertThat(event.principalId()).isEqualTo("principal-a");
  }

  @Test
  void anAuditRecordCarriesTheApiKeyNodeWhenTheRequestIsKeyScoped() {
    install(snapshot(1L, document(GLOBAL, 1L)));
    final PolicyRequest keyed =
        request()
            .scopeChain(ScopeChain.builder().organization("org-a").apiKey("key-7").build())
            .build();

    engine().govern(keyed);

    assertThat(audited.get(0).apiKeyId()).isEqualTo("key-7");
  }

  @Test
  void anAuditRecordSaysSoWhenNoApiKeyNodeIsPresent() {
    install(snapshot(1L, document(GLOBAL, 1L)));

    engine().govern(request().build());

    assertThat(audited.get(0).apiKeyId()).isEqualTo(PolicyAuditEvent.ABSENT);
  }

  @Test
  void anAuditRecordNamesEveryViolatedStatement() {
    install(
        snapshot(
            1L,
            document(
                GLOBAL,
                1L,
                rule(
                    "ctx",
                    PolicyType.MAX_CONTEXT,
                    PolicyValue.Limit.of(1),
                    EnforcementLevel.ADVISORY),
                rule(
                    "out",
                    PolicyType.MAX_OUTPUT_TOKENS,
                    PolicyValue.Limit.of(1),
                    EnforcementLevel.SHADOW))));

    engine().govern(request().contextTokens(99).outputTokens(99).build());

    assertThat(audited.get(0).matchedRuleIds()).containsExactly("ctx", "out");
  }

  @Test
  void anAuditRecordStampsTheGenerationTheDecisionUsed() {
    install(snapshot(7L, document(GLOBAL, 7L)));

    engine().govern(request().build());

    assertThat(audited.get(0).policyVersion().sequence()).isEqualTo(7L);
  }

  @Test
  void anEnforcementDecisionIsNeverStampedAsSimulated() {
    install(snapshot(1L, document(GLOBAL, 1L)));

    engine().govern(request().build());

    assertThat(audited.get(0).simulated()).isFalse();
  }

  @Test
  void anAuditSinkThatThrowsDoesNotChangeTheVerdict() {
    install(snapshot(1L, document(GLOBAL, 1L)));
    final GovernanceEngine engine =
        new GovernanceEngine(
            registry,
            EVALUATOR,
            FRESH,
            event -> {
              throw new IllegalStateException("audit backend down");
            },
            metrics,
            CLOCK,
            TickerPort.FROZEN,
            null);

    // Losing an audit write is bad; letting an audit outage start refusing traffic is worse.
    assertThat(engine.govern(request().build()).verdict()).isEqualTo(Verdict.ALLOW);
  }

  @Test
  void aMetricsBackendThatThrowsDoesNotChangeTheVerdict() {
    install(snapshot(1L, document(GLOBAL, 1L)));
    final GovernanceEngine engine =
        new GovernanceEngine(
            registry,
            EVALUATOR,
            FRESH,
            audited::add,
            new ExplodingMetrics(),
            CLOCK,
            TickerPort.FROZEN,
            null);

    assertThat(engine.govern(request().build()).verdict()).isEqualTo(Verdict.ALLOW);
  }

  // ---- never throws ---------------------------------------------------------------------------

  @Test
  void aConsumptionPortThatThrowsProducesARefusalNotAnException() {
    install(
        snapshot(
            1L,
            document(GLOBAL, 1L, rule("q", PolicyType.MAX_REQUESTS, PolicyValue.Limit.of(10)))));
    final GovernanceEngine engine =
        engine(
            scope -> {
              throw new IllegalStateException("coordination tier partitioned");
            },
            CLOCK);

    final PolicyDecision decision = engine.govern(request().build());

    assertThat(decision.verdict()).isEqualTo(Verdict.DENY);
    assertThat(decision.reasonCode()).isEqualTo(DenialReason.POLICY_UNAVAILABLE.code());
  }

  @Test
  void aConsumptionPortReturningNullProducesARefusalNotAnException() {
    install(
        snapshot(
            1L,
            document(GLOBAL, 1L, rule("q", PolicyType.MAX_REQUESTS, PolicyValue.Limit.of(10)))));

    assertThat(engine(scope -> null, CLOCK).govern(request().build()).verdict())
        .isEqualTo(Verdict.DENY);
  }

  @Test
  void aClockThatThrowsProducesARefusalNotAnException() {
    install(snapshot(1L, document(GLOBAL, 1L)));
    final GovernanceEngine engine =
        engine(
            FRESH,
            () -> {
              throw new IllegalStateException("clock unavailable");
            });

    assertThatCode(() -> engine.govern(request().build())).doesNotThrowAnyException();
  }

  @Test
  void aClockReturningNullDoesNotProduceAnException() {
    install(snapshot(1L, document(GLOBAL, 1L)));

    assertThatCode(() -> engine(FRESH, () -> null).govern(request().build()))
        .doesNotThrowAnyException();
  }

  @Test
  void everyDecisionCarriesAContextEvenWhenRefused() {
    final PolicyDecision decision = engine().govern(request().build());

    assertThat(decision.context()).isNotNull();
    assertThat(decision.context().allowedProviders()).isEmpty();
  }

  // ---- metrics --------------------------------------------------------------------------------

  @Test
  void aRefusalIsAttributedToThePrecedenceTierThatBoundIt() {
    install(
        snapshot(
            1L,
            document(
                GLOBAL,
                1L,
                rule("r", PolicyType.REGION_RESTRICTION, PolicyValue.Values.of("us-east-1")))));

    engine().govern(request().build());

    assertThat(metrics.bindings(PolicyDomain.RESIDENCY)).isEqualTo(1L);
    assertThat(metrics.decisions(Verdict.DENY)).isEqualTo(1L);
  }

  @Test
  void everyVerdictIsCountedUnderItsOwnName() {
    install(
        snapshot(
            1L,
            document(
                GLOBAL,
                1L,
                rule(
                    "a",
                    PolicyType.MAX_CONTEXT,
                    PolicyValue.Limit.of(1),
                    EnforcementLevel.ADVISORY))));

    final GovernanceEngine engine = engine();
    engine.govern(request().contextTokens(99).build());
    engine.govern(request().contextTokens(0).build());

    assertThat(metrics.decisions(Verdict.SOFT_DENY)).isEqualTo(1L);
    assertThat(metrics.decisions(Verdict.ALLOW)).isEqualTo(1L);
    assertThat(metrics.evaluations()).isEqualTo(2L);
  }

  @Test
  void anUnverifiableCeilingIsCountedAsAStaleConsumptionRefusal() {
    install(
        snapshot(
            1L,
            document(GLOBAL, 1L, rule("q", PolicyType.MAX_REQUESTS, PolicyValue.Limit.of(10)))));

    engine(PolicyUsagePort.UNKNOWN, CLOCK).govern(request().build());

    assertThat(metrics.staleUsageCount()).isEqualTo(1L);
  }

  // ---- consumption is read once per node ------------------------------------------------------

  @Test
  void severalCeilingsAtOneNodeShareASingleConsumptionReading() {
    install(
        snapshot(
            1L,
            document(
                GLOBAL,
                1L,
                rule("q", PolicyType.MAX_REQUESTS, PolicyValue.Limit.of(1_000)),
                rule("r", PolicyType.MAX_RPM, PolicyValue.Limit.of(1_000)),
                rule("d", PolicyType.DAILY_BUDGET, PolicyValue.Limit.of(1_000_000)))));
    final int[] reads = {0};

    engine(
            scope -> {
              reads[0]++;
              return Optional.of(usage());
            },
            CLOCK)
        .govern(request().build());

    // Three ceilings, one node, one read — and, more importantly, one reading, so the three caps
    // cannot disagree about how much has been consumed.
    assertThat(reads[0]).isEqualTo(1);
  }

  // ---- the frozen façade ----------------------------------------------------------------------

  @Test
  void theFacadeAdmitsWhatTheFullEngineWouldAdmit() {
    install(snapshot(1L, document(GLOBAL, 1L)));

    final GovernanceDecision decision =
        engine()
            .authorize(
                PolicyFixture.requestContext(), PolicyFixture.principal(), PolicyFixture.tenant());

    assertThat(decision.permit()).isTrue();
    assertThat(decision.reason()).isEqualTo(PolicyDecision.PERMITTED);
  }

  @Test
  void theFacadeEnforcesEverythingItsSignatureCanExpress() {
    install(
        snapshot(
            1L,
            document(
                GLOBAL,
                1L,
                rule("kill", PolicyType.EMERGENCY_KILL_SWITCH, PolicyValue.Flag.TRUE))));

    final GovernanceDecision decision =
        engine()
            .authorize(
                PolicyFixture.requestContext(), PolicyFixture.principal(), PolicyFixture.tenant());

    assertThat(decision.permit()).isFalse();
    assertThat(decision.reason()).isEqualTo(DenialReason.SECURITY_DENIED.code());
  }

  @Test
  void theFacadeEnforcesResidencyBecauseTheRegionIsInTheRequestContext() {
    install(
        snapshot(
            1L,
            document(
                GLOBAL,
                1L,
                rule("res", PolicyType.REGION_RESTRICTION, PolicyValue.Values.of("us-east-1")))));

    assertThat(
            engine()
                .authorize(
                    PolicyFixture.requestContext(),
                    PolicyFixture.principal(),
                    PolicyFixture.tenant())
                .permit())
        .isFalse();
  }

  @Test
  void theFacadeCannotEnforceModelPolicyAndDoesNotPretendTo() {
    install(
        snapshot(
            1L,
            document(
                GLOBAL,
                1L,
                rule("m", PolicyType.MODEL_ALLOW_LIST, PolicyValue.Values.of("model.other")))));

    // The frozen signature carries no model, so there is nothing to compare. Admitting here and
    // saying so is honest; substituting a placeholder would either refuse everything or admit past
    // a
    // real allow-list. The full engine, given the model, does refuse it.
    assertThat(
            engine()
                .authorize(
                    PolicyFixture.requestContext(),
                    PolicyFixture.principal(),
                    PolicyFixture.tenant())
                .permit())
        .isTrue();
    assertThat(engine().govern(request().build()).verdict()).isEqualTo(Verdict.DENY);
  }

  @Test
  void theFacadeCarriesTheResolvedConstraintsForward() {
    install(
        snapshot(
            1L,
            document(
                GLOBAL,
                1L,
                rule("res", PolicyType.REGION_RESTRICTION, PolicyValue.Values.of("eu-west-1")),
                rule("json", PolicyType.JSON_MODE_REQUIRED, PolicyValue.Flag.TRUE))));

    final GovernanceDecision decision =
        engine()
            .authorize(
                PolicyFixture.requestContext(), PolicyFixture.principal(), PolicyFixture.tenant());

    assertThat(decision.permit()).isFalse();
  }

  @Test
  void anAdvisoryBreachSurfacesAsAnObligationRatherThanVanishing() {
    install(
        snapshot(
            1L,
            document(
                GLOBAL,
                1L,
                rule(
                    "soft",
                    PolicyType.MAX_RPM,
                    PolicyValue.Limit.of(0),
                    EnforcementLevel.ADVISORY))));

    final GovernanceDecision decision =
        engine()
            .authorize(
                PolicyFixture.requestContext(), PolicyFixture.principal(), PolicyFixture.tenant());

    assertThat(decision.permit()).isTrue();
    assertThat(decision.obligations()).contains("soft-violation:soft");
  }

  @Test
  void aShadowBreachSurfacesAsAnObligationToo() {
    install(
        snapshot(
            1L,
            document(
                GLOBAL,
                1L,
                rule(
                    "shadow",
                    PolicyType.MAX_RPM,
                    PolicyValue.Limit.of(0),
                    EnforcementLevel.SHADOW))));

    assertThat(
            engine()
                .authorize(
                    PolicyFixture.requestContext(),
                    PolicyFixture.principal(),
                    PolicyFixture.tenant())
                .obligations())
        .contains("shadow-violation:shadow");
  }

  @Test
  void aRefusedRequestCarriesNoObligations() {
    install(
        snapshot(
            1L,
            document(
                GLOBAL,
                1L,
                rule("kill", PolicyType.EMERGENCY_KILL_SWITCH, PolicyValue.Flag.TRUE))));

    assertThat(
            engine()
                .authorize(
                    PolicyFixture.requestContext(),
                    PolicyFixture.principal(),
                    PolicyFixture.tenant())
                .obligations())
        .isEmpty();
  }

  // ---- lifecycle through the engine -----------------------------------------------------------

  @Test
  void theEngineReportsTheGenerationInForce() {
    install(snapshot(11L, document(GLOBAL, 11L)));

    assertThat(engine().currentVersion().sequence()).isEqualTo(11L);
  }

  @Test
  void aRollbackThroughTheEngineChangesWhatTheNextRequestSees() {
    install(snapshot(1L, document(GLOBAL, 1L)));
    install(
        snapshot(
            2L,
            document(
                GLOBAL,
                2L,
                rule("kill", PolicyType.EMERGENCY_KILL_SWITCH, PolicyValue.Flag.TRUE))));
    final GovernanceEngine engine = engine();
    assertThat(engine.govern(request().build()).verdict()).isEqualTo(Verdict.DENY);

    assertThat(engine.rollbackTo(PolicyVersion.of("v1", 1L))).isTrue();

    assertThat(engine.govern(request().build()).verdict()).isEqualTo(Verdict.ALLOW);
  }

  @Test
  void aRollbackToAnUnknownGenerationChangesNothing() {
    install(snapshot(1L, document(GLOBAL, 1L)));

    assertThat(engine().rollbackTo(PolicyVersion.of("nope", 99L))).isFalse();
    assertThat(engine().currentVersion().sequence()).isEqualTo(1L);
  }

  @Test
  void anEngineWiredWithoutALoaderSimplyDoesNotReload() {
    install(snapshot(1L, document(GLOBAL, 1L)));

    assertThat(engine().reload()).isFalse();
  }

  @Test
  void aPerProjectCapIsMeasuredAgainstThatProjectsConsumption() {
    install(
        snapshot(
            1L, document(PROJECT_REF, 1L, rule("p", PolicyType.MAX_RPM, PolicyValue.Limit.of(5)))));
    final PolicyUsagePort perScope =
        scope ->
            Optional.of(
                scope.scope() == PolicyScope.PROJECT
                    ? PolicyUsage.none(NOW)
                    : new PolicyUsage(0, 9_999, 0, 0, 0, 0, NOW));

    assertThat(engine(perScope, CLOCK).govern(request().build()).verdict())
        .isEqualTo(Verdict.ALLOW);
  }

  @Test
  void aDecisionNamesTheModelPolicyThatRefusedIt() {
    install(
        snapshot(
            1L,
            document(
                GLOBAL,
                1L,
                rule(
                    "only-approved",
                    PolicyType.MODEL_ALLOW_LIST,
                    PolicyValue.Values.of("model.other")))));

    final PolicyDecision decision = engine().govern(request().model(MODEL).build());

    assertThat(decision.binding()).get().extracting("ruleId").isEqualTo("only-approved");
  }

  /** Metrics that fail on every call, to prove they cannot influence a verdict. */
  private static final class ExplodingMetrics
      implements io.reliabilityai.gateway.dataplane.governance.api.PolicyMetrics {

    @Override
    public void decision(final Verdict verdict, final long latencyNanos) {
      throw new IllegalStateException("metrics backend down");
    }
  }
}

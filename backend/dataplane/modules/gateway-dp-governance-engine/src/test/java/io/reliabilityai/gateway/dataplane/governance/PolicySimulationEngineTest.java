package io.reliabilityai.gateway.dataplane.governance;

import static io.reliabilityai.gateway.dataplane.governance.PolicyFixture.CLOCK;
import static io.reliabilityai.gateway.dataplane.governance.PolicyFixture.GLOBAL;
import static io.reliabilityai.gateway.dataplane.governance.PolicyFixture.MODEL;
import static io.reliabilityai.gateway.dataplane.governance.PolicyFixture.bundle;
import static io.reliabilityai.gateway.dataplane.governance.PolicyFixture.document;
import static io.reliabilityai.gateway.dataplane.governance.PolicyFixture.request;
import static io.reliabilityai.gateway.dataplane.governance.PolicyFixture.requestContext;
import static io.reliabilityai.gateway.dataplane.governance.PolicyFixture.rule;
import static io.reliabilityai.gateway.dataplane.governance.PolicyFixture.snapshot;
import static io.reliabilityai.gateway.dataplane.governance.PolicyFixture.usage;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.reliabilityai.gateway.dataplane.governance.api.EnforcementLevel;
import io.reliabilityai.gateway.dataplane.governance.api.PolicyAuditEvent;
import io.reliabilityai.gateway.dataplane.governance.api.PolicyCompilationException;
import io.reliabilityai.gateway.dataplane.governance.api.PolicyRequest;
import io.reliabilityai.gateway.dataplane.governance.api.PolicyType;
import io.reliabilityai.gateway.dataplane.governance.api.PolicyUsagePort;
import io.reliabilityai.gateway.dataplane.governance.api.PolicyValue;
import io.reliabilityai.gateway.dataplane.governance.api.PolicyVersion;
import io.reliabilityai.gateway.dataplane.governance.api.SimulationImpact;
import io.reliabilityai.gateway.dataplane.governance.api.SimulationResult;
import io.reliabilityai.gateway.dataplane.governance.api.TickerPort;
import io.reliabilityai.gateway.dataplane.governance.api.Verdict;
import io.reliabilityai.gateway.dataplane.governance.application.GovernanceEngine;
import io.reliabilityai.gateway.dataplane.governance.application.PolicySimulationEngine;
import io.reliabilityai.gateway.dataplane.governance.domain.PolicyEvaluator;
import io.reliabilityai.gateway.dataplane.governance.domain.PolicySnapshot;
import io.reliabilityai.gateway.dataplane.governance.internal.InProcessPolicyMetrics;
import io.reliabilityai.gateway.dataplane.governance.internal.PolicyCompiler;
import io.reliabilityai.gateway.dataplane.governance.internal.PolicyRegistry;
import io.reliabilityai.gateway.dataplane.governance.internal.PolicyStore;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * Tests "what would happen if" — against the past, the present, and a candidate not yet installed.
 */
class PolicySimulationEngineTest {

  private static final PolicyEvaluator EVALUATOR =
      new PolicyEvaluator(Duration.ofSeconds(30), Duration.ofMinutes(5));
  private static final PolicyUsagePort FRESH = scope -> Optional.of(usage());

  private final List<PolicyAuditEvent> audited = new ArrayList<>();
  private final InProcessPolicyMetrics metrics = new InProcessPolicyMetrics();
  private final PolicyRegistry registry = new PolicyRegistry(new PolicyStore(64, 5), metrics);
  private final GovernanceEngine engine =
      new GovernanceEngine(
          registry, EVALUATOR, FRESH, audited::add, metrics, CLOCK, TickerPort.FROZEN, null);
  private final PolicySimulationEngine simulator =
      new PolicySimulationEngine(engine, new PolicyCompiler(), CLOCK);

  private static PolicySnapshot permissive(final long sequence) {
    return snapshot(
        sequence,
        document(
            GLOBAL,
            sequence,
            rule("models", PolicyType.MODEL_ALLOW_LIST, PolicyValue.Values.of(MODEL.value()))));
  }

  private static PolicySnapshot restrictive(final long sequence) {
    return snapshot(
        sequence,
        document(
            GLOBAL,
            sequence,
            rule("models", PolicyType.MODEL_ALLOW_LIST, PolicyValue.Values.of("model.other"))));
  }

  // ---- simulating the current generation ------------------------------------------------------

  @Test
  void simulatingTheCurrentGenerationGivesTheSameVerdictAsEnforcingIt() {
    registry.install(permissive(1L));

    assertThat(simulator.simulateCurrent(request().build()).verdict())
        .isEqualTo(engine.govern(request().build()).verdict());
  }

  @Test
  void aSimulationLeavesNoEnforcementAuditBehind() {
    registry.install(permissive(1L));

    simulator.simulateCurrent(request().build());

    // A simulated denial that is indistinguishable from a real one makes the audit stream useless
    // as
    // evidence, because no reviewer can tell which records describe things that happened.
    assertThat(audited).isEmpty();
  }

  @Test
  void aSimulationMovesNoCounters() {
    registry.install(permissive(1L));

    simulator.simulateCurrent(request().build());

    assertThat(metrics.evaluations()).isZero();
  }

  @Test
  void aSimulationInstallsNothing() {
    registry.install(permissive(1L));

    simulator.simulate(request().build(), restrictive(9L));

    assertThat(registry.currentVersion().sequence()).isEqualTo(1L);
  }

  // ---- simulating the past --------------------------------------------------------------------

  @Test
  void aRetainedGenerationCanBeReplayed() {
    registry.install(permissive(1L));
    registry.install(restrictive(2L));

    final Optional<io.reliabilityai.gateway.dataplane.governance.api.PolicyDecision> past =
        simulator.simulatePast(request().build(), PolicyVersion.of("v1", 1L));

    assertThat(past).isPresent();
    assertThat(past.orElseThrow().verdict()).isEqualTo(Verdict.ALLOW);
    assertThat(simulator.simulateCurrent(request().build()).verdict()).isEqualTo(Verdict.DENY);
  }

  @Test
  void aGenerationNoLongerRetainedCannotBeReplayed() {
    registry.install(permissive(1L));

    assertThat(simulator.simulatePast(request().build(), PolicyVersion.of("v42", 42L))).isEmpty();
  }

  @Test
  void aReplayOfAPastGenerationStampsThatGenerationsVersion() {
    registry.install(permissive(1L));
    registry.install(restrictive(2L));

    assertThat(
            simulator
                .simulatePast(request().build(), PolicyVersion.of("v1", 1L))
                .orElseThrow()
                .policyVersion()
                .sequence())
        .isEqualTo(1L);
  }

  // ---- what-if against a candidate ------------------------------------------------------------

  @Test
  void aCandidateBundleCompilesWithoutBeingInstalled() {
    registry.install(permissive(1L));

    final PolicySnapshot candidate =
        simulator.compileCandidate(
            bundle(
                5L,
                document(
                    GLOBAL,
                    5L,
                    rule(
                        "models",
                        PolicyType.MODEL_ALLOW_LIST,
                        PolicyValue.Values.of("model.other")))));

    assertThat(candidate.version().sequence()).isEqualTo(5L);
    assertThat(registry.currentVersion().sequence()).isEqualTo(1L);
  }

  @Test
  void aCandidateThatWouldNotCompileFailsBeforeAnyResultsAreProduced() {
    assertThatThrownBy(
            () ->
                simulator.compileCandidate(
                    bundle(
                        5L,
                        document(
                            GLOBAL,
                            5L,
                            rule(
                                "weak",
                                PolicyType.PII_RESTRICTION,
                                PolicyValue.Flag.TRUE,
                                EnforcementLevel.SHADOW)))))
        .isInstanceOf(PolicyCompilationException.class);
  }

  @Test
  void whatIfShowsBothVerdictsSideBySide() {
    registry.install(permissive(1L));

    final SimulationResult result = simulator.whatIf(request().build(), restrictive(2L));

    assertThat(result.baseline().verdict()).isEqualTo(Verdict.ALLOW);
    assertThat(result.candidate().verdict()).isEqualTo(Verdict.DENY);
    assertThat(result.changed()).isTrue();
    assertThat(result.newlyDenied()).isTrue();
    assertThat(result.newlyAdmitted()).isFalse();
  }

  @Test
  void whatIfReportsNoChangeWhenTheCandidateDecidesTheSame() {
    registry.install(permissive(1L));

    final SimulationResult result = simulator.whatIf(request().build(), permissive(2L));

    assertThat(result.changed()).isFalse();
    assertThat(result.newlyDenied()).isFalse();
  }

  @Test
  void whatIfDetectsALooseningAsWellAsATightening() {
    registry.install(restrictive(1L));

    final SimulationResult result = simulator.whatIf(request().build(), permissive(2L));

    assertThat(result.newlyAdmitted()).isTrue();
    assertThat(result.newlyDenied()).isFalse();
  }

  @Test
  void aCandidatePromotingAShadowRuleToMandatoryShowsUpAsANewDenial() {
    registry.install(
        snapshot(
            1L,
            document(
                GLOBAL,
                1L,
                rule(
                    "ctx",
                    PolicyType.MAX_CONTEXT,
                    PolicyValue.Limit.of(10),
                    EnforcementLevel.SHADOW))));
    final PolicySnapshot promoted =
        snapshot(
            2L,
            document(GLOBAL, 2L, rule("ctx", PolicyType.MAX_CONTEXT, PolicyValue.Limit.of(10))));

    // This is the whole reason shadow mode exists: measure first, promote second.
    assertThat(simulator.whatIf(request().contextTokens(500).build(), promoted).newlyDenied())
        .isTrue();
  }

  // ---- batch impact ---------------------------------------------------------------------------

  @Test
  void impactCountsHowManyRequestsACandidateWouldStartRefusing() {
    registry.install(permissive(1L));
    final List<PolicyRequest> sample = sample(10);

    final SimulationImpact impact = simulator.impactOf(sample, restrictive(2L));

    assertThat(impact.evaluated()).isEqualTo(10);
    assertThat(impact.newlyDenied()).isEqualTo(10);
    assertThat(impact.unchanged()).isZero();
    assertThat(impact.isSafeRollout()).isFalse();
  }

  @Test
  void impactNamesTheStatementsCausingTheNewRefusals() {
    registry.install(permissive(1L));

    final SimulationImpact impact = simulator.impactOf(sample(3), restrictive(2L));

    assertThat(impact.newlyDeniedRuleIds()).containsExactly("models");
  }

  @Test
  void aHarmlessCandidateIsReportedAsASafeRollout() {
    registry.install(permissive(1L));

    final SimulationImpact impact = simulator.impactOf(sample(5), permissive(2L));

    assertThat(impact.isSafeRollout()).isTrue();
    assertThat(impact.unchanged()).isEqualTo(5);
  }

  @Test
  void aMixedSampleSeparatesTheRequestsThatBreakFromThoseThatDoNot() {
    registry.install(permissive(1L));
    final PolicySnapshot candidate =
        snapshot(
            2L,
            document(
                GLOBAL,
                2L,
                rule("models", PolicyType.MODEL_ALLOW_LIST, PolicyValue.Values.of(MODEL.value())),
                rule("ctx", PolicyType.MAX_CONTEXT, PolicyValue.Limit.of(100))));
    final List<PolicyRequest> mixed =
        List.of(
            request().requestContext(requestContext("small")).contextTokens(10).build(),
            request().requestContext(requestContext("big")).contextTokens(1_000).build());

    final SimulationImpact impact = simulator.impactOf(mixed, candidate);

    assertThat(impact.newlyDenied()).isEqualTo(1);
    assertThat(impact.unchanged()).isEqualTo(1);
  }

  @Test
  void anEmptySampleReportsNothingAndIsTriviallySafe() {
    registry.install(permissive(1L));

    final SimulationImpact impact = simulator.impactOf(List.of(), restrictive(2L));

    assertThat(impact.evaluated()).isZero();
    assertThat(impact.isSafeRollout()).isTrue();
  }

  @Test
  void aWholeBatchIsJudgedAsOfOneInstant() {
    registry.install(permissive(1L));
    final PolicySnapshot windowed =
        snapshot(
            2L,
            document(
                GLOBAL,
                2L,
                rule(
                    "maint",
                    PolicyType.MAINTENANCE_WINDOW,
                    PolicyValue.Windows.of(
                        new PolicyValue.TimeWindow(
                            PolicyFixture.NOW.minusSeconds(1),
                            PolicyFixture.NOW.plusSeconds(1))))));

    // Every request in the batch falls inside the window. Reading the clock per request would let
    // the
    // window close partway through and produce a report judged under two different sets of
    // conditions.
    assertThat(simulator.impactOf(sample(20), windowed).newlyDenied()).isEqualTo(20);
  }

  @Test
  void simulationRunsAgainstAnArbitraryGenerationThatWasNeverInstalled() {
    registry.install(permissive(1L));

    assertThat(simulator.simulate(request().build(), restrictive(99L)).verdict())
        .isEqualTo(Verdict.DENY);
  }

  @Test
  void simulatingOnAnUnconfiguredNodeStillProducesADecisionRatherThanThrowing() {
    // No generation installed: the baseline is the empty snapshot, which constrains nothing.
    assertThat(simulator.simulateCurrent(request().build()).verdict()).isEqualTo(Verdict.ALLOW);
  }

  private static List<PolicyRequest> sample(final int count) {
    final List<PolicyRequest> requests = new ArrayList<>(count);
    for (int i = 0; i < count; i++) {
      requests.add(request().requestContext(requestContext("corr-" + i)).build());
    }
    return requests;
  }
}

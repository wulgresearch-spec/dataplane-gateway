package io.reliabilityai.gateway.dataplane.app.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import io.reliabilityai.gateway.canonical.identity.CausationId;
import io.reliabilityai.gateway.canonical.identity.CorrelationId;
import io.reliabilityai.gateway.canonical.identity.IdempotencyKey;
import io.reliabilityai.gateway.canonical.identity.Region;
import io.reliabilityai.gateway.canonical.identity.RequestId;
import io.reliabilityai.gateway.canonical.io.CanonicalRequest;
import io.reliabilityai.gateway.canonical.io.ForwardedTransportIdentity;
import io.reliabilityai.gateway.dataplane.app.MandatoryStage;
import io.reliabilityai.gateway.dataplane.app.pipeline.PipelineOutcome;
import io.reliabilityai.gateway.dataplane.app.pipeline.RequestExecution;
import io.reliabilityai.gateway.dataplane.governance.api.AuditSinkPort;
import io.reliabilityai.gateway.dataplane.governance.api.DenialReason;
import io.reliabilityai.gateway.dataplane.governance.api.GovernancePolicy;
import io.reliabilityai.gateway.dataplane.governance.api.PolicyAuditEvent;
import io.reliabilityai.gateway.dataplane.governance.api.PolicyMetrics;
import io.reliabilityai.gateway.dataplane.governance.api.PolicyRule;
import io.reliabilityai.gateway.dataplane.governance.api.PolicyScopeRef;
import io.reliabilityai.gateway.dataplane.governance.api.PolicySourcePort;
import io.reliabilityai.gateway.dataplane.governance.api.PolicyType;
import io.reliabilityai.gateway.dataplane.governance.api.PolicyUsage;
import io.reliabilityai.gateway.dataplane.governance.api.PolicyUsagePort;
import io.reliabilityai.gateway.dataplane.governance.api.PolicyValue;
import io.reliabilityai.gateway.dataplane.governance.api.PolicyVersion;
import io.reliabilityai.gateway.dataplane.governance.api.TickerPort;
import io.reliabilityai.gateway.dataplane.governance.api.Verdict;
import io.reliabilityai.gateway.dataplane.governance.application.GovernanceEngine;
import io.reliabilityai.gateway.dataplane.governance.application.GovernanceEngineService;
import io.reliabilityai.gateway.dataplane.schemalock.api.Mode;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Composition tests for the hierarchical Governance Engine: when a node is configured with a policy
 * source, the runtime must build the full engine and hand <em>that</em> to the pipeline, so that
 * hierarchical policy is what actually admits or refuses live traffic.
 *
 * <p>Also pins the property that matters operationally: policy can be replaced and rolled back on a
 * running node, and the very next request feels it.
 */
class PolicyEngineCompositionTest {

  private static final Instant CLOCK_INSTANT = Instant.parse("2026-01-01T00:00:00Z");

  @TempDir Path walDir;
  @TempDir Path dlqDir;

  private final List<PolicyAuditEvent> audited = new ArrayList<>();
  private final AtomicReference<PolicySourcePort.PolicyBundle> published = new AtomicReference<>();

  private GatewayRuntime runtime() {
    return new GatewayRuntime(
        RuntimeFixture.config(
            RuntimeFixture.masterKey(),
            walDir,
            dlqDir.resolve("dlq.log"),
            record -> {},
            new RuntimeFixture.TestClock(),
            RuntimeFixture.authenticatingExternalAdapters(),
            Optional.of(governanceConfig())));
  }

  private GatewayRuntimeConfig.GovernanceConfig governanceConfig() {
    final GatewayRuntimeConfig.GovernanceConfig base =
        RuntimeFixture.governanceConfig(RuntimeFixture.permittingPolicy(), AuditSinkPort.NO_OP);
    return new GatewayRuntimeConfig.GovernanceConfig(
        base.policySnapshots(),
        base.entitlementSnapshots(),
        base.usageStates(),
        base.featureFlags(),
        base.auditSink(),
        base.usageStalenessTolerance(),
        Optional.of(
            new GatewayRuntimeConfig.PolicyEngineConfig(
                () -> Optional.ofNullable(published.get()),
                (PolicyUsagePort) scope -> Optional.of(PolicyUsage.none(CLOCK_INSTANT)),
                audited::add,
                PolicyMetrics.NO_OP,
                TickerPort.FROZEN,
                Duration.ofSeconds(60),
                Duration.ofMinutes(5),
                256,
                4,
                true)));
  }

  /** A generation attached at the organization node, carrying whatever rules a test needs. */
  private static PolicySourcePort.PolicyBundle bundle(
      final long sequence, final PolicyRule... rules) {
    return new PolicySourcePort.PolicyBundle(
        PolicyVersion.of("v" + sequence, sequence),
        List.of(
            GovernancePolicy.of(
                "org-doc",
                PolicyScopeRef.of(
                    io.reliabilityai.gateway.dataplane.governance.api.PolicyScope.ORGANIZATION,
                    RuntimeFixture.TENANT.org()),
                PolicyVersion.of("v" + sequence, sequence),
                List.of(rules))));
  }

  private static RequestExecution.Inbound inbound() {
    return new RequestExecution.Inbound(
        new RequestId("req-1"),
        new io.reliabilityai.gateway.canonical.context.RequestContext(
            new CorrelationId("corr-1"),
            new IdempotencyKey("idem-1"),
            new CausationId("cause-1"),
            "traceparent",
            new Region("us-east-1")),
        new ForwardedTransportIdentity("Bearer", "token", Map.of()),
        new CanonicalRequest(RuntimeFixture.MODEL, List.of(), List.of(), Map.of()),
        Set.of("chat"),
        1024,
        Set.of(),
        1_000_000L,
        Set.of(),
        Instant.parse("2026-01-01T00:00:30Z"),
        true,
        Mode.BATCH,
        Optional.empty(),
        Optional.empty());
  }

  @Test
  void configuringAPolicySourceMakesTheHierarchicalEngineTheNodesEnforcementPoint() {
    published.set(bundle(1L));
    final GatewayRuntime gateway = runtime();
    gateway.start();

    assertThat(gateway.governance()).containsInstanceOf(GovernanceEngine.class);
    assertThat(gateway.policyEngine()).isPresent();
    gateway.stop();
  }

  @Test
  void omittingAPolicySourceLeavesTheNodeOnTheSingleTenantEvaluator() {
    final GatewayRuntime gateway =
        new GatewayRuntime(
            RuntimeFixture.config(
                RuntimeFixture.masterKey(),
                walDir,
                dlqDir.resolve("dlq.log"),
                record -> {},
                new RuntimeFixture.TestClock(),
                RuntimeFixture.authenticatingExternalAdapters(),
                RuntimeFixture.governanceConfigured()));
    gateway.start();

    assertThat(gateway.governance()).containsInstanceOf(GovernanceEngineService.class);
    assertThat(gateway.policyEngine()).isEmpty();
    gateway.stop();
  }

  @Test
  void theGovernanceStageIsBoundEitherWay() {
    published.set(bundle(1L));
    final GatewayRuntime gateway = runtime();
    gateway.start();

    assertThat(gateway.boundStages()).contains(MandatoryStage.GOVERNANCE);
    gateway.stop();
  }

  @Test
  void theNodeInstallsItsFirstGenerationDuringStartup() {
    published.set(bundle(7L));
    final GatewayRuntime gateway = runtime();
    gateway.start();

    assertThat(gateway.policyEngine().orElseThrow().currentVersion().sequence()).isEqualTo(7L);
    gateway.stop();
  }

  @Test
  void aNodeThatStartedWithNoPolicyRefusesEveryRequestRatherThanServingUngoverned() {
    published.set(null);
    final GatewayRuntime gateway = runtime();
    gateway.start();

    final PipelineOutcome outcome = gateway.pipeline().execute(inbound());

    assertThat(outcome).isInstanceOf(PipelineOutcome.Refused.class);
    final PipelineOutcome.Refused refused = (PipelineOutcome.Refused) outcome;
    assertThat(refused.refusal().stage()).isEqualTo(MandatoryStage.GOVERNANCE);
    assertThat(refused.refusal().reason()).isEqualTo(DenialReason.POLICY_NOT_FOUND.code());
    gateway.stop();
  }

  @Test
  void aConformingRequestPassesGovernanceAndReachesTheRouter() {
    published.set(bundle(1L));
    final GatewayRuntime gateway = runtime();
    gateway.start();

    final PipelineOutcome outcome = gateway.pipeline().execute(inbound());

    assertThat(outcome.trace().stages())
        .containsSequence(MandatoryStage.GOVERNANCE, MandatoryStage.ROUTER);
    gateway.stop();
  }

  @Test
  void hierarchicalPolicyRefusesLiveTrafficAtTheGovernanceStage() {
    published.set(
        bundle(
            1L,
            PolicyRule.mandatory("kill", PolicyType.EMERGENCY_KILL_SWITCH, PolicyValue.Flag.TRUE)));
    final GatewayRuntime gateway = runtime();
    gateway.start();

    final PipelineOutcome outcome = gateway.pipeline().execute(inbound());

    final PipelineOutcome.Refused refused = (PipelineOutcome.Refused) outcome;
    assertThat(refused.refusal().stage()).isEqualTo(MandatoryStage.GOVERNANCE);
    assertThat(refused.refusal().reason()).isEqualTo(DenialReason.SECURITY_DENIED.code());
    assertThat(outcome.trace().stages())
        .containsExactly(MandatoryStage.INGRESS, MandatoryStage.AUTHN, MandatoryStage.GOVERNANCE);
    gateway.stop();
  }

  @Test
  void everyGovernedRequestLeavesAnAuditFact() {
    published.set(bundle(1L));
    final GatewayRuntime gateway = runtime();
    gateway.start();

    gateway.pipeline().execute(inbound());

    assertThat(audited).hasSize(1);
    assertThat(audited.get(0).verdict()).isEqualTo(Verdict.ALLOW);
    assertThat(audited.get(0).tenantScope()).isEqualTo(RuntimeFixture.TENANT);
    gateway.stop();
  }

  @Test
  void theAuditFactIsStampedWithTheRuntimeClockNotTheWallClock() {
    published.set(bundle(1L));
    final GatewayRuntime gateway = runtime();
    gateway.start();

    gateway.pipeline().execute(inbound());

    assertThat(audited.get(0).decidedAt()).isEqualTo(CLOCK_INSTANT);
    gateway.stop();
  }

  @Test
  void policyCanBeTightenedOnARunningNodeWithoutARestart() {
    published.set(bundle(1L));
    final GatewayRuntime gateway = runtime();
    gateway.start();
    assertThat(gateway.pipeline().execute(inbound()).trace().stages())
        .contains(MandatoryStage.ROUTER);

    published.set(
        bundle(
            2L,
            PolicyRule.mandatory("suspend", PolicyType.TENANT_SUSPENSION, PolicyValue.Flag.TRUE)));
    assertThat(gateway.policyEngine().orElseThrow().reload()).isTrue();

    // The very next request feels the tightening. Revoking access must not take as long as a
    // deploy.
    final PipelineOutcome after = gateway.pipeline().execute(inbound());
    assertThat(((PipelineOutcome.Refused) after).refusal().reason())
        .isEqualTo(DenialReason.TENANT_DISABLED.code());
    gateway.stop();
  }

  @Test
  void aTighteningCanBeRolledBackOnARunningNode() {
    published.set(bundle(1L));
    final GatewayRuntime gateway = runtime();
    gateway.start();
    published.set(
        bundle(
            2L,
            PolicyRule.mandatory("suspend", PolicyType.TENANT_SUSPENSION, PolicyValue.Flag.TRUE)));
    gateway.policyEngine().orElseThrow().reload();

    assertThat(gateway.policyEngine().orElseThrow().rollbackTo(PolicyVersion.of("v1", 1L)))
        .isTrue();

    assertThat(gateway.pipeline().execute(inbound()).trace().stages())
        .contains(MandatoryStage.ROUTER);
    gateway.stop();
  }

  @Test
  void aReloadOfferingAnOlderGenerationChangesNothing() {
    published.set(bundle(5L));
    final GatewayRuntime gateway = runtime();
    gateway.start();

    published.set(bundle(4L));
    assertThat(gateway.policyEngine().orElseThrow().reload()).isFalse();
    assertThat(gateway.policyEngine().orElseThrow().currentVersion().sequence()).isEqualTo(5L);
    gateway.stop();
  }
}

package io.reliabilityai.gateway.dataplane.app.runtime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.reliabilityai.gateway.canonical.decision.GovernanceDecision;
import io.reliabilityai.gateway.canonical.identity.CausationId;
import io.reliabilityai.gateway.canonical.identity.CorrelationId;
import io.reliabilityai.gateway.canonical.identity.IdempotencyKey;
import io.reliabilityai.gateway.canonical.identity.Region;
import io.reliabilityai.gateway.canonical.identity.RequestId;
import io.reliabilityai.gateway.canonical.io.CanonicalRequest;
import io.reliabilityai.gateway.canonical.io.ForwardedTransportIdentity;
import io.reliabilityai.gateway.dataplane.app.MandatoryStage;
import io.reliabilityai.gateway.dataplane.app.StartupValidationException;
import io.reliabilityai.gateway.dataplane.app.pipeline.PipelineOutcome;
import io.reliabilityai.gateway.dataplane.app.pipeline.RequestExecution;
import io.reliabilityai.gateway.dataplane.governance.api.AuditSinkPort;
import io.reliabilityai.gateway.dataplane.governance.api.Decision;
import io.reliabilityai.gateway.dataplane.governance.api.DenialReason;
import io.reliabilityai.gateway.dataplane.governance.api.GovernanceDecisionRecord;
import io.reliabilityai.gateway.dataplane.governance.api.GovernanceRequest;
import io.reliabilityai.gateway.dataplane.governance.application.GovernanceEngineService;
import io.reliabilityai.gateway.dataplane.governance.domain.PolicySet;
import io.reliabilityai.gateway.dataplane.schemalock.api.Mode;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Composition tests for the GOVERNANCE stage: the runtime must build the real Policy Enforcement
 * Point and hand <em>that</em> to the pipeline. Before this wiring the engine existed but nothing
 * enforced it, which is the failure mode these tests exist to prevent recurring.
 */
class GovernanceCompositionTest {

  @TempDir Path walDir;
  @TempDir Path dlqDir;

  private final List<GovernanceDecisionRecord> audited = new ArrayList<>();

  private GatewayRuntime runtime(final PolicySet policy) {
    return runtime(Optional.of(RuntimeFixture.governanceConfig(policy, audited::add)));
  }

  private GatewayRuntime runtime(final Optional<GatewayRuntimeConfig.GovernanceConfig> governance) {
    return new GatewayRuntime(
        RuntimeFixture.config(
            RuntimeFixture.masterKey(),
            walDir,
            dlqDir.resolve("dlq.log"),
            record -> {},
            new RuntimeFixture.TestClock(),
            RuntimeFixture.authenticatingExternalAdapters(),
            governance));
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
  void runtimeConstructsTheGovernanceEngineItself() {
    final GatewayRuntime gateway = runtime(RuntimeFixture.permittingPolicy());
    gateway.start();

    // Not "some GovernancePort an operator passed in" — the node's own PEP.
    assertThat(gateway.governance()).containsInstanceOf(GovernanceEngineService.class);
    gateway.stop();
  }

  @Test
  void governanceBindsFromTheConstructedEngineNotTheExternalSeam() {
    final GatewayRuntime gateway = runtime(RuntimeFixture.permittingPolicy());
    gateway.start();

    assertThat(gateway.boundStages()).contains(MandatoryStage.GOVERNANCE);
    assertThat(gateway.unboundStages()).isEmpty();
    gateway.stop();
  }

  @Test
  void pipelineConsultsTheConstructedEngineAndPermitsAConformingRequest() {
    final GatewayRuntime gateway = runtime(RuntimeFixture.permittingPolicy());
    gateway.start();

    final PipelineOutcome outcome = gateway.pipeline().execute(inbound());

    // Governance permitted, so the chain proceeded past it. (It stops further down at SECRETS,
    // because no credential snapshot is published on this node — that is a later blocker, not this
    // one.)
    assertThat(outcome.trace().stages())
        .containsSequence(MandatoryStage.GOVERNANCE, MandatoryStage.ROUTER);
    assertThat(outcome.trace().refusalStage()).isNotEqualTo(Optional.of(MandatoryStage.GOVERNANCE));
    gateway.stop();
  }

  @Test
  void deniedRequestIsBlockedAtGovernanceAndNeverReachesTheRouter() {
    final GatewayRuntime gateway = runtime(RuntimeFixture.disabledTenantPolicy());
    gateway.start();

    final PipelineOutcome outcome = gateway.pipeline().execute(inbound());

    assertThat(outcome).isInstanceOf(PipelineOutcome.Refused.class);
    final PipelineOutcome.Refused refused = (PipelineOutcome.Refused) outcome;
    assertThat(refused.refusal().stage()).isEqualTo(MandatoryStage.GOVERNANCE);
    assertThat(refused.refusal().reason()).isEqualTo(DenialReason.TENANT_DISABLED.code());
    assertThat(outcome.trace().stages())
        .containsExactly(MandatoryStage.INGRESS, MandatoryStage.AUTHN, MandatoryStage.GOVERNANCE);
    gateway.stop();
  }

  @Test
  void missingPolicySnapshotFailsClosedAtGovernance() {
    final GatewayRuntime gateway = runtime((PolicySet) null); // no policy served for the tenant
    gateway.start();

    final PipelineOutcome outcome = gateway.pipeline().execute(inbound());

    assertThat(((PipelineOutcome.Refused) outcome).refusal().reason())
        .isEqualTo(DenialReason.POLICY_NOT_FOUND.code());
    gateway.stop();
  }

  @Test
  void approvalRequirementBlocksTheRequestBeforeAnyProviderWork() {
    final GatewayRuntime gateway = runtime(RuntimeFixture.approvalRequiredPolicy());
    gateway.start();

    final GovernanceEngineService engine =
        (GovernanceEngineService) gateway.governance().orElseThrow();
    final Decision decision =
        engine.govern(
            new GovernanceRequest(
                inbound().requestContext(),
                new io.reliabilityai.gateway.canonical.context.PrincipalContext(
                    new io.reliabilityai.gateway.canonical.identity.PrincipalId("principal-a"),
                    Map.of(),
                    "jws",
                    "decision-1"),
                new io.reliabilityai.gateway.canonical.context.TenantContext(RuntimeFixture.TENANT),
                RuntimeFixture.MODEL,
                new Region("us-east-1"),
                Set.of("chat"),
                Set.of(),
                Set.of(),
                Set.of(),
                0L));

    // Approval pending is not admission: the pipeline refuses on any non-permit, so no provider
    // runs.
    assertThat(decision).isInstanceOf(Decision.RequireApproval.class);
    assertThat(decision.permitted()).isFalse();
    gateway.stop();
  }

  @Test
  void auditSinkReceivesADecisionForEveryGovernedRequest() {
    final GatewayRuntime gateway = runtime(RuntimeFixture.permittingPolicy());
    gateway.start();

    gateway.pipeline().execute(inbound());

    assertThat(audited).hasSize(1);
    assertThat(audited.get(0).outcome()).isEqualTo("PERMIT");
    assertThat(audited.get(0).tenantScope()).isEqualTo(RuntimeFixture.TENANT);
    gateway.stop();
  }

  @Test
  void snapshotVersionsPropagateFromTheConfiguredSnapshotsOntoTheAuditFact() {
    final GatewayRuntime gateway = runtime(RuntimeFixture.permittingPolicy());
    gateway.start();

    gateway.pipeline().execute(inbound());

    // Replayability: the audit fact names exactly which snapshots the decision was made against.
    assertThat(audited.get(0).policyVersions())
        .containsEntry("policy", "policy:v1")
        .containsEntry("entitlement", "entitlement:v1");
    gateway.stop();
  }

  @Test
  void governanceUsesTheRuntimeClockNotTheWallClock() {
    final GatewayRuntime gateway = runtime(RuntimeFixture.permittingPolicy());
    gateway.start();

    gateway.pipeline().execute(inbound());

    assertThat(audited.get(0).decidedAt()).isEqualTo(Instant.parse("2026-01-01T00:00:00Z"));
    gateway.stop();
  }

  @Test
  void startupFailsClosedWhenGovernanceConfigIsMissing() {
    final GatewayRuntime gateway = runtime(Optional.empty());

    assertThatThrownBy(gateway::start)
        .isInstanceOf(StartupValidationException.class)
        .hasMessageContaining("GOVERNANCE");
    assertThat(gateway.state()).isEqualTo(GatewayRuntime.State.FAILED);
  }

  @Test
  void canonicalFacadeIsReachableFromTheRuntimeForDirectAdmissionChecks() {
    final GatewayRuntime gateway = runtime(RuntimeFixture.permittingPolicy());
    gateway.start();

    final GovernanceDecision decision =
        gateway
            .governance()
            .orElseThrow()
            .authorize(
                inbound().requestContext(),
                new io.reliabilityai.gateway.canonical.context.PrincipalContext(
                    new io.reliabilityai.gateway.canonical.identity.PrincipalId("principal-a"),
                    Map.of(),
                    "jws",
                    "decision-1"),
                new io.reliabilityai.gateway.canonical.context.TenantContext(
                    RuntimeFixture.TENANT));

    assertThat(decision.permit()).isTrue();
    gateway.stop();
  }

  @Test
  void auditSinkFailureDoesNotBreakTheRequestPath() {
    final GatewayRuntime gateway =
        runtime(
            Optional.of(
                RuntimeFixture.governanceConfig(
                    RuntimeFixture.permittingPolicy(),
                    (AuditSinkPort)
                        record -> {
                          throw new IllegalStateException("audit stream down");
                        })));
    gateway.start();

    // An audit outage must never become an admission outage.
    final PipelineOutcome outcome = gateway.pipeline().execute(inbound());

    assertThat(outcome.trace().stages()).contains(MandatoryStage.ROUTER);
    gateway.stop();
  }
}

package io.reliabilityai.gateway.dataplane.governance;

import static org.assertj.core.api.Assertions.assertThat;

import io.reliabilityai.gateway.canonical.decision.GovernanceDecision;
import io.reliabilityai.gateway.canonical.identity.TenantScope;
import io.reliabilityai.gateway.dataplane.governance.api.AuditSinkPort;
import io.reliabilityai.gateway.dataplane.governance.api.Decision;
import io.reliabilityai.gateway.dataplane.governance.api.DenialReason;
import io.reliabilityai.gateway.dataplane.governance.api.EntitlementSnapshotPort;
import io.reliabilityai.gateway.dataplane.governance.api.FeatureFlagSnapshotPort;
import io.reliabilityai.gateway.dataplane.governance.api.GovernanceDecisionRecord;
import io.reliabilityai.gateway.dataplane.governance.api.PolicySnapshotPort;
import io.reliabilityai.gateway.dataplane.governance.api.UsageStatePort;
import io.reliabilityai.gateway.dataplane.governance.application.GovernanceEngineService;
import io.reliabilityai.gateway.dataplane.governance.domain.Entitlement;
import io.reliabilityai.gateway.dataplane.governance.domain.GovernanceEvaluator;
import io.reliabilityai.gateway.dataplane.governance.domain.PolicySet;
import io.reliabilityai.gateway.dataplane.governance.domain.UsageState;
import io.reliabilityai.gateway.ports.GovernancePort;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * Tests for the PEP wrapper: snapshot resolution, fail-closed behaviour on resolution failure,
 * audit emission, and the canonical {@link GovernancePort} façade the pipeline calls.
 */
class GovernanceEngineServiceTest {

  private final List<GovernanceDecisionRecord> audited = new ArrayList<>();

  private Optional<PolicySet> policy = Optional.of(GovernanceFixture.policy());
  private Optional<Entitlement> entitlement = Optional.of(GovernanceFixture.entitlement());
  private Optional<UsageState> usage = Optional.of(GovernanceFixture.usage());
  private boolean policyReaderThrows;

  private GovernanceEngineService service() {
    final PolicySnapshotPort policyPort =
        tenantScope -> {
          if (policyReaderThrows) {
            throw new IllegalStateException("snapshot store unavailable");
          }
          return policy;
        };
    final EntitlementSnapshotPort entitlementPort = tenantScope -> entitlement;
    final UsageStatePort usagePort = tenantScope -> usage;
    final FeatureFlagSnapshotPort flagPort = (tenantScope, feature) -> Optional.of(Boolean.TRUE);
    final AuditSinkPort auditPort = audited::add;

    return new GovernanceEngineService(
        policyPort,
        entitlementPort,
        usagePort,
        flagPort,
        auditPort,
        new GovernanceEvaluator(Duration.ofSeconds(60)),
        GovernanceFixture.CLOCK);
  }

  @Test
  void permitsAConformingRequest() {
    final Decision decision = service().govern(GovernanceFixture.request());

    assertThat(decision.permitted()).isTrue();
  }

  @Test
  void emitsAnAuditFactForPermitsNotJustDenials() {
    service().govern(GovernanceFixture.request());

    // Auditing only denials would leave no evidence the gate ran at all.
    assertThat(audited).hasSize(1);
    final GovernanceDecisionRecord record = audited.get(0);
    assertThat(record.outcome()).isEqualTo("PERMIT");
    assertThat(record.binding()).isEqualTo("none");
    assertThat(record.tenantScope()).isEqualTo(GovernanceFixture.TENANT);
    assertThat(record.decidedAt())
        .isEqualTo(GovernanceFixture.NOW); // injected clock, never wall clock
    assertThat(record.policyVersions()).containsEntry("policy", "policy:v1");
  }

  @Test
  void emitsAnAuditFactNamingTheBindingDomainOnDenial() {
    policy = Optional.empty();

    service().govern(GovernanceFixture.request());

    assertThat(audited).hasSize(1);
    assertThat(audited.get(0).outcome()).isEqualTo("policy-not-found");
    assertThat(audited.get(0).binding()).isEqualTo("POLICY_NOT_FOUND");
  }

  @Test
  void failsClosedWhenASnapshotReaderThrows() {
    policyReaderThrows = true;

    final Decision decision = service().govern(GovernanceFixture.request());

    // A reader that throws tells us nothing about whether the request is allowed.
    assertThat(decision.permitted()).isFalse();
    assertThat(((Decision.Deny) decision).reason()).isEqualTo(DenialReason.POLICY_UNAVAILABLE);
  }

  @Test
  void anAuditSinkFailureNeverChangesTheDecision() {
    final GovernanceEngineService withBrokenAudit =
        new GovernanceEngineService(
            tenantScope -> policy,
            tenantScope -> entitlement,
            tenantScope -> usage,
            (tenantScope, feature) -> Optional.of(Boolean.TRUE),
            record -> {
              throw new IllegalStateException("audit stream down");
            },
            new GovernanceEvaluator(Duration.ofSeconds(60)),
            GovernanceFixture.CLOCK);

    // An audit outage must not become an admission outage.
    assertThat(withBrokenAudit.govern(GovernanceFixture.request()).permitted()).isTrue();
  }

  @Test
  void canonicalFacadePermitsAndCarriesResidencyScopeAsObligations() {
    final GovernanceDecision decision =
        service()
            .authorize(
                GovernanceFixture.requestContext(),
                GovernanceFixture.principal(),
                GovernanceFixture.tenant());

    assertThat(decision.permit()).isTrue();
    assertThat(decision.reason()).isEqualTo("permitted");
    assertThat(decision.obligations()).containsExactly("us-east-1");
  }

  @Test
  void canonicalFacadeDeniesAnUnknownTenantFailingClosed() {
    policy = Optional.empty();

    final GovernanceDecision decision =
        service()
            .authorize(
                GovernanceFixture.requestContext(),
                GovernanceFixture.principal(),
                GovernanceFixture.tenant());

    assertThat(decision.permit()).isFalse();
    assertThat(decision.reason()).isEqualTo("policy-not-found");
  }

  @Test
  void canonicalFacadeDeniesADisabledTenant() {
    final PolicySet base = GovernanceFixture.policy();
    policy =
        Optional.of(
            new PolicySet(
                base.version(),
                false,
                base.allowedModels(),
                base.allowedRegions(),
                base.allowedCapabilities(),
                base.allowedTools(),
                base.complianceRegimes(),
                base.approvalRequiredCapabilities(),
                base.gatedFeatures(),
                base.maxRequestsPerWindow()));

    final GovernanceDecision decision =
        service()
            .authorize(
                GovernanceFixture.requestContext(),
                GovernanceFixture.principal(),
                GovernanceFixture.tenant());

    assertThat(decision.permit()).isFalse();
    assertThat(decision.reason()).isEqualTo("tenant-disabled");
  }

  @Test
  void canonicalFacadeEnforcesBudgetOnTheAdmissionPath() {
    usage = Optional.of(new UsageState(10L, 1_000_001L, GovernanceFixture.NOW));

    final GovernanceDecision decision =
        service()
            .authorize(
                GovernanceFixture.requestContext(),
                GovernanceFixture.principal(),
                GovernanceFixture.tenant());

    assertThat(decision.permit()).isFalse();
    assertThat(decision.reason()).isEqualTo("budget-exceeded");
  }

  @Test
  void theEngineSatisfiesTheFrozenCanonicalPortSoThePipelineCanConsumeIt() {
    // Integration contract: this is exactly how the composition root binds the GOVERNANCE stage.
    final GovernancePort port = service();

    assertThat(
            port.authorize(
                    GovernanceFixture.requestContext(),
                    GovernanceFixture.principal(),
                    GovernanceFixture.tenant())
                .permit())
        .isTrue();
  }

  @Test
  void decisionsAreDeterministicAcrossRepeatedCalls() {
    final GovernanceEngineService engine = service();
    final Decision first = engine.govern(GovernanceFixture.request());

    for (int run = 0; run < 25; run++) {
      assertThat(engine.govern(GovernanceFixture.request())).isEqualTo(first);
    }
  }

  @Test
  void tenantScopeIsCarriedIntoEverySnapshotLookupUnchanged() {
    final List<TenantScope> observed = new ArrayList<>();
    final GovernanceEngineService engine =
        new GovernanceEngineService(
            tenantScope -> {
              observed.add(tenantScope);
              return policy;
            },
            tenantScope -> {
              observed.add(tenantScope);
              return entitlement;
            },
            tenantScope -> {
              observed.add(tenantScope);
              return usage;
            },
            (tenantScope, feature) -> Optional.of(Boolean.TRUE),
            AuditSinkPort.NO_OP,
            new GovernanceEvaluator(Duration.ofSeconds(60)),
            GovernanceFixture.CLOCK);

    engine.govern(GovernanceFixture.request());

    // Tenant isolation (AD-021): every lookup is scoped to the requesting tenant, never widened.
    assertThat(observed).isNotEmpty().allMatch(GovernanceFixture.TENANT::equals);
  }
}

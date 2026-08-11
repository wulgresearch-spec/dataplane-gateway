package io.reliabilityai.gateway.dataplane.governance;

import io.reliabilityai.gateway.canonical.context.PrincipalContext;
import io.reliabilityai.gateway.canonical.context.RequestContext;
import io.reliabilityai.gateway.canonical.context.TenantContext;
import io.reliabilityai.gateway.canonical.identity.CanonicalModelId;
import io.reliabilityai.gateway.canonical.identity.CausationId;
import io.reliabilityai.gateway.canonical.identity.CorrelationId;
import io.reliabilityai.gateway.canonical.identity.IdempotencyKey;
import io.reliabilityai.gateway.canonical.identity.PrincipalId;
import io.reliabilityai.gateway.canonical.identity.Region;
import io.reliabilityai.gateway.canonical.identity.SnapshotVersion;
import io.reliabilityai.gateway.canonical.identity.TenantScope;
import io.reliabilityai.gateway.dataplane.governance.api.GovernanceRequest;
import io.reliabilityai.gateway.dataplane.governance.domain.Entitlement;
import io.reliabilityai.gateway.dataplane.governance.domain.PolicySet;
import io.reliabilityai.gateway.dataplane.governance.domain.UsageState;
import io.reliabilityai.gateway.ports.ClockPort;
import java.time.Instant;
import java.util.Map;
import java.util.Set;

/** Deterministic inputs for governance tests, so each test states only the one thing it varies. */
final class GovernanceFixture {

  static final Instant NOW = Instant.parse("2026-01-01T00:00:00Z");
  static final TenantScope TENANT = TenantScope.of("org-a", "tenant-a");
  static final CanonicalModelId MODEL = new CanonicalModelId("model.test.v1");
  static final Region REGION = new Region("us-east-1");
  static final ClockPort CLOCK = () -> NOW;

  private GovernanceFixture() {}

  static RequestContext requestContext() {
    return new RequestContext(
        new CorrelationId("corr-1"),
        new IdempotencyKey("idem-1"),
        new CausationId("cause-1"),
        "traceparent",
        REGION);
  }

  static PrincipalContext principal() {
    return new PrincipalContext(new PrincipalId("principal-a"), Map.of(), "jws", "decision-1");
  }

  static TenantContext tenant() {
    return new TenantContext(TENANT);
  }

  static GovernanceRequest request() {
    return new GovernanceRequest(
        requestContext(),
        principal(),
        tenant(),
        MODEL,
        REGION,
        Set.of("chat"),
        Set.of(),
        Set.of("SOC2"),
        Set.of(),
        1_000L);
  }

  static PolicySet policy() {
    return new PolicySet(
        new SnapshotVersion("policy", "v1"),
        true,
        Set.of(MODEL),
        Set.of("us-east-1"),
        Set.of("chat", "vision"),
        Set.of("search"),
        Set.of("SOC2", "GDPR"),
        Set.of(),
        Set.of("beta-feature"),
        1_000L);
  }

  static Entitlement entitlement() {
    return new Entitlement(new SnapshotVersion("entitlement", "v1"), 500L, 1_000_000L);
  }

  static UsageState usage() {
    return new UsageState(10L, 5_000L, NOW);
  }
}

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
import io.reliabilityai.gateway.canonical.identity.TenantScope;
import io.reliabilityai.gateway.dataplane.governance.api.EnforcementLevel;
import io.reliabilityai.gateway.dataplane.governance.api.GovernancePolicy;
import io.reliabilityai.gateway.dataplane.governance.api.PolicyRequest;
import io.reliabilityai.gateway.dataplane.governance.api.PolicyRule;
import io.reliabilityai.gateway.dataplane.governance.api.PolicyScope;
import io.reliabilityai.gateway.dataplane.governance.api.PolicyScopeRef;
import io.reliabilityai.gateway.dataplane.governance.api.PolicySourcePort;
import io.reliabilityai.gateway.dataplane.governance.api.PolicyType;
import io.reliabilityai.gateway.dataplane.governance.api.PolicyUsage;
import io.reliabilityai.gateway.dataplane.governance.api.PolicyValue;
import io.reliabilityai.gateway.dataplane.governance.api.PolicyVersion;
import io.reliabilityai.gateway.dataplane.governance.api.ScopeChain;
import io.reliabilityai.gateway.dataplane.governance.domain.PolicySnapshot;
import io.reliabilityai.gateway.dataplane.governance.domain.ResolvedRule;
import io.reliabilityai.gateway.dataplane.governance.internal.PolicyCompiler;
import io.reliabilityai.gateway.ports.ClockPort;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** Deterministic inputs, so each test states only the one thing it varies. */
final class PolicyFixture {

  static final Instant NOW = Instant.parse("2026-03-01T12:00:00Z");
  static final ClockPort CLOCK = () -> NOW;
  static final CanonicalModelId MODEL = new CanonicalModelId("model.chat.v1");
  static final Region REGION = new Region("eu-west-1");
  static final String ORG = "org-a";
  static final String WORKSPACE = "ws-a";
  static final String PROJECT = "proj-a";
  static final TenantScope TENANT = new TenantScope(ORG, "tenant-a", WORKSPACE, PROJECT);

  static final PolicyScopeRef GLOBAL = PolicyScopeRef.global();
  static final PolicyScopeRef ORG_REF = PolicyScopeRef.of(PolicyScope.ORGANIZATION, ORG);
  static final PolicyScopeRef WORKSPACE_REF = PolicyScopeRef.of(PolicyScope.WORKSPACE, WORKSPACE);
  static final PolicyScopeRef PROJECT_REF = PolicyScopeRef.of(PolicyScope.PROJECT, PROJECT);

  private PolicyFixture() {}

  static RequestContext requestContext() {
    return requestContext("corr-1");
  }

  static RequestContext requestContext(final String correlation) {
    return new RequestContext(
        new CorrelationId(correlation),
        new IdempotencyKey("idem-" + correlation),
        new CausationId("cause-" + correlation),
        "traceparent",
        REGION);
  }

  static PrincipalContext principal() {
    return new PrincipalContext(
        new PrincipalId("principal-a"), java.util.Map.of(), "jws", "auth-1");
  }

  static TenantContext tenant() {
    return new TenantContext(TENANT);
  }

  static ScopeChain chain() {
    return ScopeChain.forTenant(TENANT);
  }

  /** A plain chat request that every default policy in this fixture admits. */
  static PolicyRequest.Builder request() {
    return PolicyRequest.builder()
        .requestContext(requestContext())
        .principal(principal())
        .tenant(tenant())
        .scopeChain(chain())
        .model(MODEL)
        .region(REGION);
  }

  static PolicyUsage usage() {
    return PolicyUsage.none(NOW);
  }

  static PolicyUsage usage(final long requests, final long spentToday) {
    return new PolicyUsage(requests, requests, 0L, 0L, spentToday, spentToday, NOW);
  }

  static PolicyRule rule(final String id, final PolicyType type, final PolicyValue value) {
    return PolicyRule.mandatory(id, type, value);
  }

  static PolicyRule rule(
      final String id,
      final PolicyType type,
      final PolicyValue value,
      final EnforcementLevel level) {
    return new PolicyRule(id, type, value, level);
  }

  static GovernancePolicy document(
      final PolicyScopeRef scope, final long sequence, final PolicyRule... rules) {
    return GovernancePolicy.of(
        "doc-" + scope.scope() + "-" + sequence,
        scope,
        PolicyVersion.of("v" + sequence, sequence),
        Arrays.asList(rules));
  }

  static PolicySourcePort.PolicyBundle bundle(final long sequence, final GovernancePolicy... docs) {
    return new PolicySourcePort.PolicyBundle(
        PolicyVersion.of("v" + sequence, sequence), Arrays.asList(docs));
  }

  static PolicySnapshot snapshot(final long sequence, final GovernancePolicy... docs) {
    return new PolicyCompiler().compile(bundle(sequence, docs));
  }

  /** A snapshot whose global document permits the fixture model and region. */
  static PolicySnapshot permissiveSnapshot() {
    return snapshot(
        1L,
        document(
            GLOBAL,
            1L,
            rule("g-model", PolicyType.MODEL_ALLOW_LIST, PolicyValue.Values.of(MODEL.value())),
            rule(
                "g-region", PolicyType.REGION_RESTRICTION, PolicyValue.Values.of(REGION.value()))));
  }

  static ResolvedRule resolved(
      final PolicyScopeRef scope,
      final String id,
      final PolicyType type,
      final PolicyValue value,
      final EnforcementLevel level) {
    return new ResolvedRule(new PolicyRule(id, type, value, level), scope);
  }

  static ResolvedRule resolved(
      final PolicyScopeRef scope, final String id, final PolicyType type, final PolicyValue value) {
    return resolved(scope, id, type, value, EnforcementLevel.MANDATORY);
  }

  static List<String> ruleIds(final List<? extends Object> violations) {
    return new ArrayList<>(violations.stream().map(Object::toString).toList());
  }
}

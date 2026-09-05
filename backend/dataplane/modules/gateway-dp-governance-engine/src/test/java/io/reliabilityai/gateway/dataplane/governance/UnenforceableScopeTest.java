package io.reliabilityai.gateway.dataplane.governance;

import static io.reliabilityai.gateway.dataplane.governance.PolicyFixture.GLOBAL;
import static io.reliabilityai.gateway.dataplane.governance.PolicyFixture.ORG_REF;
import static io.reliabilityai.gateway.dataplane.governance.PolicyFixture.bundle;
import static io.reliabilityai.gateway.dataplane.governance.PolicyFixture.document;
import static io.reliabilityai.gateway.dataplane.governance.PolicyFixture.rule;
import static io.reliabilityai.gateway.dataplane.governance.PolicyFixture.snapshot;
import static org.assertj.core.api.Assertions.assertThat;

import io.reliabilityai.gateway.dataplane.governance.api.EnforcementLevel;
import io.reliabilityai.gateway.dataplane.governance.api.GovernancePolicy;
import io.reliabilityai.gateway.dataplane.governance.api.PolicyMetrics;
import io.reliabilityai.gateway.dataplane.governance.api.PolicyRule;
import io.reliabilityai.gateway.dataplane.governance.api.PolicyScope;
import io.reliabilityai.gateway.dataplane.governance.api.PolicyScopeRef;
import io.reliabilityai.gateway.dataplane.governance.api.PolicySourcePort;
import io.reliabilityai.gateway.dataplane.governance.api.PolicyType;
import io.reliabilityai.gateway.dataplane.governance.api.PolicyValue;
import io.reliabilityai.gateway.dataplane.governance.api.PolicyVersion;
import io.reliabilityai.gateway.dataplane.governance.internal.InProcessPolicyMetrics;
import io.reliabilityai.gateway.dataplane.governance.internal.PolicyCompiler;
import io.reliabilityai.gateway.dataplane.governance.internal.PolicyLoader;
import io.reliabilityai.gateway.dataplane.governance.internal.PolicyRegistry;
import io.reliabilityai.gateway.dataplane.governance.internal.PolicyStore;
import java.lang.reflect.Method;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/**
 * A document attached to a scope this build never constructs is accepted, and now says so.
 *
 * <p>{@code PolicyScope.REQUEST} is declared by the policy model and expressible by {@code
 * ScopeChain.Builder}, but no shipped code path ever puts that node into a chain. A document
 * authored against it therefore compiles, installs, and is stored — and then {@code
 * PolicySnapshot.effectiveFor} never visits it, because the fold walks only the refs a chain
 * actually contains. The document is silently inert.
 *
 * <p>Silence is the defect, not acceptance. An operator who authors a restriction and receives no
 * error concludes it is being enforced. These tests hold the two halves of that fix together: the
 * document must keep being accepted exactly as before, because refusing it would break a deployment
 * that already publishes one, and the acceptance must now be countable.
 *
 * <p>What is deliberately <b>not</b> asserted here: that any other scope is reachable. This change
 * makes one known-unreachable scope visible; it does not survey the rest, and nothing here should
 * be read as evidence that {@code ENVIRONMENT}, {@code WORKSPACE} or {@code PROJECT} are
 * constructed. The four scopes named in {@link
 * #theScopesTheShippedResolverBuildsAreNeverReported()} are the ones separately proven to bind a
 * request end to end.
 */
class UnenforceableScopeTest {

  private static final PolicyScopeRef REQUEST_REF =
      PolicyScopeRef.of(PolicyScope.REQUEST, "req-self-restriction");

  private static PolicyRegistry registry(final InProcessPolicyMetrics metrics) {
    return new PolicyRegistry(new PolicyStore(64, 3), metrics);
  }

  private static PolicyLoader loader(
      final PolicySourcePort source,
      final PolicyRegistry registry,
      final InProcessPolicyMetrics metrics) {
    return new PolicyLoader(source, new PolicyCompiler(), registry, metrics);
  }

  private static PolicyRule limit(final String id, final long value) {
    return rule(id, PolicyType.MAX_COST, PolicyValue.Limit.of(value));
  }

  // ---- the document is still accepted ---------------------------------------------------------

  @Test
  void aRequestScopedDocumentIsStillAcceptedAndPutInForce() {
    final InProcessPolicyMetrics metrics = new InProcessPolicyMetrics();
    final PolicyRegistry registry = registry(metrics);
    final PolicySourcePort.PolicyBundle published =
        bundle(
            1L, document(GLOBAL, 1L, limit("g", 100L)), document(REQUEST_REF, 1L, limit("r", 1L)));

    // The whole point of observing rather than refusing: acceptance is unchanged.
    assertThat(loader(() -> Optional.of(published), registry, metrics).reload()).isTrue();
    assertThat(registry.currentVersion().sequence()).isEqualTo(1L);
    assertThat(metrics.rejections()).isZero();
    assertThat(metrics.installs()).isEqualTo(1L);
  }

  @Test
  void theRequestScopedDocumentIsStoredRatherThanDropped() {
    // Counting it must not have turned into quietly discarding it: the snapshot still carries the
    // document, so a later build that constructs the node would fold exactly what was published.
    final PolicySourcePort.PolicyBundle published =
        bundle(
            1L, document(GLOBAL, 1L, limit("g", 100L)), document(REQUEST_REF, 1L, limit("r", 1L)));

    assertThat(new PolicyCompiler().compile(published).size()).isEqualTo(2);
  }

  // ---- and now it is countable ----------------------------------------------------------------

  @Test
  void aRequestScopedDocumentInForceIsCounted() {
    final InProcessPolicyMetrics metrics = new InProcessPolicyMetrics();
    final PolicyRegistry registry = registry(metrics);
    final PolicySourcePort.PolicyBundle published =
        bundle(
            1L, document(GLOBAL, 1L, limit("g", 100L)), document(REQUEST_REF, 1L, limit("r", 1L)));

    loader(() -> Optional.of(published), registry, metrics).reload();

    assertThat(metrics.unenforceableScopes(PolicyScope.REQUEST)).isEqualTo(1L);
  }

  @Test
  void aGenerationCarryingSeveralSuchDocumentsIsCountedOncePerScope() {
    // Per generation, not per document. A bundle listing fifty request-scoped documents is one
    // configuration mistake, and fifty increments would read as fifty separate events.
    final InProcessPolicyMetrics metrics = new InProcessPolicyMetrics();
    final PolicyRegistry registry = registry(metrics);
    final PolicySourcePort.PolicyBundle published =
        bundle(
            1L,
            document(GLOBAL, 1L, limit("g", 100L)),
            document(PolicyScopeRef.of(PolicyScope.REQUEST, "a"), 1L, limit("r1", 1L)),
            document(PolicyScopeRef.of(PolicyScope.REQUEST, "b"), 2L, limit("r2", 2L)),
            document(PolicyScopeRef.of(PolicyScope.REQUEST, "c"), 3L, limit("r3", 3L)));

    loader(() -> Optional.of(published), registry, metrics).reload();

    assertThat(metrics.unenforceableScopes(PolicyScope.REQUEST)).isEqualTo(1L);
  }

  @Test
  void eachNewGenerationThatStillCarriesOneIsCountedAgain() {
    // The condition has not gone away just because it was reported once; an operator watching the
    // rate needs it to keep reporting while the misconfiguration is still being published.
    final InProcessPolicyMetrics metrics = new InProcessPolicyMetrics();
    final PolicyRegistry registry = registry(metrics);
    final AtomicReference<PolicySourcePort.PolicyBundle> published =
        new AtomicReference<>(bundle(1L, document(REQUEST_REF, 1L, limit("r", 1L))));
    final PolicyLoader loader = loader(() -> Optional.of(published.get()), registry, metrics);

    assertThat(loader.reload()).isTrue();
    published.set(bundle(2L, document(REQUEST_REF, 2L, limit("r", 1L))));
    assertThat(loader.reload()).isTrue();

    assertThat(metrics.unenforceableScopes(PolicyScope.REQUEST)).isEqualTo(2L);
  }

  // ---- nothing that was not put in force is ever reported -------------------------------------

  @Test
  void aBundleThatWillNotCompileReportsNothing() {
    // Not accepted, so not accepted-but-unenforceable. Reporting here would tell an operator the
    // document is in force when the generation was refused outright.
    final InProcessPolicyMetrics metrics = new InProcessPolicyMetrics();
    final PolicyRegistry registry = registry(metrics);
    final PolicySourcePort.PolicyBundle bad =
        bundle(
            9L,
            document(REQUEST_REF, 9L, limit("r", 1L)),
            document(
                GLOBAL,
                9L,
                rule(
                    "weak",
                    PolicyType.PII_RESTRICTION,
                    PolicyValue.Flag.TRUE,
                    EnforcementLevel.SHADOW)));

    assertThat(loader(() -> Optional.of(bad), registry, metrics).reload()).isFalse();
    assertThat(metrics.unenforceableScopes(PolicyScope.REQUEST)).isZero();
  }

  @Test
  void aGenerationTheRegistryDeclinesAsStaleReportsNothing() {
    final InProcessPolicyMetrics metrics = new InProcessPolicyMetrics();
    final PolicyRegistry registry = registry(metrics);
    registry.install(snapshot(5L, document(GLOBAL, 5L, limit("g", 100L))));
    final PolicySourcePort.PolicyBundle stale =
        bundle(2L, document(REQUEST_REF, 2L, limit("r", 1L)));

    assertThat(loader(() -> Optional.of(stale), registry, metrics).reload()).isFalse();
    assertThat(metrics.unenforceableScopes(PolicyScope.REQUEST)).isZero();
  }

  @Test
  void republishingTheSameGenerationDoesNotKeepCounting() {
    // A refresh loop polling an unchanged bundle must not turn one misconfiguration into a rate.
    final InProcessPolicyMetrics metrics = new InProcessPolicyMetrics();
    final PolicyRegistry registry = registry(metrics);
    final PolicySourcePort.PolicyBundle published =
        bundle(1L, document(REQUEST_REF, 1L, limit("r", 1L)));
    final PolicyLoader loader = loader(() -> Optional.of(published), registry, metrics);

    loader.reload();
    loader.reload();
    loader.reload();

    assertThat(metrics.unenforceableScopes(PolicyScope.REQUEST)).isEqualTo(1L);
  }

  @Test
  void aSourceThatCannotAnswerReportsNothing() {
    final InProcessPolicyMetrics metrics = new InProcessPolicyMetrics();
    final PolicyRegistry registry = registry(metrics);

    assertThat(loader(PolicySourcePort.EMPTY, registry, metrics).reload()).isFalse();
    assertThat(metrics.unenforceableScopes(PolicyScope.REQUEST)).isZero();
  }

  @Test
  void aDisabledRequestScopedDocumentIsNotReported() {
    // A disabled document is skipped by the compiler too. It is not in force and its author has
    // already said so; reporting it would be a false positive on a deliberate act.
    final InProcessPolicyMetrics metrics = new InProcessPolicyMetrics();
    final PolicyRegistry registry = registry(metrics);
    final GovernancePolicy disabled =
        new GovernancePolicy(
            "doc-disabled",
            REQUEST_REF,
            PolicyVersion.of("v1", 1L),
            List.of(limit("r", 1L)),
            false);
    final PolicySourcePort.PolicyBundle published =
        new PolicySourcePort.PolicyBundle(
            PolicyVersion.of("v1", 1L), List.of(document(GLOBAL, 1L, limit("g", 100L)), disabled));

    assertThat(loader(() -> Optional.of(published), registry, metrics).reload()).isTrue();
    assertThat(metrics.unenforceableScopes(PolicyScope.REQUEST)).isZero();
  }

  // ---- no false positives ---------------------------------------------------------------------

  @Test
  void theScopesTheShippedResolverBuildsAreNeverReported() {
    // ORGANIZATION, API_KEY, USER and SERVICE_ACCOUNT are the nodes ClaimBasedScopeResolver
    // actually constructs and that are separately proven to bind a request end to end. If this
    // counter ever fired for one of them it would be telling an operator that a working
    // restriction is inert, which is the worse direction of the two.
    final InProcessPolicyMetrics metrics = new InProcessPolicyMetrics();
    final PolicyRegistry registry = registry(metrics);
    final PolicySourcePort.PolicyBundle published =
        bundle(
            1L,
            document(GLOBAL, 1L, limit("g", 100L)),
            document(ORG_REF, 1L, limit("o", 90L)),
            document(PolicyScopeRef.of(PolicyScope.API_KEY, "K1"), 1L, limit("k", 80L)),
            document(PolicyScopeRef.of(PolicyScope.USER, "u1"), 1L, limit("u", 70L)),
            document(PolicyScopeRef.of(PolicyScope.SERVICE_ACCOUNT, "s1"), 1L, limit("s", 60L)));

    assertThat(loader(() -> Optional.of(published), registry, metrics).reload()).isTrue();

    assertThat(metrics.unenforceableScopes(PolicyScope.ORGANIZATION)).isZero();
    assertThat(metrics.unenforceableScopes(PolicyScope.API_KEY)).isZero();
    assertThat(metrics.unenforceableScopes(PolicyScope.USER)).isZero();
    assertThat(metrics.unenforceableScopes(PolicyScope.SERVICE_ACCOUNT)).isZero();
    assertThat(metrics.unenforceableScopes(PolicyScope.GLOBAL)).isZero();
  }

  @ParameterizedTest
  @EnumSource(PolicyScope.class)
  void onlyTheRequestScopeIsReportedByThisBuild(final PolicyScope scope) {
    // States the reporting set exactly, so widening or narrowing it is a deliberate edit with a
    // failing test attached rather than a silent change in what operators are told.
    final InProcessPolicyMetrics metrics = new InProcessPolicyMetrics();
    final PolicyRegistry registry = registry(metrics);
    // One document only: the compiler refuses two at the same node, and the GLOBAL case would
    // otherwise collide with an anchor document rather than exercise the counter.
    final PolicyScopeRef ref =
        scope == PolicyScope.GLOBAL ? GLOBAL : PolicyScopeRef.of(scope, "id-1");
    final PolicySourcePort.PolicyBundle published = bundle(1L, document(ref, 1L, limit("x", 10L)));

    assertThat(loader(() -> Optional.of(published), registry, metrics).reload()).isTrue();

    assertThat(metrics.unenforceableScopes(scope))
        .isEqualTo(scope == PolicyScope.REQUEST ? 1L : 0L);
  }

  // ---- the diagnostic carries nothing an operator authored ------------------------------------

  @Test
  void theDiagnosticCanOnlyEverPublishTheScopeItself() throws NoSuchMethodException {
    // Bounded cardinality is a compile-time property here, not a convention someone must remember:
    // the port accepts one closed-enum argument, so a document id, tenant, principal or claim
    // cannot reach a metrics backend through this counter even by accident.
    final Method counter = PolicyMetrics.class.getMethod("unenforceableScope", PolicyScope.class);

    assertThat(counter.getParameterTypes()).containsExactly(PolicyScope.class);
    assertThat(PolicyScope.class.isEnum()).isTrue();
    assertThat(counter.getReturnType()).isEqualTo(void.class);
  }

  @Test
  void anOperatorAuthoredIdentifierIsNotNeededToRecordTheEvent() {
    // The scope id is operator free text. Authoring one that looks like a secret changes nothing
    // about what is recorded: the count is keyed by the enum and by nothing else.
    final InProcessPolicyMetrics metrics = new InProcessPolicyMetrics();
    final PolicyRegistry registry = registry(metrics);
    final PolicySourcePort.PolicyBundle published =
        bundle(
            1L,
            document(
                PolicyScopeRef.of(PolicyScope.REQUEST, "sk-live-do-not-log-me"),
                1L,
                limit("r", 1L)));

    assertThat(loader(() -> Optional.of(published), registry, metrics).reload()).isTrue();
    assertThat(metrics.unenforceableScopes(PolicyScope.REQUEST)).isEqualTo(1L);
  }

  @Test
  void aDeploymentWithNoMetricsBackendStillReloadsCleanly() {
    // PolicyMetrics.NO_OP takes the default, which does nothing. The reporting pass must not be
    // able to break a reload on a node that publishes no metrics at all.
    final PolicyRegistry registry = new PolicyRegistry(new PolicyStore(64, 3), PolicyMetrics.NO_OP);
    final PolicySourcePort.PolicyBundle published =
        bundle(1L, document(REQUEST_REF, 1L, limit("r", 1L)));

    assertThat(
            new PolicyLoader(
                    () -> Optional.of(published),
                    new PolicyCompiler(),
                    registry,
                    PolicyMetrics.NO_OP)
                .reload())
        .isTrue();
    assertThat(registry.currentVersion().sequence()).isEqualTo(1L);
  }
}

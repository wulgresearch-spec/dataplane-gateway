package io.reliabilityai.gateway.dataplane.governance;

import static io.reliabilityai.gateway.dataplane.governance.PolicyFixture.GLOBAL;
import static io.reliabilityai.gateway.dataplane.governance.PolicyFixture.ORG_REF;
import static io.reliabilityai.gateway.dataplane.governance.PolicyFixture.PROJECT_REF;
import static io.reliabilityai.gateway.dataplane.governance.PolicyFixture.bundle;
import static io.reliabilityai.gateway.dataplane.governance.PolicyFixture.chain;
import static io.reliabilityai.gateway.dataplane.governance.PolicyFixture.document;
import static io.reliabilityai.gateway.dataplane.governance.PolicyFixture.rule;
import static io.reliabilityai.gateway.dataplane.governance.PolicyFixture.snapshot;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.reliabilityai.gateway.dataplane.governance.api.EnforcementLevel;
import io.reliabilityai.gateway.dataplane.governance.api.GovernancePolicy;
import io.reliabilityai.gateway.dataplane.governance.api.PolicyCompilationException;
import io.reliabilityai.gateway.dataplane.governance.api.PolicyRule;
import io.reliabilityai.gateway.dataplane.governance.api.PolicySourcePort;
import io.reliabilityai.gateway.dataplane.governance.api.PolicyType;
import io.reliabilityai.gateway.dataplane.governance.api.PolicyValue;
import io.reliabilityai.gateway.dataplane.governance.api.PolicyVersion;
import io.reliabilityai.gateway.dataplane.governance.domain.EffectivePolicy;
import io.reliabilityai.gateway.dataplane.governance.domain.PolicySnapshot;
import io.reliabilityai.gateway.dataplane.governance.internal.InProcessPolicyMetrics;
import io.reliabilityai.gateway.dataplane.governance.internal.PolicyCompiler;
import io.reliabilityai.gateway.dataplane.governance.internal.PolicyLoader;
import io.reliabilityai.gateway.dataplane.governance.internal.PolicyRegistry;
import io.reliabilityai.gateway.dataplane.governance.internal.PolicyStore;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/** Tests compilation, the generation store, the fold cache, hot reload and rollback. */
class PolicyLifecycleTest {

  private static PolicyRegistry registry(final InProcessPolicyMetrics metrics) {
    return new PolicyRegistry(new PolicyStore(64, 3), metrics);
  }

  // ---- compilation ----------------------------------------------------------------------------

  @Test
  void aCoherentBundleCompiles() {
    final PolicySnapshot compiled =
        new PolicyCompiler()
            .compile(
                bundle(
                    1L,
                    document(GLOBAL, 1L, rule("g", PolicyType.MAX_COST, PolicyValue.Limit.of(1)))));

    assertThat(compiled.version().sequence()).isEqualTo(1L);
    assertThat(compiled.size()).isEqualTo(1);
  }

  @Test
  void aHardTierRuleAuthoredAsAdvisoryIsRefusedAtCompileTime() {
    final PolicySourcePort.PolicyBundle bad =
        bundle(
            1L,
            document(
                GLOBAL,
                1L,
                rule(
                    "weak",
                    PolicyType.REGION_RESTRICTION,
                    PolicyValue.Values.of("eu-west-1"),
                    EnforcementLevel.ADVISORY)));

    // Refusing here is what makes "residency can never be downgraded" true by construction rather
    // than by every evaluation path remembering to check.
    assertThatThrownBy(() -> new PolicyCompiler().compile(bad))
        .isInstanceOf(PolicyCompilationException.class)
        .hasMessageContaining("weak");
  }

  @Test
  void aHardTierRuleAuthoredInShadowIsRefusedAtCompileTime() {
    final PolicySourcePort.PolicyBundle bad =
        bundle(
            1L,
            document(
                GLOBAL,
                1L,
                rule(
                    "sneaky",
                    PolicyType.EMERGENCY_KILL_SWITCH,
                    PolicyValue.Flag.TRUE,
                    EnforcementLevel.SHADOW)));

    assertThatThrownBy(() -> new PolicyCompiler().compile(bad))
        .isInstanceOf(PolicyCompilationException.class);
  }

  @ParameterizedTest
  @EnumSource(PolicyType.class)
  void noHardTierTypeCanBeCompiledBelowMandatory(final PolicyType type) {
    if (!type.requiresMandatoryEnforcement()) {
      return;
    }
    final PolicySourcePort.PolicyBundle bad =
        bundle(
            1L, document(GLOBAL, 1L, rule("r", type, sampleFor(type), EnforcementLevel.ADVISORY)));

    assertThatThrownBy(() -> new PolicyCompiler().compile(bad))
        .isInstanceOf(PolicyCompilationException.class);
  }

  @ParameterizedTest
  @EnumSource(PolicyType.class)
  void everySoftTierTypeCanBeCompiledAsAdvisory(final PolicyType type) {
    if (type.requiresMandatoryEnforcement()) {
      return;
    }
    final PolicySnapshot compiled =
        new PolicyCompiler()
            .compile(
                bundle(
                    1L,
                    document(
                        GLOBAL, 1L, rule("r", type, sampleFor(type), EnforcementLevel.ADVISORY))));

    assertThat(compiled.policyAt(GLOBAL).ruleFor(type).enforcement())
        .isEqualTo(EnforcementLevel.ADVISORY);
  }

  @Test
  void twoDocumentsOnTheSameNodeAreRefused() {
    final PolicySourcePort.PolicyBundle clashing =
        bundle(
            1L,
            document(ORG_REF, 1L, rule("a", PolicyType.MAX_COST, PolicyValue.Limit.of(1))),
            document(ORG_REF, 2L, rule("b", PolicyType.MAX_COST, PolicyValue.Limit.of(2))));

    assertThatThrownBy(() -> new PolicyCompiler().compile(clashing))
        .isInstanceOf(PolicyCompilationException.class)
        .hasMessageContaining("same node");
  }

  @Test
  void aDisabledDocumentIsNotCompiledIn() {
    final GovernancePolicy disabled =
        new GovernancePolicy(
            "off",
            ORG_REF,
            PolicyVersion.of("v1", 1L),
            List.of(rule("r", PolicyType.MAX_COST, PolicyValue.Limit.of(1))),
            false);

    assertThat(new PolicyCompiler().compile(bundle(1L, disabled)).size()).isZero();
  }

  @Test
  void aRuleWhoseValueShapeContradictsItsTypeIsRefused() {
    assertThatThrownBy(
            () ->
                new PolicyRule(
                    "r", PolicyType.MAX_COST, PolicyValue.Flag.TRUE, EnforcementLevel.MANDATORY))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void anEmptyBundleCompilesToAnEmptyGeneration() {
    assertThat(new PolicyCompiler().compile(bundle(1L)).size()).isZero();
  }

  // ---- the store ------------------------------------------------------------------------------

  @Test
  void aFreshNodeHoldsNoGeneration() {
    assertThat(new PolicyStore(16, 2).isInstalled()).isFalse();
  }

  @Test
  void installingAGenerationMakesItCurrent() {
    final PolicyStore store = new PolicyStore(16, 2);

    assertThat(store.install(snapshot(5L, document(GLOBAL, 5L)))).isTrue();
    assertThat(store.isInstalled()).isTrue();
    assertThat(store.current().snapshot().version().sequence()).isEqualTo(5L);
  }

  @Test
  void anOlderGenerationIsRefusedRatherThanQuietlyUndoingATightening() {
    final PolicyStore store = new PolicyStore(16, 2);
    store.install(snapshot(5L, document(GLOBAL, 5L)));

    assertThat(store.install(snapshot(4L, document(GLOBAL, 4L)))).isFalse();
    assertThat(store.current().snapshot().version().sequence()).isEqualTo(5L);
  }

  @Test
  void reInstallingTheSameGenerationIsANoOp() {
    final PolicyStore store = new PolicyStore(16, 2);
    store.install(snapshot(5L, document(GLOBAL, 5L)));

    assertThat(store.install(snapshot(5L, document(GLOBAL, 5L)))).isFalse();
  }

  @Test
  void aSnapshotAndItsCacheAreAlwaysPublishedTogether() {
    final PolicyStore store = new PolicyStore(16, 2);
    store.install(snapshot(1L, document(GLOBAL, 1L)));
    final PolicyStore.Generation first = store.current();

    store.install(snapshot(2L, document(GLOBAL, 2L)));
    final PolicyStore.Generation second = store.current();

    // Pairing them makes it impossible to serve a fresh generation through a stale fold cache.
    assertThat(second.cache()).isNotSameAs(first.cache());
    assertThat(second.cache().snapshot()).isSameAs(second.snapshot());
  }

  @Test
  void aRollbackTargetMustBeAGenerationThisNodeActuallyServed() {
    final PolicyStore store = new PolicyStore(16, 2);
    store.install(snapshot(1L, document(GLOBAL, 1L)));
    store.install(snapshot(2L, document(GLOBAL, 2L)));

    assertThat(store.rollbackTo(PolicyVersion.of("v99", 99L))).isEmpty();
    assertThat(store.current().snapshot().version().sequence()).isEqualTo(2L);
  }

  @Test
  void rollbackRestoresAPreviousGeneration() {
    final PolicyStore store = new PolicyStore(16, 2);
    store.install(snapshot(1L, document(GLOBAL, 1L)));
    store.install(snapshot(2L, document(GLOBAL, 2L)));

    assertThat(store.rollbackTo(PolicyVersion.of("v1", 1L))).isPresent();
    assertThat(store.current().snapshot().version().sequence()).isEqualTo(1L);
  }

  @Test
  void rollbackHistoryIsBounded() {
    final PolicyStore store = new PolicyStore(16, 2);
    for (long sequence = 1; sequence <= 5; sequence++) {
      store.install(snapshot(sequence, document(GLOBAL, sequence)));
    }

    assertThat(store.rollbackTargets()).hasSize(2);
    assertThat(store.rollbackTo(PolicyVersion.of("v1", 1L))).isEmpty();
  }

  @Test
  void rollbackTargetsAreListedMostRecentFirst() {
    final PolicyStore store = new PolicyStore(16, 3);
    for (long sequence = 1; sequence <= 4; sequence++) {
      store.install(snapshot(sequence, document(GLOBAL, sequence)));
    }

    assertThat(store.rollbackTargets())
        .extracting(PolicyVersion::sequence)
        .containsExactly(3L, 2L, 1L);
  }

  @Test
  void aGenerationCanBeFoundForSimulationWhetherCurrentOrRetained() {
    final PolicyStore store = new PolicyStore(16, 3);
    store.install(snapshot(1L, document(GLOBAL, 1L)));
    store.install(snapshot(2L, document(GLOBAL, 2L)));

    assertThat(store.snapshotOf(PolicyVersion.of("v2", 2L))).isPresent();
    assertThat(store.snapshotOf(PolicyVersion.of("v1", 1L))).isPresent();
    assertThat(store.snapshotOf(PolicyVersion.of("v9", 9L))).isEmpty();
  }

  @Test
  void afterARollbackTheRestoredGenerationIsWhatRequestsSee() {
    final PolicyStore store = new PolicyStore(16, 3);
    store.install(
        snapshot(
            1L, document(GLOBAL, 1L, rule("a", PolicyType.MAX_COST, PolicyValue.Limit.of(100)))));
    store.install(
        snapshot(
            2L, document(GLOBAL, 2L, rule("b", PolicyType.MAX_COST, PolicyValue.Limit.of(1)))));
    store.rollbackTo(PolicyVersion.of("v1", 1L));

    final EffectivePolicy effective = store.current().cache().effectiveFor(chain(), hit -> {});
    assertThat(((PolicyValue.Limit) effective.ruleFor(PolicyType.MAX_COST).value()).value())
        .isEqualTo(100L);
  }

  // ---- the fold cache -------------------------------------------------------------------------

  @Test
  void theSecondRequestOnAChainReusesTheFold() {
    final InProcessPolicyMetrics metrics = new InProcessPolicyMetrics();
    final PolicyRegistry registry = registry(metrics);
    registry.install(snapshot(1L, document(GLOBAL, 1L)));

    final EffectivePolicy first = registry.effectiveFor(chain());
    final EffectivePolicy second = registry.effectiveFor(chain());

    assertThat(second).isSameAs(first);
    assertThat(metrics.cacheMisses()).isEqualTo(1L);
    assertThat(metrics.cacheHits()).isEqualTo(1L);
  }

  @Test
  void installingAGenerationDiscardsTheFoldsOfThePreviousOne() {
    final PolicyRegistry registry = registry(new InProcessPolicyMetrics());
    registry.install(
        snapshot(
            1L, document(GLOBAL, 1L, rule("a", PolicyType.MAX_COST, PolicyValue.Limit.of(100)))));
    final EffectivePolicy before = registry.effectiveFor(chain());

    registry.install(
        snapshot(
            2L, document(GLOBAL, 2L, rule("b", PolicyType.MAX_COST, PolicyValue.Limit.of(5)))));
    final EffectivePolicy after = registry.effectiveFor(chain());

    assertThat(after).isNotSameAs(before);
    assertThat(((PolicyValue.Limit) after.ruleFor(PolicyType.MAX_COST).value()).value())
        .isEqualTo(5L);
  }

  @Test
  void theFoldCacheStopsGrowingAtItsBound() {
    final PolicyStore store = new PolicyStore(2, 1);
    store.install(snapshot(1L, document(GLOBAL, 1L)));
    final var cache = store.current().cache();

    for (int i = 0; i < 20; i++) {
      cache.effectiveFor(
          io.reliabilityai.gateway.dataplane.governance.api.ScopeChain.builder()
              .organization("org-" + i)
              .build(),
          hit -> {});
    }

    assertThat(cache.size()).isEqualTo(2);
  }

  @Test
  void aChainPastTheCacheBoundStillResolvesCorrectly() {
    final PolicyStore store = new PolicyStore(1, 1);
    store.install(
        snapshot(
            1L, document(GLOBAL, 1L, rule("g", PolicyType.MAX_COST, PolicyValue.Limit.of(7)))));
    final var cache = store.current().cache();
    cache.effectiveFor(
        io.reliabilityai.gateway.dataplane.governance.api.ScopeChain.builder()
            .organization("filler")
            .build(),
        hit -> {});

    final EffectivePolicy overflowed = cache.effectiveFor(chain(), hit -> {});

    assertThat(((PolicyValue.Limit) overflowed.ruleFor(PolicyType.MAX_COST).value()).value())
        .isEqualTo(7L);
  }

  // ---- hot reload -----------------------------------------------------------------------------

  @Test
  void reloadCompilesAndInstallsWhatTheSourceOffers() {
    final InProcessPolicyMetrics metrics = new InProcessPolicyMetrics();
    final PolicyRegistry registry = registry(metrics);
    final AtomicReference<PolicySourcePort.PolicyBundle> published =
        new AtomicReference<>(bundle(1L, document(GLOBAL, 1L)));
    final PolicyLoader loader =
        new PolicyLoader(
            () -> Optional.ofNullable(published.get()), new PolicyCompiler(), registry, metrics);

    assertThat(loader.reload()).isTrue();
    assertThat(registry.currentVersion().sequence()).isEqualTo(1L);

    published.set(bundle(2L, document(GLOBAL, 2L)));
    assertThat(loader.reload()).isTrue();
    assertThat(registry.currentVersion().sequence()).isEqualTo(2L);
  }

  @Test
  void aSourceThatCannotAnswerLeavesThePreviousGenerationServing() {
    final InProcessPolicyMetrics metrics = new InProcessPolicyMetrics();
    final PolicyRegistry registry = registry(metrics);
    registry.install(snapshot(3L, document(GLOBAL, 3L)));
    final PolicyLoader loader =
        new PolicyLoader(PolicySourcePort.EMPTY, new PolicyCompiler(), registry, metrics);

    assertThat(loader.reload()).isFalse();
    assertThat(registry.currentVersion().sequence()).isEqualTo(3L);
    assertThat(metrics.rejections()).isEqualTo(1L);
  }

  @Test
  void aSourceThatThrowsLeavesThePreviousGenerationServing() {
    final InProcessPolicyMetrics metrics = new InProcessPolicyMetrics();
    final PolicyRegistry registry = registry(metrics);
    registry.install(snapshot(3L, document(GLOBAL, 3L)));
    final PolicyLoader loader =
        new PolicyLoader(
            () -> {
              throw new IllegalStateException("distribution outage");
            },
            new PolicyCompiler(),
            registry,
            metrics);

    assertThat(loader.reload()).isFalse();
    assertThat(registry.currentVersion().sequence()).isEqualTo(3L);
  }

  @Test
  void aBundleThatWillNotCompileLeavesThePreviousGenerationServing() {
    final InProcessPolicyMetrics metrics = new InProcessPolicyMetrics();
    final PolicyRegistry registry = registry(metrics);
    registry.install(snapshot(3L, document(GLOBAL, 3L)));
    final PolicySourcePort.PolicyBundle bad =
        bundle(
            9L,
            document(
                GLOBAL,
                9L,
                rule(
                    "weak",
                    PolicyType.PII_RESTRICTION,
                    PolicyValue.Flag.TRUE,
                    EnforcementLevel.SHADOW)));
    final PolicyLoader loader =
        new PolicyLoader(() -> Optional.of(bad), new PolicyCompiler(), registry, metrics);

    // A control-plane defect must not be able to take the gate down or, worse, empty it.
    assertThat(loader.reload()).isFalse();
    assertThat(registry.currentVersion().sequence()).isEqualTo(3L);
    assertThat(metrics.rejections()).isEqualTo(1L);
  }

  @Test
  void aRefusedReloadIsCountedSoAFrozenNodeIsVisible() {
    final InProcessPolicyMetrics metrics = new InProcessPolicyMetrics();
    final PolicyRegistry registry = registry(metrics);
    final PolicyLoader loader =
        new PolicyLoader(PolicySourcePort.EMPTY, new PolicyCompiler(), registry, metrics);

    loader.reload();
    loader.reload();

    assertThat(metrics.rejections()).isEqualTo(2L);
    assertThat(metrics.installs()).isZero();
  }

  @Test
  void installsAndRollbacksAreCountedSeparately() {
    final InProcessPolicyMetrics metrics = new InProcessPolicyMetrics();
    final PolicyRegistry registry = registry(metrics);
    registry.install(snapshot(1L, document(GLOBAL, 1L)));
    registry.install(snapshot(2L, document(GLOBAL, 2L)));
    registry.rollbackTo(PolicyVersion.of("v1", 1L));

    assertThat(metrics.installs()).isEqualTo(2L);
    assertThat(metrics.rollbacks()).isEqualTo(1L);
  }

  @Test
  void aRollbackToAnUnknownGenerationIsCountedAsARejection() {
    final InProcessPolicyMetrics metrics = new InProcessPolicyMetrics();
    final PolicyRegistry registry = registry(metrics);
    registry.install(snapshot(1L, document(GLOBAL, 1L)));

    assertThat(registry.rollbackTo(PolicyVersion.of("nope", 42L))).isEmpty();
    assertThat(metrics.rejections()).isEqualTo(1L);
  }

  @Test
  void aMultiDocumentGenerationInstallsAsOneUnit() {
    final PolicyRegistry registry = registry(new InProcessPolicyMetrics());

    assertThat(
            registry.install(
                snapshot(
                    1L,
                    document(GLOBAL, 1L, rule("g", PolicyType.MAX_COST, PolicyValue.Limit.of(9))),
                    document(
                        ORG_REF, 1L, rule("o", PolicyType.MAX_CONTEXT, PolicyValue.Limit.of(9))),
                    document(
                        PROJECT_REF, 1L, rule("p", PolicyType.MAX_RPM, PolicyValue.Limit.of(9))))))
        .isTrue();
    assertThat(registry.current().size()).isEqualTo(3);
  }

  private static PolicyValue sampleFor(final PolicyType type) {
    return switch (type.kind()) {
      case ALLOW_LIST, DENY_LIST -> PolicyValue.Values.of("x");
      case CAPABILITY, REQUIREMENT, PROHIBITION -> PolicyValue.Flag.TRUE;
      case CEILING -> PolicyValue.Limit.of(1);
      case WINDOW ->
          PolicyValue.Windows.of(
              new PolicyValue.TimeWindow(PolicyFixture.NOW, PolicyFixture.NOW.plusSeconds(60)));
    };
  }
}

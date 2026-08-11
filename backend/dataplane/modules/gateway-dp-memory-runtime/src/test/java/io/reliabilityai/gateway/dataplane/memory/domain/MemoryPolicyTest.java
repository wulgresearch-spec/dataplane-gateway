package io.reliabilityai.gateway.dataplane.memory.domain;

import static io.reliabilityai.gateway.dataplane.memory.MemoryFixtures.OTHER_REGION;
import static io.reliabilityai.gateway.dataplane.memory.MemoryFixtures.REGION;
import static io.reliabilityai.gateway.dataplane.memory.MemoryFixtures.T0;
import static io.reliabilityai.gateway.dataplane.memory.MemoryFixtures.TENANT;
import static io.reliabilityai.gateway.dataplane.memory.MemoryFixtures.permissivePolicy;
import static org.assertj.core.api.Assertions.assertThat;

import io.reliabilityai.gateway.canonical.identity.PrincipalId;
import io.reliabilityai.gateway.dataplane.memory.api.DataClassification;
import io.reliabilityai.gateway.dataplane.memory.api.MemoryPolicyType;
import io.reliabilityai.gateway.dataplane.memory.api.MemoryScope;
import io.reliabilityai.gateway.dataplane.memory.api.MemoryType;
import io.reliabilityai.gateway.dataplane.memory.api.PiiAction;
import io.reliabilityai.gateway.dataplane.memory.domain.EffectiveMemoryPolicy.VersioningMode;
import io.reliabilityai.gateway.dataplane.memory.internal.MemoryPolicyStore;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayNameGeneration;
import org.junit.jupiter.api.DisplayNameGenerator;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * The policy algebra, the compiled snapshot, and the atomic swap.
 *
 * <p>The merge is claimed to be a semilattice meet — idempotent, commutative, associative and
 * monotonically non-loosening (MEM-19). Those are properties, not examples, so they are asserted
 * over a generated cross-product of policies. A merge rule that loosened in one corner of the space
 * is exactly the kind of defect a handful of chosen cases would miss, and it would be a silent
 * privilege escalation.
 */
@DisplayNameGeneration(DisplayNameGenerator.ReplaceUnderscores.class)
class MemoryPolicyTest {

  private static EffectiveMemoryPolicy policy(
      final Duration ttl,
      final PiiAction pii,
      final Set<String> regions,
      final boolean encryption,
      final boolean hold,
      final boolean deleteAllowed,
      final DataClassification ceiling) {
    return new EffectiveMemoryPolicy(
        Optional.ofNullable(ttl),
        Optional.empty(),
        pii,
        regions,
        encryption,
        hold,
        deleteAllowed,
        Optional.empty(),
        VersioningMode.APPEND,
        Optional.empty(),
        ceiling,
        true);
  }

  /** A spread of policies covering each dimension's extremes. */
  private static List<EffectiveMemoryPolicy> samples() {
    final List<EffectiveMemoryPolicy> policies = new ArrayList<>();
    for (final Duration ttl : new Duration[] {null, Duration.ofHours(1), Duration.ofDays(30)}) {
      for (final PiiAction pii : PiiAction.values()) {
        for (final Set<String> regions :
            List.of(Set.<String>of(), Set.of(REGION), Set.of(REGION, OTHER_REGION))) {
          policies.add(
              policy(ttl, pii, regions, false, false, true, DataClassification.SENSITIVE_PII));
        }
      }
    }
    policies.add(
        policy(
            Duration.ofDays(1),
            PiiAction.ALLOW,
            Set.of(REGION),
            true,
            true,
            false,
            DataClassification.INTERNAL));
    policies.add(EffectiveMemoryPolicy.UNENFORCEABLE);
    return policies;
  }

  static Stream<Arguments> policyPairs() {
    final List<EffectiveMemoryPolicy> samples = samples();
    final List<Arguments> pairs = new ArrayList<>();
    for (final EffectiveMemoryPolicy left : samples) {
      for (final EffectiveMemoryPolicy right : samples) {
        pairs.add(Arguments.of(left, right));
      }
    }
    return pairs.stream();
  }

  // ---- The semilattice properties -----------------------------------------------------------

  @ParameterizedTest
  @MethodSource("policyPairs")
  void mergingIsCommutative(final EffectiveMemoryPolicy a, final EffectiveMemoryPolicy b) {
    assertThat(a.mergeWith(b)).isEqualTo(b.mergeWith(a));
  }

  @ParameterizedTest
  @MethodSource("policyPairs")
  void mergingIsIdempotent(final EffectiveMemoryPolicy a, final EffectiveMemoryPolicy b) {
    final EffectiveMemoryPolicy merged = a.mergeWith(b);
    assertThat(merged.mergeWith(merged)).isEqualTo(merged);
  }

  @ParameterizedTest
  @MethodSource("policyPairs")
  void mergingNeverLengthensTheTtl(final EffectiveMemoryPolicy a, final EffectiveMemoryPolicy b) {
    // MEM-19 on the TTL dimension: any scope may shorten a life, none may lengthen one.
    final EffectiveMemoryPolicy merged = a.mergeWith(b);
    for (final EffectiveMemoryPolicy input : List.of(a, b)) {
      if (input.ttl().isPresent() && merged.ttl().isPresent()) {
        assertThat(merged.ttl().get()).isLessThanOrEqualTo(input.ttl().get());
      }
    }
  }

  @ParameterizedTest
  @MethodSource("policyPairs")
  void mergingNeverWidensThePermittedRegions(
      final EffectiveMemoryPolicy a, final EffectiveMemoryPolicy b) {
    final EffectiveMemoryPolicy merged = a.mergeWith(b);
    assertThat(a.permittedRegions()).containsAll(merged.permittedRegions());
    assertThat(b.permittedRegions()).containsAll(merged.permittedRegions());
  }

  @ParameterizedTest
  @MethodSource("policyPairs")
  void mergingNeverSoftensThePiiAction(
      final EffectiveMemoryPolicy a, final EffectiveMemoryPolicy b) {
    final EffectiveMemoryPolicy merged = a.mergeWith(b);
    assertThat(merged.piiAction().severity())
        .isGreaterThanOrEqualTo(Math.max(a.piiAction().severity(), b.piiAction().severity()));
  }

  @ParameterizedTest
  @MethodSource("policyPairs")
  void mergingNeverRemovesAnEncryptionRequirement(
      final EffectiveMemoryPolicy a, final EffectiveMemoryPolicy b) {
    if (a.encryptionRequired() || b.encryptionRequired()) {
      assertThat(a.mergeWith(b).encryptionRequired()).isTrue();
    }
  }

  @ParameterizedTest
  @MethodSource("policyPairs")
  void aLegalHoldAnywhereInTheChainWins(
      final EffectiveMemoryPolicy a, final EffectiveMemoryPolicy b) {
    // MEM-20: a hold cannot be merged away, whatever the other scope says.
    if (a.legalHold() || b.legalHold()) {
      assertThat(a.mergeWith(b).legalHold()).isTrue();
    }
  }

  @ParameterizedTest
  @MethodSource("policyPairs")
  void mergingNeverGrantsDeletionThatEitherSideForbade(
      final EffectiveMemoryPolicy a, final EffectiveMemoryPolicy b) {
    if (!a.deleteAllowed() || !b.deleteAllowed()) {
      assertThat(a.mergeWith(b).deleteAllowed()).isFalse();
    }
  }

  @ParameterizedTest
  @MethodSource("policyPairs")
  void mergingNeverRaisesTheClassificationCeiling(
      final EffectiveMemoryPolicy a, final EffectiveMemoryPolicy b) {
    final EffectiveMemoryPolicy merged = a.mergeWith(b);
    assertThat(merged.maxClassification().severity())
        .isLessThanOrEqualTo(
            Math.min(a.maxClassification().severity(), b.maxClassification().severity()));
  }

  @ParameterizedTest
  @MethodSource("policyPairs")
  void mergingWithAnUnenforceablePolicyStaysUnenforceable(
      final EffectiveMemoryPolicy a, final EffectiveMemoryPolicy b) {
    // Unenforceable is contagious, and must be. A scope that could not be resolved does not become
    // resolvable by being merged with one that could (MEM-21).
    if (!a.enforceable() || !b.enforceable()) {
      assertThat(a.mergeWith(b).enforceable()).isFalse();
    }
  }

  @Test
  void mergingIsAssociative() {
    final List<EffectiveMemoryPolicy> samples = samples();
    for (int i = 0; i < samples.size(); i += 7) {
      for (int j = 1; j < samples.size(); j += 11) {
        for (int k = 2; k < samples.size(); k += 13) {
          final EffectiveMemoryPolicy a = samples.get(i);
          final EffectiveMemoryPolicy b = samples.get(j % samples.size());
          final EffectiveMemoryPolicy c = samples.get(k % samples.size());
          assertThat(a.mergeWith(b).mergeWith(c)).isEqualTo(a.mergeWith(b.mergeWith(c)));
        }
      }
    }
  }

  // ---- Individual rules --------------------------------------------------------------------

  @Test
  void theUnenforceableDefaultIsTheStrictestThingAvailableRatherThanTheMostPermissive() {
    // The single most important default in the module. "No policy" must never mean "no
    // restrictions".
    final EffectiveMemoryPolicy none = EffectiveMemoryPolicy.UNENFORCEABLE;
    assertThat(none.enforceable()).isFalse();
    assertThat(none.piiAction()).isEqualTo(PiiAction.REFUSE);
    assertThat(none.hasPermittedRegion()).isFalse();
    assertThat(none.encryptionRequired()).isTrue();
    assertThat(none.deleteAllowed()).isFalse();
    assertThat(none.permitsClassification(DataClassification.PUBLIC)).isFalse();
  }

  @Test
  void aRetentionFloorAboveTheTtlCeilingIsSurfacedRatherThanSilentlyResolved() {
    // Deleting data a retention rule requires is the worse error, and a rule conflict nobody sees
    // is
    // worse than both (AD-026 §8.2).
    final EffectiveMemoryPolicy conflicted =
        new EffectiveMemoryPolicy(
            Optional.of(Duration.ofDays(1)),
            Optional.of(Duration.ofDays(365)),
            PiiAction.ALLOW,
            Set.of(REGION),
            false,
            false,
            true,
            Optional.empty(),
            VersioningMode.APPEND,
            Optional.empty(),
            DataClassification.PII,
            true);
    assertThat(conflicted.retentionConflictsWithTtl()).isTrue();
    // And the record does not expire, rather than expiring against the retention rule.
    assertThat(conflicted.expiryFor(T0, Optional.empty())).isEmpty();
  }

  @Test
  void aCallerRequestedTtlMayShortenButNeverLengthen() {
    final EffectiveMemoryPolicy policy = permissivePolicy();
    assertThat(policy.ttl()).contains(Duration.ofDays(30));

    final Optional<java.time.Instant> shortened =
        policy.expiryFor(T0, Optional.of(Duration.ofHours(1)));
    assertThat(shortened).contains(T0.plus(Duration.ofHours(1)));

    // Asking for a century gets the policy's thirty days. Otherwise any caller could opt out of
    // retention limits simply by asking (AD-026 §5.1).
    final Optional<java.time.Instant> attempted =
        policy.expiryFor(T0, Optional.of(Duration.ofDays(36_500)));
    assertThat(attempted).contains(T0.plus(Duration.ofDays(30)));
  }

  @Test
  void aPolicyWithNoTtlAndNoRequestProducesNoExpiry() {
    final EffectiveMemoryPolicy forever =
        policy(null, PiiAction.ALLOW, Set.of(REGION), false, false, true, DataClassification.PII);
    assertThat(forever.expiryFor(T0, Optional.empty())).isEmpty();
  }

  @Test
  void anEmptyRegionIntersectionMeansThereIsNowhereLawfulToPutTheBytes() {
    final EffectiveMemoryPolicy eu =
        policy(null, PiiAction.ALLOW, Set.of(REGION), false, false, true, DataClassification.PII);
    final EffectiveMemoryPolicy us =
        policy(
            null,
            PiiAction.ALLOW,
            Set.of(OTHER_REGION),
            false,
            false,
            true,
            DataClassification.PII);
    assertThat(eu.mergeWith(us).hasPermittedRegion()).isFalse();
  }

  @Test
  void sealingIsRequiredWhenEitherTheEncryptionPolicyOrThePiiActionDemandsIt() {
    final EffectiveMemoryPolicy encrypted =
        policy(
            null,
            PiiAction.ALLOW,
            Set.of(REGION),
            true,
            false,
            true,
            DataClassification.SENSITIVE_PII);
    assertThat(encrypted.requiresSealing(DataClassification.PUBLIC)).isTrue();

    final EffectiveMemoryPolicy piiEncrypts =
        policy(
            null,
            PiiAction.ENCRYPT,
            Set.of(REGION),
            false,
            false,
            true,
            DataClassification.SENSITIVE_PII);
    assertThat(piiEncrypts.requiresSealing(DataClassification.PII)).isTrue();
    assertThat(piiEncrypts.requiresSealing(DataClassification.INTERNAL)).isFalse();
  }

  @Test
  void aCredentialIsNeverPermittedByAnyPolicy() {
    assertThat(permissivePolicy().permitsClassification(DataClassification.SECRET)).isFalse();
  }

  @Test
  void anUnclassifiedBodyIsNeverPermittedByAnyPolicy() {
    assertThat(permissivePolicy().permitsClassification(DataClassification.UNCLASSIFIED)).isFalse();
  }

  @ParameterizedTest
  @EnumSource(MemoryPolicyType.class)
  void everyPolicyTypeDeclaresHowItMerges(final MemoryPolicyType type) {
    assertThat(type.mergeRule()).isNotNull();
  }

  @Test
  void onlyThePoliciesThatCanStopAWriteSaySoTheyCan() {
    assertThat(MemoryPolicyType.RESIDENCY.canRefuseWrite()).isTrue();
    assertThat(MemoryPolicyType.PII.canRefuseWrite()).isTrue();
    assertThat(MemoryPolicyType.DELETE.canRefuseWrite()).isTrue();
    assertThat(MemoryPolicyType.TTL.canRefuseWrite()).isFalse();
    assertThat(MemoryPolicyType.ARCHIVE.canRefuseWrite()).isFalse();
  }

  // ---- Snapshot compilation and resolution ---------------------------------------------------

  @Test
  void anEmptySnapshotResolvesToUnenforceableSoANodeWithNoConfigurationRefusesToServe() {
    assertThat(MemoryPolicySnapshot.EMPTY.installed()).isFalse();
    assertThat(MemoryPolicySnapshot.EMPTY.resolve(MemoryScope.ofTenant(TENANT), MemoryType.SESSION))
        .isEqualTo(EffectiveMemoryPolicy.UNENFORCEABLE);
  }

  @Test
  void compilationMergesABroadPolicyIntoEveryNarrowerScopeBeneathIt() {
    final MemoryScope tenant = MemoryScope.ofTenant(TENANT);
    final MemoryScope user = MemoryScope.ofUser(TENANT, new PrincipalId("alice"));

    final EffectiveMemoryPolicy broad =
        policy(
            Duration.ofDays(30),
            PiiAction.ALLOW,
            Set.of(REGION, OTHER_REGION),
            false,
            false,
            true,
            DataClassification.SENSITIVE_PII);
    final EffectiveMemoryPolicy narrow =
        policy(
            Duration.ofDays(1),
            PiiAction.REDACT,
            Set.of(REGION),
            false,
            false,
            true,
            DataClassification.PII);

    final MemoryPolicySnapshot snapshot =
        MemoryPolicySnapshot.compile(
            1L, Map.of(tenant, broad, user, narrow), Map.of(), EffectiveMemoryPolicy.UNENFORCEABLE);

    final EffectiveMemoryPolicy resolved = snapshot.resolve(user, MemoryType.LONG_TERM);
    // The narrow scope's stricter values win, and the broad scope's constraints are already folded
    // in.
    assertThat(resolved.ttl()).contains(Duration.ofDays(1));
    assertThat(resolved.piiAction()).isEqualTo(PiiAction.REDACT);
    assertThat(resolved.permittedRegions()).containsExactly(REGION);
  }

  @Test
  void resolutionPrefersTheNarrowestMatchingScope() {
    final MemoryScope tenant = MemoryScope.ofTenant(TENANT);
    final MemoryScope user = MemoryScope.ofUser(TENANT, new PrincipalId("alice"));
    final MemoryPolicySnapshot snapshot =
        MemoryPolicySnapshot.compile(
            1L, Map.of(tenant, permissivePolicy()), Map.of(), EffectiveMemoryPolicy.UNENFORCEABLE);
    // The user scope has no entry of its own, so it inherits the tenant's rather than falling
    // through
    // to the unenforceable default.
    assertThat(snapshot.resolve(user, MemoryType.SESSION).enforceable()).isTrue();
  }

  @Test
  void aScopeWithNoPolicyAtAllFallsBackRatherThanInventingOne() {
    final MemoryPolicySnapshot snapshot =
        new MemoryPolicySnapshot(1L, Map.of(), Map.of(), EffectiveMemoryPolicy.UNENFORCEABLE);
    assertThat(snapshot.resolve(MemoryScope.ofTenant(TENANT), MemoryType.SESSION).enforceable())
        .isFalse();
  }

  @Test
  void aPerTypeOverrideAppliesWhenNoScopeMatches() {
    final MemoryPolicySnapshot snapshot =
        new MemoryPolicySnapshot(
            1L,
            Map.of(),
            Map.of(MemoryType.WORKING, permissivePolicy()),
            EffectiveMemoryPolicy.UNENFORCEABLE);
    assertThat(snapshot.resolve(MemoryScope.ofTenant(TENANT), MemoryType.WORKING).enforceable())
        .isTrue();
    assertThat(snapshot.resolve(MemoryScope.ofTenant(TENANT), MemoryType.SESSION).enforceable())
        .isFalse();
  }

  // ---- The atomic swap ------------------------------------------------------------------------

  @Test
  void aFreshStoreServesTheFailClosedEmptySnapshot() {
    final MemoryPolicyStore store = new MemoryPolicyStore(4);
    assertThat(store.installed()).isFalse();
    assertThat(store.current()).isEqualTo(MemoryPolicySnapshot.EMPTY);
  }

  @Test
  void installingANewerSnapshotReplacesTheLiveOne() {
    final MemoryPolicyStore store = new MemoryPolicyStore(4);
    final MemoryPolicySnapshot first =
        new MemoryPolicySnapshot(1L, Map.of(), Map.of(), permissivePolicy());
    assertThat(store.install(first)).isTrue();
    assertThat(store.current()).isEqualTo(first);
    assertThat(store.installed()).isTrue();
  }

  @Test
  void aStaleSnapshotArrivingLateIsRefusedRatherThanRevertingPolicy() {
    // A slow control-plane push or a retried delivery must not undo a tightening made after an
    // incident.
    final MemoryPolicyStore store = new MemoryPolicyStore(4);
    store.install(new MemoryPolicySnapshot(5L, Map.of(), Map.of(), permissivePolicy()));
    assertThat(store.install(new MemoryPolicySnapshot(3L, Map.of(), Map.of(), permissivePolicy())))
        .isFalse();
    assertThat(store.current().version()).isEqualTo(5L);
  }

  @Test
  void reinstallingTheSameVersionIsRefused() {
    final MemoryPolicyStore store = new MemoryPolicyStore(4);
    final MemoryPolicySnapshot snapshot =
        new MemoryPolicySnapshot(2L, Map.of(), Map.of(), permissivePolicy());
    assertThat(store.install(snapshot)).isTrue();
    assertThat(store.install(snapshot)).isFalse();
  }

  @Test
  void rollbackRestampsSoItIsNotItselfRefusedByTheMonotonicityRule() {
    final MemoryPolicyStore store = new MemoryPolicyStore(4);
    final EffectiveMemoryPolicy original = permissivePolicy();
    store.install(new MemoryPolicySnapshot(1L, Map.of(), Map.of(), original));
    store.install(
        new MemoryPolicySnapshot(2L, Map.of(), Map.of(), EffectiveMemoryPolicy.UNENFORCEABLE));

    final Optional<MemoryPolicySnapshot> rolled = store.rollbackTo(1L);
    assertThat(rolled).isPresent();
    assertThat(store.current().fallback()).isEqualTo(original);
    // Restamped above the live version, so the rollback actually took effect.
    assertThat(store.current().version()).isGreaterThan(2L);
  }

  @Test
  void rollingBackToAVersionNoLongerRetainedIsRefused() {
    final MemoryPolicyStore store = new MemoryPolicyStore(1);
    store.install(new MemoryPolicySnapshot(1L, Map.of(), Map.of(), permissivePolicy()));
    store.install(new MemoryPolicySnapshot(2L, Map.of(), Map.of(), permissivePolicy()));
    store.install(new MemoryPolicySnapshot(3L, Map.of(), Map.of(), permissivePolicy()));
    assertThat(store.rollbackTo(1L)).isEmpty();
  }

  @Test
  void theHistoryIsBoundedSoAnUpdateStormCannotExhaustMemory() {
    final MemoryPolicyStore store = new MemoryPolicyStore(3);
    for (int version = 1; version <= 50; version++) {
      store.install(new MemoryPolicySnapshot(version, Map.of(), Map.of(), permissivePolicy()));
    }
    assertThat(store.history()).hasSizeLessThanOrEqualTo(3);
  }

  @Test
  void concurrentReadersNeverSeeAHalfInstalledSnapshot() throws Exception {
    // The lock-free guarantee (MEM-27), exercised: readers spin while a writer installs, and every
    // snapshot a reader observes must be internally whole.
    final MemoryPolicyStore store = new MemoryPolicyStore(2);
    store.install(new MemoryPolicySnapshot(1L, Map.of(), Map.of(), permissivePolicy()));

    final java.util.concurrent.ExecutorService pool =
        java.util.concurrent.Executors.newFixedThreadPool(5);
    final java.util.concurrent.atomic.AtomicBoolean stop =
        new java.util.concurrent.atomic.AtomicBoolean();
    final java.util.concurrent.atomic.AtomicBoolean torn =
        new java.util.concurrent.atomic.AtomicBoolean();

    final List<java.util.concurrent.Future<?>> readers = new ArrayList<>();
    for (int i = 0; i < 4; i++) {
      readers.add(
          pool.submit(
              () -> {
                while (!stop.get()) {
                  final MemoryPolicySnapshot seen = store.current();
                  if (seen.byScope() == null || seen.fallback() == null) {
                    torn.set(true);
                  }
                }
              }));
    }
    for (int version = 2; version <= 500; version++) {
      store.install(new MemoryPolicySnapshot(version, Map.of(), Map.of(), permissivePolicy()));
    }
    stop.set(true);
    for (final java.util.concurrent.Future<?> reader : readers) {
      reader.get(30, java.util.concurrent.TimeUnit.SECONDS);
    }
    pool.shutdownNow();

    assertThat(torn).isFalse();
    assertThat(store.current().version()).isEqualTo(500L);
  }
}

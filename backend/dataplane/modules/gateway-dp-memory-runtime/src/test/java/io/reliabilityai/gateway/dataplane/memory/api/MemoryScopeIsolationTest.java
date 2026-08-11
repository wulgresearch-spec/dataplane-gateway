package io.reliabilityai.gateway.dataplane.memory.api;

import static io.reliabilityai.gateway.dataplane.memory.MemoryFixtures.OTHER_TENANT;
import static io.reliabilityai.gateway.dataplane.memory.MemoryFixtures.TENANT;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.reliabilityai.gateway.canonical.identity.PrincipalId;
import io.reliabilityai.gateway.canonical.identity.TenantScope;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayNameGeneration;
import org.junit.jupiter.api.DisplayNameGenerator;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * The isolation model, exhausted.
 *
 * <p>{@code narrowTo} is the single most important method in the module: it runs before any adapter
 * is touched, and it is the whole of the cross-tenant guarantee. The central property — that
 * narrowing can only ever tighten — is asserted over a generated cross-product of scopes rather
 * than on a handful of chosen examples, because a hole in an isolation model is exactly the thing
 * chosen examples miss.
 */
@DisplayNameGeneration(DisplayNameGenerator.ReplaceUnderscores.class)
class MemoryScopeIsolationTest {

  private static final PrincipalId ALICE = new PrincipalId("alice");
  private static final PrincipalId BOB = new PrincipalId("bob");

  private static MemoryScope scope(
      final String org,
      final String tenant,
      final String workspace,
      final String project,
      final PrincipalId owner,
      final String partition) {
    return new MemoryScope(
        new TenantScope(org, tenant, workspace, project),
        Optional.ofNullable(owner),
        partition == null ? MemoryScope.NO_PARTITION : partition);
  }

  /** Every combination worth trying: two tenants, two workspaces, two owners, two partitions. */
  static Stream<Arguments> everyScopePair() {
    final List<MemoryScope> scopes = new ArrayList<>();
    for (final String tenant : new String[] {"core", "other"}) {
      for (final String workspace : new String[] {null, "ws-a", "ws-b"}) {
        for (final PrincipalId owner : new PrincipalId[] {null, ALICE, BOB}) {
          for (final String partition : new String[] {null, "s-1"}) {
            scopes.add(scope("acme", tenant, workspace, null, owner, partition));
          }
        }
      }
    }
    final List<Arguments> pairs = new ArrayList<>();
    for (final MemoryScope requested : scopes) {
      for (final MemoryScope caller : scopes) {
        pairs.add(Arguments.of(requested, caller));
      }
    }
    return pairs.stream();
  }

  @ParameterizedTest
  @MethodSource("everyScopePair")
  void narrowingNeverProducesSomethingWiderThanTheCallersOwnAuthority(
      final MemoryScope requested, final MemoryScope caller) {
    // The property the whole isolation model rests on. For any pair at all, the narrowed scope must
    // be
    // visible to the caller — that is, it can never grant reach the caller did not already have.
    final Optional<MemoryScope> narrowed = requested.narrowTo(caller);
    narrowed.ifPresent(
        result ->
            assertThat(result.visibleTo(caller))
                .as("%s narrowed to %s gave %s", requested.key(), caller.key(), result.key())
                .isTrue());
  }

  @ParameterizedTest
  @MethodSource("everyScopePair")
  void narrowingAcrossTenantsAlwaysRefuses(final MemoryScope requested, final MemoryScope caller) {
    if (!requested.sameTenantAs(caller)) {
      assertThat(requested.narrowTo(caller)).isEmpty();
    }
  }

  @ParameterizedTest
  @MethodSource("everyScopePair")
  void narrowingIsIdempotent(final MemoryScope requested, final MemoryScope caller) {
    // Narrowing an already-narrowed scope against the same caller must change nothing. If it did,
    // the
    // pipeline's single narrowing step would be doing less than a second one would.
    final Optional<MemoryScope> once = requested.narrowTo(caller);
    once.ifPresent(first -> assertThat(first.narrowTo(caller)).contains(first));
  }

  @Test
  void aDifferentTenantHasNoIntersectionAtAll() {
    final MemoryScope mine = MemoryScope.ofTenant(TENANT);
    final MemoryScope theirs = MemoryScope.ofTenant(OTHER_TENANT);
    assertThat(mine.narrowTo(theirs)).isEmpty();
    assertThat(theirs.narrowTo(mine)).isEmpty();
    assertThat(mine.visibleTo(theirs)).isFalse();
  }

  @Test
  void aCallerPinnedToAWorkspaceCannotReachAnother() {
    final MemoryScope caller = scope("acme", "core", "ws-a", null, null, null);
    final MemoryScope requested = scope("acme", "core", "ws-b", null, null, null);
    assertThat(requested.narrowTo(caller)).isEmpty();
  }

  @Test
  void aCallerPinnedToAWorkspaceHasItsRequestNarrowedToThatWorkspace() {
    final MemoryScope caller = scope("acme", "core", "ws-a", null, null, null);
    final MemoryScope requested = MemoryScope.ofTenant(TENANT);
    assertThat(requested.narrowTo(caller).orElseThrow().workspace()).contains("ws-a");
  }

  @Test
  void aCallerPinnedToAnOwnerCannotAskForAnotherPrincipalsMemories() {
    // And is not told that the other principal exists — the request is narrowed away rather than
    // answered with a distinguishable error (AD-026 §10.3).
    final MemoryScope caller = MemoryScope.ofUser(TENANT, ALICE);
    final MemoryScope requested = MemoryScope.ofUser(TENANT, BOB);
    assertThat(requested.narrowTo(caller)).isEmpty();
  }

  @Test
  void aCallerPinnedToAnOwnerHasABroadRequestNarrowedToItself() {
    final MemoryScope caller = MemoryScope.ofUser(TENANT, ALICE);
    final MemoryScope requested = MemoryScope.ofTenant(TENANT);
    assertThat(requested.narrowTo(caller).orElseThrow().owner()).contains(ALICE);
  }

  @Test
  void aUserPrivateRecordIsInvisibleToAWorkspaceWideCallerWhoIsNotThatUser() {
    // The rule that makes user isolation mean something. Without it, any workspace member could
    // read
    // every other member's private memories simply by not naming an owner.
    final MemoryScope record = MemoryScope.ofUser(TENANT, ALICE);
    final MemoryScope caller = MemoryScope.ofTenant(TENANT);
    assertThat(record.visibleTo(caller)).isFalse();
  }

  @Test
  void aSharedRecordIsVisibleToAUserScopedCallerInTheSameWorkspace() {
    final MemoryScope record = MemoryScope.ofTenant(TENANT);
    final MemoryScope caller = MemoryScope.ofUser(TENANT, ALICE);
    assertThat(record.visibleTo(caller)).isTrue();
  }

  @Test
  void aRecordInOnePartitionIsInvisibleToACallerPinnedToAnother() {
    final MemoryScope record = MemoryScope.ofPartition(TENANT, ALICE, "session-1");
    final MemoryScope caller = MemoryScope.ofPartition(TENANT, ALICE, "session-2");
    assertThat(record.visibleTo(caller)).isFalse();
    assertThat(record.narrowTo(caller)).isEmpty();
  }

  @Test
  void aScopeIsAlwaysVisibleToItself() {
    for (final MemoryScope candidate :
        List.of(
            MemoryScope.ofTenant(TENANT),
            MemoryScope.ofUser(TENANT, ALICE),
            MemoryScope.ofPartition(TENANT, ALICE, "s-1"))) {
      assertThat(candidate.visibleTo(candidate)).isTrue();
    }
  }

  @Test
  void theChainRunsFromBroadestToNarrowest() {
    // Policy merges along this chain and each step may only tighten, so a chain in the wrong order
    // would let a narrow scope loosen a broad one.
    final MemoryScope deep =
        new MemoryScope(new TenantScope("acme", "core", "ws-a", null), Optional.of(ALICE), "s-1");
    final List<MemoryScope> chain = deep.chain();
    assertThat(chain).hasSize(4);
    assertThat(chain.get(0).workspace()).isEmpty();
    assertThat(chain.get(1).workspace()).contains("ws-a");
    assertThat(chain.get(2).owner()).contains(ALICE);
    assertThat(chain.get(3).partition()).isEqualTo("s-1");
  }

  @Test
  void aTenantWideScopeHasAChainOfOne() {
    assertThat(MemoryScope.ofTenant(TENANT).chain()).hasSize(1);
  }

  @Test
  void theChainIsUnmodifiable() {
    assertThatThrownBy(() -> MemoryScope.ofTenant(TENANT).chain().add(MemoryScope.ofTenant(TENANT)))
        .isInstanceOf(UnsupportedOperationException.class);
  }

  @Test
  void theScopeKeyDistinguishesEveryDimension() {
    // Two scopes that differ anywhere must key differently, or an adapter partitioned by key would
    // merge them — which for the tenant dimension would be a cross-tenant leak.
    final List<MemoryScope> distinct =
        List.of(
            MemoryScope.ofTenant(TENANT),
            MemoryScope.ofTenant(OTHER_TENANT),
            scope("acme", "core", "ws-a", null, null, null),
            scope("acme", "core", "ws-b", null, null, null),
            MemoryScope.ofUser(TENANT, ALICE),
            MemoryScope.ofUser(TENANT, BOB),
            MemoryScope.ofPartition(TENANT, ALICE, "s-1"),
            MemoryScope.ofPartition(TENANT, ALICE, "s-2"));
    assertThat(distinct.stream().map(MemoryScope::key)).doesNotHaveDuplicates();
  }

  @Test
  void theScopeKeyCannotBeConfusedByValuesThatConcatenateAlike() {
    // Length-prefixed for this reason: "ab" + "c" and "a" + "bc" must not produce the same key.
    final MemoryScope left = scope("ab", "c", null, null, null, null);
    final MemoryScope right = scope("a", "bc", null, null, null, null);
    assertThat(left.key()).isNotEqualTo(right.key());
  }

  @Test
  void aScopeIsStableAcrossConstructions() {
    assertThat(MemoryScope.ofUser(TENANT, ALICE).key())
        .isEqualTo(MemoryScope.ofUser(TENANT, ALICE).key());
  }

  @Test
  void aPartialScopeIsUnrepresentable() {
    assertThatThrownBy(() -> new MemoryScope(null, Optional.empty(), ""))
        .isInstanceOf(NullPointerException.class);
    assertThatThrownBy(() -> new MemoryScope(TENANT, null, ""))
        .isInstanceOf(NullPointerException.class);
    assertThatThrownBy(() -> new MemoryScope(TENANT, Optional.empty(), null))
        .isInstanceOf(NullPointerException.class);
  }

  @Test
  void aTenantScopeWithNoTenantIsUnrepresentable() {
    assertThatThrownBy(() -> TenantScope.of("acme", ""))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void aBlankPartitionIsRefusedByTheFactoryThatRequiresOne() {
    assertThatThrownBy(() -> MemoryScope.ofPartition(TENANT, ALICE, " "))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void aCallerCanNarrowThroughItsOwnHelper() {
    final MemoryCaller caller = MemoryFixturesBridge.callerFor(MemoryScope.ofUser(TENANT, ALICE));
    assertThat(caller.narrow(MemoryScope.ofTenant(TENANT))).isPresent();
    assertThat(caller.narrow(MemoryScope.ofTenant(OTHER_TENANT))).isEmpty();
  }

  /** Keeps the fixtures import out of a test that is otherwise purely about the api package. */
  private static final class MemoryFixturesBridge {
    static MemoryCaller callerFor(final MemoryScope scope) {
      return io.reliabilityai.gateway.dataplane.memory.MemoryFixtures.callerFor(scope);
    }
  }
}

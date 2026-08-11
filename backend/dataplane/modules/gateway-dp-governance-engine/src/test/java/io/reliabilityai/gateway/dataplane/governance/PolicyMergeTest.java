package io.reliabilityai.gateway.dataplane.governance;

import static io.reliabilityai.gateway.dataplane.governance.PolicyFixture.ORG_REF;
import static io.reliabilityai.gateway.dataplane.governance.PolicyFixture.PROJECT_REF;
import static io.reliabilityai.gateway.dataplane.governance.PolicyFixture.resolved;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.reliabilityai.gateway.dataplane.governance.api.EnforcementLevel;
import io.reliabilityai.gateway.dataplane.governance.api.PolicyKind;
import io.reliabilityai.gateway.dataplane.governance.api.PolicyScope;
import io.reliabilityai.gateway.dataplane.governance.api.PolicyType;
import io.reliabilityai.gateway.dataplane.governance.api.PolicyValue;
import io.reliabilityai.gateway.dataplane.governance.domain.PolicyMerge;
import io.reliabilityai.gateway.dataplane.governance.domain.ResolvedRule;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/**
 * Tests the merge algebra. The interesting assertions are not the individual combinators but the
 * four algebraic properties — idempotence, commutativity, associativity and non-loosening — because
 * those are what make the hierarchy unambiguous regardless of how a chain is shaped.
 */
class PolicyMergeTest {

  private static final PolicyValue.TimeWindow MORNING =
      new PolicyValue.TimeWindow(
          Instant.parse("2026-03-01T06:00:00Z"), Instant.parse("2026-03-01T08:00:00Z"));
  private static final PolicyValue.TimeWindow EVENING =
      new PolicyValue.TimeWindow(
          Instant.parse("2026-03-01T20:00:00Z"), Instant.parse("2026-03-01T22:00:00Z"));

  @Test
  void allowListsIntersectSoAChildCanOnlyNarrow() {
    final PolicyValue merged =
        PolicyMerge.mergeValues(
            PolicyKind.ALLOW_LIST,
            PolicyValue.Values.of("a", "b", "c"),
            PolicyValue.Values.of("b", "c", "d"));

    assertThat(((PolicyValue.Values) merged).values()).containsExactly("b", "c");
  }

  @Test
  void allowListIntersectionCanEmptyTheSetEntirely() {
    final PolicyValue merged =
        PolicyMerge.mergeValues(
            PolicyKind.ALLOW_LIST, PolicyValue.Values.of("a"), PolicyValue.Values.of("b"));

    // Two scopes permitting disjoint sets permit nothing. That is the correct, if blunt, answer.
    assertThat(((PolicyValue.Values) merged).values()).isEmpty();
  }

  @Test
  void aChildCannotReAddSomethingItsParentExcluded() {
    final PolicyValue merged =
        PolicyMerge.mergeValues(
            PolicyKind.ALLOW_LIST, PolicyValue.Values.of("a"), PolicyValue.Values.of("a", "b"));

    assertThat(((PolicyValue.Values) merged).values()).containsExactly("a");
  }

  @Test
  void denyListsUnionSoADenialAnywhereSticks() {
    final PolicyValue merged =
        PolicyMerge.mergeValues(
            PolicyKind.DENY_LIST, PolicyValue.Values.of("a"), PolicyValue.Values.of("b"));

    assertThat(((PolicyValue.Values) merged).values()).containsExactly("a", "b");
  }

  @Test
  void capabilityOffAtAParentCannotBeTurnedBackOn() {
    final PolicyValue merged =
        PolicyMerge.mergeValues(
            PolicyKind.CAPABILITY, PolicyValue.Flag.FALSE, PolicyValue.Flag.TRUE);

    assertThat(((PolicyValue.Flag) merged).value()).isFalse();
  }

  @Test
  void capabilityStaysOnOnlyWhenEveryScopeAgrees() {
    final PolicyValue merged =
        PolicyMerge.mergeValues(
            PolicyKind.CAPABILITY, PolicyValue.Flag.TRUE, PolicyValue.Flag.TRUE);

    assertThat(((PolicyValue.Flag) merged).value()).isTrue();
  }

  @Test
  void requirementImposedAnywhereAppliesEverywhereBelow() {
    final PolicyValue merged =
        PolicyMerge.mergeValues(
            PolicyKind.REQUIREMENT, PolicyValue.Flag.TRUE, PolicyValue.Flag.FALSE);

    assertThat(((PolicyValue.Flag) merged).value()).isTrue();
  }

  @Test
  void prohibitionPulledAnywhereCannotBeUnpulled() {
    final PolicyValue merged =
        PolicyMerge.mergeValues(
            PolicyKind.PROHIBITION, PolicyValue.Flag.TRUE, PolicyValue.Flag.FALSE);

    assertThat(((PolicyValue.Flag) merged).value()).isTrue();
  }

  @Test
  void ceilingsTakeTheTightestBound() {
    final PolicyValue merged =
        PolicyMerge.mergeValues(
            PolicyKind.CEILING, PolicyValue.Limit.of(100), PolicyValue.Limit.of(40));

    assertThat(((PolicyValue.Limit) merged).value()).isEqualTo(40L);
  }

  @Test
  void aChildCannotRaiseACeilingItsParentLowered() {
    final PolicyValue merged =
        PolicyMerge.mergeValues(
            PolicyKind.CEILING, PolicyValue.Limit.of(10), PolicyValue.Limit.of(999));

    assertThat(((PolicyValue.Limit) merged).value()).isEqualTo(10L);
  }

  @Test
  void blockedWindowsUnion() {
    final PolicyValue merged =
        PolicyMerge.mergeValues(
            PolicyKind.WINDOW, PolicyValue.Windows.of(MORNING), PolicyValue.Windows.of(EVENING));

    assertThat(((PolicyValue.Windows) merged).windows()).containsExactly(MORNING, EVENING);
  }

  @Test
  void mergingAValueWithItselfChangesNothing() {
    for (final PolicyKind kind : PolicyKind.values()) {
      final PolicyValue value = sampleFor(kind);
      assertThat(PolicyMerge.mergeValues(kind, value, value))
          .as("idempotence for %s", kind)
          .isEqualTo(value);
    }
  }

  @ParameterizedTest
  @EnumSource(PolicyKind.class)
  void mergeIsCommutative(final PolicyKind kind) {
    final PolicyValue left = sampleFor(kind);
    final PolicyValue right = otherFor(kind);

    assertThat(PolicyMerge.mergeValues(kind, left, right))
        .isEqualTo(PolicyMerge.mergeValues(kind, right, left));
  }

  @ParameterizedTest
  @EnumSource(PolicyKind.class)
  void mergeIsAssociative(final PolicyKind kind) {
    final PolicyValue a = sampleFor(kind);
    final PolicyValue b = otherFor(kind);
    final PolicyValue c = thirdFor(kind);

    final PolicyValue leftFirst =
        PolicyMerge.mergeValues(kind, PolicyMerge.mergeValues(kind, a, b), c);
    final PolicyValue rightFirst =
        PolicyMerge.mergeValues(kind, a, PolicyMerge.mergeValues(kind, b, c));

    assertThat(leftFirst).isEqualTo(rightFirst);
  }

  @Test
  void enforcementMergesToTheStricterLevel() {
    assertThat(EnforcementLevel.SHADOW.strictest(EnforcementLevel.ADVISORY))
        .isEqualTo(EnforcementLevel.ADVISORY);
    assertThat(EnforcementLevel.ADVISORY.strictest(EnforcementLevel.MANDATORY))
        .isEqualTo(EnforcementLevel.MANDATORY);
    assertThat(EnforcementLevel.MANDATORY.strictest(EnforcementLevel.SHADOW))
        .isEqualTo(EnforcementLevel.MANDATORY);
  }

  @Test
  void aChildCannotDowngradeAnInheritedMandatoryRuleToShadow() {
    final ResolvedRule parent =
        resolved(
            ORG_REF,
            "org-1",
            PolicyType.MAX_OUTPUT_TOKENS,
            PolicyValue.Limit.of(100),
            EnforcementLevel.MANDATORY);
    final ResolvedRule child =
        resolved(
            PROJECT_REF,
            "proj-1",
            PolicyType.MAX_OUTPUT_TOKENS,
            PolicyValue.Limit.of(100),
            EnforcementLevel.SHADOW);

    // The single most important line in this file: write access to a leaf scope must not be enough
    // to
    // neutralise an inherited control by re-declaring it in shadow.
    assertThat(PolicyMerge.merge(parent, child).enforcement())
        .isEqualTo(EnforcementLevel.MANDATORY);
  }

  @Test
  void aMergedValueIsAttributedToTheScopeThatNarrowedIt() {
    final ResolvedRule parent =
        resolved(ORG_REF, "org-1", PolicyType.MAX_CONTEXT, PolicyValue.Limit.of(1000));
    final ResolvedRule child =
        resolved(PROJECT_REF, "proj-1", PolicyType.MAX_CONTEXT, PolicyValue.Limit.of(10));

    final ResolvedRule merged = PolicyMerge.merge(parent, child);

    assertThat(merged.source().scope()).isEqualTo(PolicyScope.PROJECT);
    assertThat(merged.ruleId()).isEqualTo("proj-1");
  }

  @Test
  void anUnchangedValueStaysAttributedToTheScopeThatSetIt() {
    final ResolvedRule parent =
        resolved(ORG_REF, "org-1", PolicyType.MAX_CONTEXT, PolicyValue.Limit.of(10));
    final ResolvedRule child =
        resolved(PROJECT_REF, "proj-1", PolicyType.MAX_CONTEXT, PolicyValue.Limit.of(1000));

    final ResolvedRule merged = PolicyMerge.merge(parent, child);

    assertThat(merged.source().scope()).isEqualTo(PolicyScope.ORGANIZATION);
    assertThat(merged.ruleId()).isEqualTo("org-1");
  }

  @Test
  void mergingDifferentPolicyTypesIsRefused() {
    final ResolvedRule left =
        resolved(ORG_REF, "a", PolicyType.MAX_CONTEXT, PolicyValue.Limit.of(1));
    final ResolvedRule right = resolved(ORG_REF, "b", PolicyType.MAX_COST, PolicyValue.Limit.of(1));

    assertThatThrownBy(() -> PolicyMerge.merge(left, right))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @ParameterizedTest
  @EnumSource(PolicyType.class)
  void everyPolicyTypeHasAMergeableKind(final PolicyType type) {
    final PolicyValue value = sampleFor(type.kind());

    assertThat(value.supports(type.kind())).isTrue();
    assertThat(PolicyMerge.mergeValues(type.kind(), value, value)).isEqualTo(value);
  }

  private static PolicyValue sampleFor(final PolicyKind kind) {
    return switch (kind) {
      case ALLOW_LIST, DENY_LIST -> PolicyValue.Values.of("a", "b");
      case CAPABILITY, REQUIREMENT, PROHIBITION -> PolicyValue.Flag.TRUE;
      case CEILING -> PolicyValue.Limit.of(50);
      case WINDOW -> PolicyValue.Windows.of(MORNING);
    };
  }

  private static PolicyValue otherFor(final PolicyKind kind) {
    return switch (kind) {
      case ALLOW_LIST, DENY_LIST -> PolicyValue.Values.of("b", "c");
      case CAPABILITY, REQUIREMENT, PROHIBITION -> PolicyValue.Flag.FALSE;
      case CEILING -> PolicyValue.Limit.of(20);
      case WINDOW -> PolicyValue.Windows.of(EVENING);
    };
  }

  private static PolicyValue thirdFor(final PolicyKind kind) {
    return switch (kind) {
      case ALLOW_LIST, DENY_LIST -> PolicyValue.Values.of("b");
      case CAPABILITY, REQUIREMENT, PROHIBITION -> PolicyValue.Flag.TRUE;
      case CEILING -> PolicyValue.Limit.of(35);
      case WINDOW -> PolicyValue.Windows.of(MORNING, EVENING);
    };
  }
}

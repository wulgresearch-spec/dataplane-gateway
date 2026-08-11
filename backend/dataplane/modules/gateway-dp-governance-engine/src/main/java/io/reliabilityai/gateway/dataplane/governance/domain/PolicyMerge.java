package io.reliabilityai.gateway.dataplane.governance.domain;

import io.reliabilityai.gateway.common.Preconditions;
import io.reliabilityai.gateway.dataplane.governance.api.EnforcementLevel;
import io.reliabilityai.gateway.dataplane.governance.api.PolicyKind;
import io.reliabilityai.gateway.dataplane.governance.api.PolicyRule;
import io.reliabilityai.gateway.dataplane.governance.api.PolicyValue;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

/**
 * The hierarchy merge: how a statement inherited from a parent scope combines with one authored at
 * a child scope (Doc 21 GV-D2, "most restrictive wins").
 *
 * <p><b>The property this file exists to guarantee.</b> Every combinator below is idempotent,
 * commutative, associative, and monotonically non-loosening. Those four properties make the merge a
 * <em>meet</em> on a semilattice of policies, and that in turn makes the effective policy for a
 * scope chain a plain fold whose result cannot depend on the order the chain was walked, on which
 * intermediate scopes happened to be populated, or on a scope appearing twice. "No ambiguity" stops
 * being an aspiration in a design document and becomes an algebraic fact a property test can
 * hammer.
 *
 * <p>The consequence worth stating plainly: <b>a child can only ever tighten.</b> There is no
 * combinator here through which a project could re-permit a model its organization denied, raise a
 * ceiling its parent lowered, or downgrade an inherited mandatory rule to advisory. That is not
 * enforced by convention or review — it is the only thing these functions can compute.
 *
 * <p>Enforcement level merges independently of value, always to the stricter of the two. Without
 * that rule, write access to any leaf scope would be enough to neutralise every inherited control
 * by re-declaring it in shadow.
 */
public final class PolicyMerge {

  private PolicyMerge() {}

  /**
   * Combines an inherited statement with one from a more specific scope.
   *
   * @param parent the statement inherited from the less specific scope
   * @param child the statement authored at the more specific scope
   * @return the merged statement, attributed to whichever scope its value came from
   */
  public static ResolvedRule merge(final ResolvedRule parent, final ResolvedRule child) {
    Preconditions.requireNonNull(parent, "parent");
    Preconditions.requireNonNull(child, "child");
    if (parent.type() != child.type()) {
      throw new IllegalArgumentException("cannot merge " + parent.type() + " with " + child.type());
    }
    final PolicyValue merged = mergeValues(parent.type().kind(), parent.value(), child.value());
    final EnforcementLevel level = parent.enforcement().strictest(child.enforcement());

    // Attribute to the parent only when the parent's value survived unchanged. Anywhere the child
    // narrowed — or where intersecting produced something neither scope authored alone — the more
    // specific scope is the honest place to point an operator.
    final boolean parentSurvived = merged.equals(parent.value());
    final ResolvedRule attributed = parentSurvived ? parent : child;
    return new ResolvedRule(
        new PolicyRule(attributed.ruleId(), parent.type(), merged, level), attributed.source());
  }

  /**
   * Combines two values of the same kind.
   *
   * @param kind the value shape, which selects the combinator
   * @param left one value
   * @param right the other value
   * @return the more restrictive combination
   */
  public static PolicyValue mergeValues(
      final PolicyKind kind, final PolicyValue left, final PolicyValue right) {
    return switch (kind) {
      case ALLOW_LIST -> intersect(values(left), values(right));
      case DENY_LIST -> union(values(left), values(right));
      case CAPABILITY -> PolicyValue.Flag.of(flag(left) && flag(right));
      case REQUIREMENT, PROHIBITION -> PolicyValue.Flag.of(flag(left) || flag(right));
      case CEILING -> PolicyValue.Limit.of(Math.min(limit(left), limit(right)));
      case WINDOW -> unionWindows(windows(left), windows(right));
    };
  }

  /** Intersection — a child scope may only narrow what its parent permitted. */
  private static PolicyValue intersect(
      final PolicyValue.Values left, final PolicyValue.Values right) {
    final Set<String> kept = new TreeSet<>(left.values());
    kept.retainAll(right.values());
    return new PolicyValue.Values(List.copyOf(kept));
  }

  /** Union — a denial added anywhere in the chain sticks. */
  private static PolicyValue union(final PolicyValue.Values left, final PolicyValue.Values right) {
    final Set<String> all = new TreeSet<>(left.values());
    all.addAll(right.values());
    return new PolicyValue.Values(List.copyOf(all));
  }

  /**
   * Union of blocked intervals. Deliberately not coalesced into minimal form: two adjacent windows
   * and one merged window block exactly the same instants, so coalescing would buy nothing at
   * evaluation time while costing an interval-arithmetic routine that could be wrong.
   */
  private static PolicyValue unionWindows(
      final PolicyValue.Windows left, final PolicyValue.Windows right) {
    final Set<PolicyValue.TimeWindow> all = new LinkedHashSet<>(left.windows());
    all.addAll(right.windows());
    return new PolicyValue.Windows(new ArrayList<>(all));
  }

  private static PolicyValue.Values values(final PolicyValue value) {
    return (PolicyValue.Values) value;
  }

  private static boolean flag(final PolicyValue value) {
    return ((PolicyValue.Flag) value).value();
  }

  private static long limit(final PolicyValue value) {
    return ((PolicyValue.Limit) value).value();
  }

  private static PolicyValue.Windows windows(final PolicyValue value) {
    return (PolicyValue.Windows) value;
  }
}

package io.reliabilityai.gateway.dataplane.governance.api;

import io.reliabilityai.gateway.common.Preconditions;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * One authored policy document, attached to one node of the hierarchy (Doc 21 §6/§8).
 *
 * <p>This is the unit the control plane authors, versions and distributes; the engine only ever
 * consumes it (Doc 21 GV-A12). It is deliberately dumb — a scope, a version, and a list of
 * statements — because every piece of intelligence about how documents combine belongs in the
 * merge, where it can be reasoned about once rather than re-implemented per document.
 *
 * <p>A document may declare each {@link PolicyType} <b>at most once</b>. Allowing two statements
 * about the same type inside one document would need an intra-document conflict rule on top of the
 * inter-scope merge rule, and two conflict rules is one more than a governance system can have and
 * still be explainable. The constructor rejects it rather than silently picking one.
 *
 * @param policyId the stable control-plane identifier for this document
 * @param scope the hierarchy node this document is attached to
 * @param version the generation this document belongs to
 * @param rules the statements, sorted into evaluation order, at most one per type
 * @param enabled whether the document participates in evaluation at all
 */
public record GovernancePolicy(
    String policyId,
    PolicyScopeRef scope,
    PolicyVersion version,
    List<PolicyRule> rules,
    boolean enabled) {

  /** Validates the document and rejects a duplicated policy type. */
  public GovernancePolicy {
    Preconditions.requireNonBlank(policyId, "policyId");
    Preconditions.requireNonNull(scope, "scope");
    Preconditions.requireNonNull(version, "version");
    Preconditions.requireNonNull(rules, "rules");
    final Set<PolicyType> seen = new HashSet<>();
    for (final PolicyRule rule : rules) {
      Preconditions.requireNonNull(rule, "rule");
      if (!seen.add(rule.type())) {
        throw new IllegalArgumentException("duplicate policy type in document: " + rule.type());
      }
    }
    final List<PolicyRule> ordered = new ArrayList<>(rules);
    ordered.sort(Comparator.comparing(PolicyRule::type));
    rules = List.copyOf(ordered);
  }

  /**
   * Creates an enabled document.
   *
   * @param policyId the stable identifier
   * @param scope the hierarchy node
   * @param version the generation
   * @param rules the statements
   * @return the policy document
   */
  public static GovernancePolicy of(
      final String policyId,
      final PolicyScopeRef scope,
      final PolicyVersion version,
      final List<PolicyRule> rules) {
    return new GovernancePolicy(policyId, scope, version, rules, true);
  }
}

package io.reliabilityai.gateway.dataplane.governance.domain;

import io.reliabilityai.gateway.common.Preconditions;
import io.reliabilityai.gateway.dataplane.governance.api.EnforcementLevel;
import io.reliabilityai.gateway.dataplane.governance.api.PolicyRule;
import io.reliabilityai.gateway.dataplane.governance.api.PolicyScopeRef;
import io.reliabilityai.gateway.dataplane.governance.api.PolicyType;
import io.reliabilityai.gateway.dataplane.governance.api.PolicyValue;

/**
 * A rule together with the hierarchy node it is attributed to after merging.
 *
 * <p>The attribution is what turns an inherited denial into something an operator can act on. Being
 * told a request was refused by a model allow-list is a shrug; being told the binding statement is
 * {@code org-policy-7} at the <em>organization</em> node points at the exact document to open.
 * Keeping the full {@link PolicyScopeRef} rather than just the scope also gives the evaluator the
 * key it needs to read consumption counters at the right node — a project's per-minute cap must be
 * measured against the project's counter, not its parent's.
 *
 * @param rule the merged statement
 * @param source the node the merged value is attributed to
 */
public record ResolvedRule(PolicyRule rule, PolicyScopeRef source) {

  /** Validates the resolved rule. */
  public ResolvedRule {
    Preconditions.requireNonNull(rule, "rule");
    Preconditions.requireNonNull(source, "source");
  }

  /**
   * The type of the underlying statement.
   *
   * @return the policy type
   */
  public PolicyType type() {
    return rule.type();
  }

  /**
   * The merged value.
   *
   * @return the policy value
   */
  public PolicyValue value() {
    return rule.value();
  }

  /**
   * How hard the merged statement bites.
   *
   * @return the enforcement level
   */
  public EnforcementLevel enforcement() {
    return rule.enforcement();
  }

  /**
   * The stable identifier of the statement the merged value is attributed to.
   *
   * @return the rule id
   */
  public String ruleId() {
    return rule.ruleId();
  }
}

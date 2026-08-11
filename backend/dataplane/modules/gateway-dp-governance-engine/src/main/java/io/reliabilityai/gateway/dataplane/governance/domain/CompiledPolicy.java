package io.reliabilityai.gateway.dataplane.governance.domain;

import io.reliabilityai.gateway.common.Preconditions;
import io.reliabilityai.gateway.dataplane.governance.api.GovernancePolicy;
import io.reliabilityai.gateway.dataplane.governance.api.PolicyRule;
import io.reliabilityai.gateway.dataplane.governance.api.PolicyScopeRef;
import io.reliabilityai.gateway.dataplane.governance.api.PolicyType;

/**
 * One authored document, indexed for evaluation.
 *
 * <p>The whole content of this class is a change of data structure: the authored form is a list of
 * statements, and evaluating it as a list would mean a scan per policy type per scope per request.
 * Here it becomes an array indexed by {@link PolicyType#ordinal()}, so "what does this document say
 * about output-token ceilings" is a single array read with no comparison, no hashing and no
 * allocation.
 *
 * <p>This is the "compile once, evaluate many" step, and it is the reason there is no parsing,
 * reflection or expression evaluation anywhere on the request path. Everything expensive about a
 * policy document happens exactly once, when it is installed.
 *
 * <p>Deeply immutable after construction and shared freely across threads: the backing array is
 * private, never handed out, and never written to after the constructor returns.
 */
public final class CompiledPolicy {

  private static final PolicyType[] TYPES = PolicyType.values();

  private final PolicyScopeRef scope;
  private final PolicyRule[] byType;

  private CompiledPolicy(final PolicyScopeRef scope, final PolicyRule[] byType) {
    this.scope = scope;
    this.byType = byType;
  }

  /**
   * Indexes an authored document.
   *
   * @param policy the authored document
   * @return the compiled form
   */
  public static CompiledPolicy of(final GovernancePolicy policy) {
    Preconditions.requireNonNull(policy, "policy");
    final PolicyRule[] index = new PolicyRule[TYPES.length];
    for (final PolicyRule rule : policy.rules()) {
      index[rule.type().ordinal()] = rule;
    }
    return new CompiledPolicy(policy.scope(), index);
  }

  /**
   * The node this document is attached to.
   *
   * @return the scope reference
   */
  public PolicyScopeRef scope() {
    return scope;
  }

  /**
   * The statement this document makes about one policy type.
   *
   * @param type the policy type
   * @return the statement, or {@code null} when the document is silent on it
   */
  public PolicyRule ruleFor(final PolicyType type) {
    return byType[type.ordinal()];
  }
}

package io.reliabilityai.gateway.dataplane.governance.internal;

import io.reliabilityai.gateway.common.Preconditions;
import io.reliabilityai.gateway.dataplane.governance.api.EnforcementLevel;
import io.reliabilityai.gateway.dataplane.governance.api.GovernancePolicy;
import io.reliabilityai.gateway.dataplane.governance.api.PolicyCompilationException;
import io.reliabilityai.gateway.dataplane.governance.api.PolicyRule;
import io.reliabilityai.gateway.dataplane.governance.api.PolicyScopeRef;
import io.reliabilityai.gateway.dataplane.governance.api.PolicySourcePort;
import io.reliabilityai.gateway.dataplane.governance.domain.CompiledPolicy;
import io.reliabilityai.gateway.dataplane.governance.domain.PolicySnapshot;
import java.util.HashMap;
import java.util.Map;

/**
 * Turns an authored policy bundle into an immutable, evaluation-ready snapshot.
 *
 * <p><b>This is a translator, not an author</b> (Doc 21 GV-A12). It indexes what the control plane
 * published and validates that it is internally coherent. It never invents a rule, never supplies a
 * default for a policy nobody wrote, and never widens anything — a bundle that says nothing about
 * models compiles to a snapshot that says nothing about models, not to one that permits them all.
 *
 * <p><b>Where the invariants are actually enforced.</b> Two checks here are load-bearing, and both
 * happen at install time so a bad bundle is rejected while the previous good one keeps serving:
 *
 * <ul>
 *   <li><b>Hard tiers must be mandatory.</b> A security, tenant, compliance, residency or
 *       authorization rule authored as advisory or shadow is refused outright. This is the single
 *       point at which "a feature flag can never turn off compliance" stops being a documentation
 *       promise: there is no way to express the dangerous thing, so no evaluation path needs to
 *       defend against it (Doc 21 GV-A5/A6/A7/A19).
 *   <li><b>One document per node.</b> Two documents at the same scope would have to be combined by
 *       some rule, and any such rule is a second conflict-resolution mechanism competing with the
 *       hierarchy merge. Refusing is clearer than choosing.
 * </ul>
 *
 * <p>Compilation is the expensive step and happens exactly once per generation, off the request
 * path. Nothing it produces requires parsing, reflection or evaluation of a script at admission
 * time.
 */
public final class PolicyCompiler {

  /**
   * Compiles a bundle.
   *
   * @param bundle the authored generation
   * @return the immutable compiled snapshot
   * @throws PolicyCompilationException when the bundle is incoherent or violates a hard-tier rule
   */
  public PolicySnapshot compile(final PolicySourcePort.PolicyBundle bundle) {
    Preconditions.requireNonNull(bundle, "bundle");
    final Map<PolicyScopeRef, CompiledPolicy> byScope = new HashMap<>();
    for (final GovernancePolicy policy : bundle.policies()) {
      if (!policy.enabled()) {
        continue;
      }
      validate(policy);
      final CompiledPolicy compiled = CompiledPolicy.of(policy);
      if (byScope.putIfAbsent(policy.scope(), compiled) != null) {
        throw new PolicyCompilationException(
            "two policy documents attached to the same node: " + policy.scope());
      }
    }
    return PolicySnapshot.of(bundle.version(), byScope);
  }

  private static void validate(final GovernancePolicy policy) {
    for (final PolicyRule rule : policy.rules()) {
      if (rule.type().requiresMandatoryEnforcement()
          && rule.enforcement() != EnforcementLevel.MANDATORY) {
        throw new PolicyCompilationException(
            "rule "
                + rule.ruleId()
                + " sets "
                + rule.type()
                + " to "
                + rule.enforcement()
                + ", but the "
                + rule.type().domain()
                + " tier is non-demotable and admits only MANDATORY");
      }
    }
  }
}

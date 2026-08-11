package io.reliabilityai.gateway.dataplane.governance.api;

/**
 * The admission seam the request pipeline calls between authentication and routing — the
 * full-fidelity successor to {@code GovernancePort.authorize} (Doc 21 §7, GV-D1).
 *
 * <p><b>Why this exists.</b> The older port carries request, principal and tenant context and
 * nothing else. That is enough to enforce kill switches, suspension, maintenance windows, residency
 * and consumption caps, and structurally incapable of enforcing anything about what the request
 * actually asks for — which model, which tools, how many tokens, how much spend. Those policies
 * were implemented and tested but unreachable from live traffic, because there was no field in
 * which the pipeline could hand governance the facts they compare against. This port takes the
 * whole question.
 *
 * <p><b>Contract.</b> Total: every call returns a decision. It does not throw, does not return
 * null, and does not distinguish "no policy applies" from "I could not evaluate" — the latter is a
 * refusal (Doc 21 §42). A caller may therefore branch on the verdict alone and cannot accidentally
 * proceed through an exception path.
 *
 * <p>The pipeline holds this port, not the engine, so a deployment can run the hierarchical engine
 * or the legacy evaluator behind an adapter without the pipeline knowing which.
 */
@FunctionalInterface
public interface GovernanceAdmissionPort {

  /**
   * Decides whether a request is admitted.
   *
   * @param request the full admission question
   * @return the decision — never null, and this method never throws
   */
  PolicyDecision admit(PolicyRequest request);
}

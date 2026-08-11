package io.reliabilityai.gateway.dataplane.governance.api;

import io.reliabilityai.gateway.common.ContentFree;
import io.reliabilityai.gateway.common.Preconditions;

/**
 * A record of one rule that the request failed (Doc 21 §21 explainability).
 *
 * <p>{@link ContentFree} by construction. It names <em>which</em> statement refused and
 * <em>where</em> in the hierarchy that statement came from, and stops there. It never carries the
 * offending value — not the model the caller asked for, not the region, not the token count —
 * because a violation record travels into audit, into telemetry, and (as a coarse code) toward the
 * caller, and a field holding "the caller asked for model X" is a field that leaks the tenant's
 * model inventory to anyone who can read a log (Doc 21 §25, Doc 14 §7.1).
 *
 * <p>{@code boundAt} is the diagnostic that makes an inherited denial actionable: being told a
 * request was refused by a model allow-list is much less useful than being told the binding
 * statement came from the <em>organization</em> scope, which tells an operator exactly which
 * document to go and look at.
 *
 * @param type the policy type that was violated
 * @param boundAt the hierarchy level that contributed the binding statement
 * @param ruleId the stable identifier of the violated statement
 * @param enforcement how hard the violated statement bites
 * @param reason the neutral binding-domain reason
 */
public record PolicyViolation(
    PolicyType type,
    PolicyScope boundAt,
    String ruleId,
    EnforcementLevel enforcement,
    DenialReason reason)
    implements ContentFree {

  /** Validates the violation. */
  public PolicyViolation {
    Preconditions.requireNonNull(type, "type");
    Preconditions.requireNonNull(boundAt, "boundAt");
    Preconditions.requireNonBlank(ruleId, "ruleId");
    Preconditions.requireNonNull(enforcement, "enforcement");
    Preconditions.requireNonNull(reason, "reason");
  }

  /**
   * The verdict this violation contributes.
   *
   * @return the verdict implied by the violated statement's enforcement level
   */
  public Verdict verdict() {
    return enforcement.verdict();
  }
}

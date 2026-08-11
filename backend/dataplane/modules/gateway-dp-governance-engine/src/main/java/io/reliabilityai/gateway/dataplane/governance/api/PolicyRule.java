package io.reliabilityai.gateway.dataplane.governance.api;

import io.reliabilityai.gateway.common.Preconditions;

/**
 * One authored statement of policy: a {@link PolicyType}, the {@link PolicyValue} it is set to, and
 * how hard it bites (Doc 21 §6 {@code PolicyRule}).
 *
 * <p>The {@code ruleId} is a stable, control-plane-assigned identifier. It travels into every
 * violation and every audit record so an operator can point at the exact statement that refused a
 * request without the engine ever having to surface the rule's contents — which is the whole of the
 * Doc 21 §25 explainability split, expressed as one field.
 *
 * <p>The rule carries no free-text description. Anything an operator would write there is authoring
 * metadata, it would ride the hot path for no benefit, and it is exactly the sort of field that
 * ends up with a tenant name in it and turns an audit record into a leak (Doc 21 §24).
 *
 * @param ruleId the stable control-plane identifier for this statement
 * @param type what the statement is about
 * @param value what the statement says
 * @param enforcement how hard it bites when violated
 */
public record PolicyRule(
    String ruleId, PolicyType type, PolicyValue value, EnforcementLevel enforcement) {

  /** Validates the rule and checks the value's shape against the type's declared kind. */
  public PolicyRule {
    Preconditions.requireNonBlank(ruleId, "ruleId");
    Preconditions.requireNonNull(type, "type");
    Preconditions.requireNonNull(value, "value");
    Preconditions.requireNonNull(enforcement, "enforcement");
    if (!value.supports(type.kind())) {
      throw new IllegalArgumentException(
          "value shape does not match " + type + " (expected " + type.kind() + ")");
    }
  }

  /**
   * Creates a mandatory rule — the safe default, and the only level a hard tier accepts.
   *
   * @param ruleId the stable identifier
   * @param type what the rule is about
   * @param value what the rule says
   * @return the mandatory rule
   */
  public static PolicyRule mandatory(
      final String ruleId, final PolicyType type, final PolicyValue value) {
    return new PolicyRule(ruleId, type, value, EnforcementLevel.MANDATORY);
  }
}

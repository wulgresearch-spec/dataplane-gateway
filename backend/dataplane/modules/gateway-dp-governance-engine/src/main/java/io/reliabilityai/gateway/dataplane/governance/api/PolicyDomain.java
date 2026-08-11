package io.reliabilityai.gateway.dataplane.governance.api;

/**
 * The frozen precedence tier a policy type belongs to (Doc 21 GV-D2).
 *
 * <p>Declaration order is precedence order. Because the combinator is deny-overrides, the tier
 * never changes <em>whether</em> a request is refused — only which refusal is reported and how
 * early the evaluation stops. Reporting the highest-precedence binding violation is what makes a
 * denial stable: a caller fixing an authorization problem should not then be told about a budget
 * problem that was true all along.
 *
 * <p>The first five tiers are <b>hard</b>. A hard tier can never be enforced advisorily or in
 * shadow — that would turn an invariant into a suggestion — and {@code PolicyCompiler} refuses to
 * compile a document that tries (Doc 21 GV-A5/GV-A6/GV-A7/GV-A19).
 */
public enum PolicyDomain {

  /** Platform-level guards. Deny here overrides everything, including tenant contract. */
  SECURITY(true, DenialReason.SECURITY_DENIED),

  /** Tenant lifecycle and contractual state. */
  TENANT(true, DenialReason.TENANT_POLICY_DENIED),

  /** Regulatory regimes the execution path must satisfy (Doc 21 GV-D8). */
  COMPLIANCE(true, DenialReason.COMPLIANCE_CONFLICT),

  /** Permitted-region confinement (Doc 21 GV-D9, AD-014). */
  RESIDENCY(true, DenialReason.REGION_NOT_ALLOWED),

  /** What the principal may address: providers, models, tools (Doc 21 GV-D4/GV-D10). */
  AUTHORIZATION(true, DenialReason.UNAUTHORIZED),

  /** Feature gating — which request shapes are enabled (Doc 21 GV-D7). */
  CAPABILITY(false, DenialReason.FEATURE_DISABLED),

  /** Admission-time usage caps (Doc 21 GV-D5). */
  QUOTA(false, DenialReason.QUOTA_EXCEEDED),

  /** Admission-time spend caps (Doc 21 GV-D6). */
  BUDGET(false, DenialReason.BUDGET_EXCEEDED);

  private final boolean hard;
  private final DenialReason defaultReason;

  PolicyDomain(final boolean hard, final DenialReason defaultReason) {
    this.hard = hard;
    this.defaultReason = defaultReason;
  }

  /**
   * Whether this tier is non-demotable, so its rules must be enforced as mandatory.
   *
   * @return {@code true} for the security, tenant, compliance, residency and authorization tiers
   */
  public boolean isHard() {
    return hard;
  }

  /**
   * The neutral, externally-surfaced reason for a violation in this tier.
   *
   * @return the binding-domain denial reason
   */
  public DenialReason defaultReason() {
    return defaultReason;
  }
}

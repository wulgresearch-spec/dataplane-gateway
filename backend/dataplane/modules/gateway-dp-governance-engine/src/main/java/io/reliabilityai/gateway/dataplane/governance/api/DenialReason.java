package io.reliabilityai.gateway.dataplane.governance.api;

/**
 * The neutral, stable reason a request was denied, naming the <b>binding policy domain</b> (Doc 21
 * §22).
 *
 * <p>Reasons are deliberately coarse. A caller learns which domain refused, never which rule
 * matched, which models exist, or whether a tenant is present — that detail would turn the denial
 * path into an enumeration oracle for an attacker probing the gateway (Doc 12 §19, Doc 13). The
 * full evaluation detail goes to the audit sink instead, where it is useful and not reachable by
 * the caller.
 *
 * <p>Ordinal order matches the precedence hierarchy (GV-D2), so the highest-precedence binding
 * denial is the one reported.
 */
public enum DenialReason {

  /** Platform-level security guard refused (precedence 1). */
  SECURITY_DENIED("security-denied"),

  /** No policy snapshot could be resolved for the tenant — fail closed (Doc 21 §22, GV-D2). */
  POLICY_NOT_FOUND("policy-not-found"),

  /** The tenant exists in no policy snapshot, or its policy marks it disabled (precedence 2). */
  TENANT_DISABLED("tenant-disabled"),

  /** Tenant policy explicitly denies this request (precedence 2). */
  TENANT_POLICY_DENIED("tenant-policy-denied"),

  /** The request cannot be satisfied under its required compliance regime (precedence 3, GV-D8). */
  COMPLIANCE_CONFLICT("compliance-conflict"),

  /**
   * The requested region lies outside the tenant's permitted residency scope (precedence 4, GV-D9).
   */
  REGION_NOT_ALLOWED("region-not-allowed"),

  /** The requested model is not permitted for this tenant (precedence 5, GV-D4). */
  MODEL_NOT_ALLOWED("model-not-allowed"),

  /** The principal may not perform this action — RBAC/ABAC (precedence 5, GV-D4). */
  UNAUTHORIZED("unauthorized"),

  /** A requested tool or capability is not authorized for this tenant/principal (GV-D10). */
  TOOL_UNAUTHORIZED("tool-unauthorized"),

  /** A gated feature is not enabled for this tenant — absent flags fail safe to off (GV-D7). */
  FEATURE_DISABLED("feature-disabled"),

  /** Admission-time usage cap exceeded (precedence 7, GV-D5). */
  QUOTA_EXCEEDED("quota-exceeded"),

  /**
   * Admission-time request-rate policy exceeded. Distinct from the per-second throttle executed by
   * the Reliability Engine at invocation (Doc 20 §17); this is the admission gate's view (GV-D5).
   */
  RATE_LIMITED("rate-limited"),

  /** Admission-time spend cap would be exceeded (precedence 8, GV-D6). */
  BUDGET_EXCEEDED("budget-exceeded"),

  /**
   * Usage or entitlement state is stale beyond tolerance, so quota/budget cannot be enforced
   * honestly. Denying is the only safe answer — admitting would silently disable the spend ceiling
   * (GV-D5/GV-D6).
   */
  POLICY_UNAVAILABLE("policy-unavailable");

  private final String code;

  DenialReason(final String code) {
    this.code = code;
  }

  /**
   * The stable, content-free wire code for this reason.
   *
   * @return the reason code
   */
  public String code() {
    return code;
  }
}

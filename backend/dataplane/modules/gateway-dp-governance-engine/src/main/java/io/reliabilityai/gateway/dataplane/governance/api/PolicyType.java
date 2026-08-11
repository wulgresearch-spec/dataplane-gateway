package io.reliabilityai.gateway.dataplane.governance.api;

/**
 * The closed set of things governance can say about a request.
 *
 * <p>This enum is the engine's entire vocabulary, and that is the point. A closed vocabulary is
 * what lets the effective policy be an {@code EnumMap} — an array indexed by {@link #ordinal()} —
 * so resolving "what does policy say about output-token ceilings for this request" is an array read
 * rather than a map probe, a string comparison, or worse, an expression tree walk. It is also what
 * lets the merge be exhaustively checked: there are finitely many types, each with one declared
 * {@link PolicyKind}, so every merge path is reachable by a test.
 *
 * <p><b>Declaration order is evaluation order</b> (Doc 21 GV-D2): security first, budget last.
 * Because the combinator is deny-overrides the order cannot change the outcome, only which
 * violation is reported and how early a mandatory denial short-circuits. Reordering these constants
 * is therefore a behavioural change to denial reporting and is guarded by test.
 *
 * <p><b>Provider neutrality (GV-D11/GV-A9).</b> {@link #PROVIDER_ALLOW_LIST} and {@link
 * #PROVIDER_DENY_LIST} exist, but the engine still knows nothing about providers: the values are
 * opaque interned ids, compared only by set membership. There is no branch on a provider name
 * anywhere in this module, and the resolved provider constraint is handed to the Router to apply
 * exactly as residency and compliance scope already are (Doc 21 §36.1).
 */
public enum PolicyType {

  // ---- Tier 1: security ------------------------------------------------------------------------

  /** Refuse everything in scope. The break-glass control; nothing below can undo it. */
  EMERGENCY_KILL_SWITCH(
      PolicyDomain.SECURITY, PolicyKind.PROHIBITION, DenialReason.SECURITY_DENIED),

  // ---- Tier 2: tenant --------------------------------------------------------------------------

  /** Refuse everything for a suspended tenant. */
  TENANT_SUSPENSION(PolicyDomain.TENANT, PolicyKind.PROHIBITION, DenialReason.TENANT_DISABLED),

  /** Refuse during declared maintenance intervals. */
  MAINTENANCE_WINDOW(PolicyDomain.TENANT, PolicyKind.WINDOW, DenialReason.TENANT_POLICY_DENIED),

  // ---- Tier 3: compliance ----------------------------------------------------------------------

  /** Regimes this scope is attested for; a request may require only regimes present here. */
  COMPLIANCE_MODE(PolicyDomain.COMPLIANCE, PolicyKind.ALLOW_LIST, DenialReason.COMPLIANCE_CONFLICT),

  /** Refuse requests the caller classified as carrying personal data. */
  PII_RESTRICTION(
      PolicyDomain.COMPLIANCE, PolicyKind.PROHIBITION, DenialReason.COMPLIANCE_CONFLICT),

  // ---- Tier 4: residency -----------------------------------------------------------------------

  /** Regions the request may execute in (AD-014). */
  REGION_RESTRICTION(
      PolicyDomain.RESIDENCY, PolicyKind.ALLOW_LIST, DenialReason.REGION_NOT_ALLOWED),

  // ---- Tier 5: authorization -------------------------------------------------------------------

  /** Providers the request may be routed to. */
  PROVIDER_ALLOW_LIST(PolicyDomain.AUTHORIZATION, PolicyKind.ALLOW_LIST, DenialReason.UNAUTHORIZED),

  /** Providers the request may never be routed to. */
  PROVIDER_DENY_LIST(PolicyDomain.AUTHORIZATION, PolicyKind.DENY_LIST, DenialReason.UNAUTHORIZED),

  /** Canonical models the request may address. */
  MODEL_ALLOW_LIST(
      PolicyDomain.AUTHORIZATION, PolicyKind.ALLOW_LIST, DenialReason.MODEL_NOT_ALLOWED),

  /** Canonical models the request may never address. */
  MODEL_DENY_LIST(PolicyDomain.AUTHORIZATION, PolicyKind.DENY_LIST, DenialReason.MODEL_NOT_ALLOWED),

  /** Canonical tools the request may invoke (Doc 21 GV-D10). */
  TOOL_ALLOW_LIST(
      PolicyDomain.AUTHORIZATION, PolicyKind.ALLOW_LIST, DenialReason.TOOL_UNAUTHORIZED),

  /** Canonical tools the request may never invoke. */
  TOOL_DENY_LIST(PolicyDomain.AUTHORIZATION, PolicyKind.DENY_LIST, DenialReason.TOOL_UNAUTHORIZED),

  // ---- Tier 6: capability ----------------------------------------------------------------------

  /** Whether the request may stream. */
  STREAMING_ALLOWED(PolicyDomain.CAPABILITY, PolicyKind.CAPABILITY, DenialReason.FEATURE_DISABLED),

  /** Whether the request must stream — used to force incremental delivery for long generations. */
  STREAMING_REQUIRED(
      PolicyDomain.CAPABILITY, PolicyKind.REQUIREMENT, DenialReason.FEATURE_DISABLED),

  /** Whether the request must pin a response schema (Doc 17 SchemaLock). */
  JSON_MODE_REQUIRED(
      PolicyDomain.CAPABILITY, PolicyKind.REQUIREMENT, DenialReason.FEATURE_DISABLED),

  /** Whether extended reasoning may be requested. */
  REASONING_ALLOWED(PolicyDomain.CAPABILITY, PolicyKind.CAPABILITY, DenialReason.FEATURE_DISABLED),

  /** Whether image inputs may be sent. */
  VISION_ALLOWED(PolicyDomain.CAPABILITY, PolicyKind.CAPABILITY, DenialReason.FEATURE_DISABLED),

  /** Whether image generation may be requested. */
  IMAGE_GENERATION_ALLOWED(
      PolicyDomain.CAPABILITY, PolicyKind.CAPABILITY, DenialReason.FEATURE_DISABLED),

  /** Whether audio input or output may be requested. */
  AUDIO_ALLOWED(PolicyDomain.CAPABILITY, PolicyKind.CAPABILITY, DenialReason.FEATURE_DISABLED),

  /** Whether embedding requests are permitted. */
  EMBEDDING_ALLOWED(PolicyDomain.CAPABILITY, PolicyKind.CAPABILITY, DenialReason.FEATURE_DISABLED),

  /** Whether fine-tuning operations are permitted. */
  FINE_TUNING_ALLOWED(
      PolicyDomain.CAPABILITY, PolicyKind.CAPABILITY, DenialReason.FEATURE_DISABLED),

  /** Whether batch submission is permitted. */
  BATCH_ALLOWED(PolicyDomain.CAPABILITY, PolicyKind.CAPABILITY, DenialReason.FEATURE_DISABLED),

  // ---- Tier 7: quota ---------------------------------------------------------------------------

  /** Largest input context the request may present, in tokens. */
  MAX_CONTEXT(PolicyDomain.QUOTA, PolicyKind.CEILING, DenialReason.QUOTA_EXCEEDED),

  /** Largest completion the request may ask for, in tokens. */
  MAX_OUTPUT_TOKENS(PolicyDomain.QUOTA, PolicyKind.CEILING, DenialReason.QUOTA_EXCEEDED),

  /** Total requests admitted in the current quota window. */
  MAX_REQUESTS(PolicyDomain.QUOTA, PolicyKind.CEILING, DenialReason.QUOTA_EXCEEDED),

  /**
   * Requests admitted per minute — the admission view, distinct from C2's throttle (Doc 21 §20).
   */
  MAX_RPM(PolicyDomain.QUOTA, PolicyKind.CEILING, DenialReason.RATE_LIMITED),

  /** Tokens admitted per minute. */
  MAX_TPM(PolicyDomain.QUOTA, PolicyKind.CEILING, DenialReason.RATE_LIMITED),

  /** In-flight requests admitted at once. */
  CONCURRENCY_LIMIT(PolicyDomain.QUOTA, PolicyKind.CEILING, DenialReason.QUOTA_EXCEEDED),

  // ---- Tier 8: budget --------------------------------------------------------------------------

  /** Largest normalized spend a single request may incur, in micros. */
  MAX_COST(PolicyDomain.BUDGET, PolicyKind.CEILING, DenialReason.BUDGET_EXCEEDED),

  /** Normalized spend admitted in the current day, in micros. */
  DAILY_BUDGET(PolicyDomain.BUDGET, PolicyKind.CEILING, DenialReason.BUDGET_EXCEEDED),

  /** Normalized spend admitted in the current month, in micros. */
  MONTHLY_BUDGET(PolicyDomain.BUDGET, PolicyKind.CEILING, DenialReason.BUDGET_EXCEEDED);

  private final PolicyDomain domain;
  private final PolicyKind kind;
  private final DenialReason denialReason;

  PolicyType(final PolicyDomain domain, final PolicyKind kind, final DenialReason denialReason) {
    this.domain = domain;
    this.kind = kind;
    this.denialReason = denialReason;
  }

  /**
   * The precedence tier this type belongs to.
   *
   * @return the policy domain
   */
  public PolicyDomain domain() {
    return domain;
  }

  /**
   * The value shape, which determines the merge combinator.
   *
   * @return the policy kind
   */
  public PolicyKind kind() {
    return kind;
  }

  /**
   * The neutral reason surfaced when this type binds a denial.
   *
   * @return the denial reason
   */
  public DenialReason denialReason() {
    return denialReason;
  }

  /**
   * Whether this type sits on a non-demotable tier and must therefore be enforced as mandatory.
   *
   * @return {@code true} when only {@link EnforcementLevel#MANDATORY} may be authored for this type
   */
  public boolean requiresMandatoryEnforcement() {
    return domain.isHard();
  }
}

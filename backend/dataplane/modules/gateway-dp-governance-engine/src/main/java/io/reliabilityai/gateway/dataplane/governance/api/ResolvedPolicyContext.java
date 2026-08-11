package io.reliabilityai.gateway.dataplane.governance.api;

import io.reliabilityai.gateway.common.Preconditions;
import java.time.Instant;
import java.util.List;

/**
 * The constraints an admitted request carries downstream — the signed Governance → Router handoff
 * (Doc 21 §36.1 {@code ResolvedGovernanceContext}).
 *
 * <p>The Router consumes this and <b>never re-evaluates policy</b>. It may narrow within these
 * constraints; it may not widen them, add a provider that is not listed, or extend the deadline.
 * That rule is what makes governance a single resolution authority instead of two subsystems that
 * drift apart over a release cycle (RGC-1/RGC-5).
 *
 * <p><b>An empty list means "policy expressed no constraint of this kind", not "deny
 * everything".</b> The distinction matters: by the time this object exists, every authored
 * allow-list has already been checked against the request and any violation has already denied it.
 * A downstream stage that read an empty {@code allowedProviders} as "no provider is permitted"
 * would refuse every request on a deployment that simply never authored a provider policy. Absence
 * of constraint is not constraint — fail-closed lives in the evaluator, not in this record's
 * readers.
 *
 * @param residencyScope permitted regions, empty when unconstrained
 * @param complianceRegimes regimes the execution path must satisfy
 * @param allowedProviders permitted provider ids, empty when unconstrained
 * @param allowedModels permitted canonical model ids, empty when unconstrained
 * @param authorizedTools tools the caller may invoke, empty when unconstrained
 * @param maxContextTokens the tightest input ceiling in force, or -1 when unconstrained
 * @param maxOutputTokens the tightest completion ceiling in force, or -1 when unconstrained
 * @param maxCostMicros the tightest per-request spend ceiling in force, or -1 when unconstrained
 * @param streamingRequired whether policy requires the response to stream
 * @param jsonModeRequired whether policy requires a pinned response schema
 * @param policyVersion the generation this context was resolved from
 * @param validUntil the deadline past which the pipeline must re-govern rather than reuse this
 */
public record ResolvedPolicyContext(
    List<String> residencyScope,
    List<String> complianceRegimes,
    List<String> allowedProviders,
    List<String> allowedModels,
    List<String> authorizedTools,
    long maxContextTokens,
    long maxOutputTokens,
    long maxCostMicros,
    boolean streamingRequired,
    boolean jsonModeRequired,
    PolicyVersion policyVersion,
    Instant validUntil) {

  /** The sentinel used for a ceiling policy did not set. */
  public static final long UNBOUNDED = -1L;

  /** Validates the resolved context. */
  public ResolvedPolicyContext {
    residencyScope = residencyScope == null ? List.of() : List.copyOf(residencyScope);
    complianceRegimes = complianceRegimes == null ? List.of() : List.copyOf(complianceRegimes);
    allowedProviders = allowedProviders == null ? List.of() : List.copyOf(allowedProviders);
    allowedModels = allowedModels == null ? List.of() : List.copyOf(allowedModels);
    authorizedTools = authorizedTools == null ? List.of() : List.copyOf(authorizedTools);
    Preconditions.requireNonNull(policyVersion, "policyVersion");
    Preconditions.requireNonNull(validUntil, "validUntil");
  }

  /**
   * The context handed forward when a request is refused: nothing is resolved, because nothing is
   * admitted. Never null, so a caller that ignores the verdict still cannot read a permissive
   * scope.
   *
   * @param version the generation the refusal was decided against
   * @param validUntil the decision instant
   * @return an empty, fully-constrained context
   */
  public static ResolvedPolicyContext denied(
      final PolicyVersion version, final Instant validUntil) {
    return new ResolvedPolicyContext(
        List.of(),
        List.of(),
        List.of(),
        List.of(),
        List.of(),
        0L,
        0L,
        0L,
        false,
        false,
        version,
        validUntil);
  }

  /**
   * Whether this context has passed its validity deadline and the pipeline must re-govern (RGC-4).
   *
   * @param now the injected-clock instant
   * @return {@code true} once the deadline has passed
   */
  public boolean isExpiredAt(final Instant now) {
    return !now.isBefore(validUntil);
  }
}

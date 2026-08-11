package io.reliabilityai.gateway.dataplane.governance.api;

import io.reliabilityai.gateway.common.Preconditions;
import java.util.List;
import java.util.Optional;

/**
 * The result of one governance evaluation. Total, never null, never thrown (Doc 21 §21/§42).
 *
 * <p>"Never throws" is the load-bearing property. An admission gate that can throw has a code path
 * that ends somewhere other than a decision, and that path will eventually be caught by a handler
 * that has to guess — and a handler guessing about admission is exactly the silent-permit failure
 * mode GV-INV exists to prevent. Every construction path here, including the one for an internal
 * error, produces a decision object with a verdict.
 *
 * <p>Carries everything an auditor needs to reconstruct the call months later: what was decided,
 * which statements were violated, which generation of policy decided it, and how long it took.
 *
 * @param verdict what was decided
 * @param reasonCode the neutral, externally-safe reason string
 * @param violations every statement violated, in precedence order
 * @param policyVersion the generation evaluated against
 * @param latencyNanos evaluation cost measured through the injected ticker
 * @param context the constraints an admitted request carries forward
 */
public record PolicyDecision(
    Verdict verdict,
    String reasonCode,
    List<PolicyViolation> violations,
    PolicyVersion policyVersion,
    long latencyNanos,
    ResolvedPolicyContext context) {

  /** The reason reported when nothing was violated. */
  public static final String PERMITTED = "permitted";

  /** Validates the decision. */
  public PolicyDecision {
    Preconditions.requireNonNull(verdict, "verdict");
    Preconditions.requireNonBlank(reasonCode, "reasonCode");
    violations = violations == null ? List.of() : List.copyOf(violations);
    Preconditions.requireNonNull(policyVersion, "policyVersion");
    Preconditions.requireNonNegative(latencyNanos, "latencyNanos");
    Preconditions.requireNonNull(context, "context");
  }

  /**
   * Builds a decision from the violations collected during evaluation, deriving the verdict and the
   * reported reason rather than trusting a caller to keep them consistent.
   *
   * @param violations the statements violated, in precedence order
   * @param version the generation evaluated against
   * @param latencyNanos the measured evaluation cost
   * @param context the constraints for an admitted request
   * @return the assembled decision
   */
  public static PolicyDecision of(
      final List<PolicyViolation> violations,
      final PolicyVersion version,
      final long latencyNanos,
      final ResolvedPolicyContext context) {
    Preconditions.requireNonNull(violations, "violations");
    Verdict verdict = Verdict.ALLOW;
    for (final PolicyViolation violation : violations) {
      verdict = verdict.worst(violation.verdict());
    }
    String reason = PERMITTED;
    for (final PolicyViolation violation : violations) {
      if (violation.verdict() == verdict) {
        reason = violation.reason().code();
        break;
      }
    }
    return new PolicyDecision(verdict, reason, violations, version, latencyNanos, context);
  }

  /**
   * The fail-closed decision produced when evaluation could not complete — a missing snapshot, an
   * unreadable counter, an unexpected exception. Refusing on uncertainty is the ground state (Doc
   * 21 §42).
   *
   * @param reason the neutral reason to surface
   * @param version the generation in force, or {@link PolicyVersion#NONE}
   * @param latencyNanos the measured cost up to the failure
   * @param at the injected-clock instant
   * @return the refusal
   */
  public static PolicyDecision failClosed(
      final DenialReason reason,
      final PolicyVersion version,
      final long latencyNanos,
      final java.time.Instant at) {
    return new PolicyDecision(
        Verdict.DENY,
        reason.code(),
        List.of(),
        version,
        latencyNanos,
        ResolvedPolicyContext.denied(version, at));
  }

  /**
   * Whether the request continues down the pipeline.
   *
   * @return {@code true} for every verdict except {@link Verdict#DENY}
   */
  public boolean admits() {
    return verdict.admits();
  }

  /**
   * The violation that determined the verdict — the highest-precedence one at the reported
   * severity.
   *
   * @return the binding violation, or empty when nothing was violated
   */
  public Optional<PolicyViolation> binding() {
    for (final PolicyViolation violation : violations) {
      if (violation.verdict() == verdict) {
        return Optional.of(violation);
      }
    }
    return Optional.empty();
  }

  /**
   * The violations that did not bind but were still recorded — advisory breaches on an admitted
   * request, or shadow rules that would have refused it.
   *
   * @return the non-binding violations
   */
  public List<PolicyViolation> observed() {
    return violations.stream().filter(violation -> violation.verdict() != verdict).toList();
  }
}

package io.reliabilityai.gateway.canonical.decision;

import io.reliabilityai.gateway.common.Preconditions;
import java.util.List;

/**
 * The Governance/PEP admission decision (C4, Doc 21, AD-019, Doc 33 §10.4). Deny-by-default; a
 * decision-replay + audit artifact. Immutable; obligations defensively copied.
 *
 * @param permit whether the request is admitted
 * @param obligations obligations attached to a permit (Doc 07)
 * @param reason the decision reason (deny/permit rationale, content-free)
 */
public record GovernanceDecision(boolean permit, List<String> obligations, String reason) {

  /** Compact constructor defensively copying obligations and validating the reason. */
  public GovernanceDecision {
    Preconditions.requireNonNull(reason, "reason");
    // Inlined copy, not Preconditions.immutableList — see that method's javadoc (EI_EXPOSE_REP).
    obligations = obligations == null ? List.of() : List.copyOf(obligations);
  }
}

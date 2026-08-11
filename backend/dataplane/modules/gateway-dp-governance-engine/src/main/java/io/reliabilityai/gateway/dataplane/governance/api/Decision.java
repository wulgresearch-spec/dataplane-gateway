package io.reliabilityai.gateway.dataplane.governance.api;

import io.reliabilityai.gateway.common.Preconditions;
import io.reliabilityai.gateway.dataplane.governance.domain.ResolvedGovernanceContext;
import java.util.List;
import java.util.Map;

/**
 * The engine's verdict (Doc 21 §6): admit, admit-subject-to-approval, or deny.
 *
 * <p>Sealed, so no caller can bolt on a fourth "maybe" case, and so a downstream stage cannot treat
 * an unhandled variant as permission. Every variant carries the policy versions it was decided
 * against, which is what makes a decision replayable months later during an audit (GV-D12).
 */
public sealed interface Decision permits Decision.Permit, Decision.RequireApproval, Decision.Deny {

  /**
   * The snapshot versions this decision was evaluated against.
   *
   * @return the version map (policy, entitlement, usage)
   */
  Map<String, String> policyVersions();

  /**
   * Whether the request may proceed to routing without further human action.
   *
   * @return {@code true} only for {@link Permit}
   */
  boolean permitted();

  /**
   * The request is admitted; the resolved constraints become hard filters for the Router (Doc 19).
   *
   * @param context the resolved residency, compliance, capability and headroom scope
   * @param policyVersions the snapshot versions decided against
   */
  record Permit(ResolvedGovernanceContext context, Map<String, String> policyVersions)
      implements Decision {

    /** Validates the permit. */
    public Permit {
      Preconditions.requireNonNull(context, "context");
      policyVersions = policyVersions == null ? Map.of() : Map.copyOf(policyVersions);
    }

    @Override
    public boolean permitted() {
      return true;
    }
  }

  /**
   * The request satisfies every hard domain but touches a capability the tenant has placed behind
   * human approval. It is <b>not</b> admitted: the caller must obtain approval and resubmit.
   * Modelled as its own variant rather than a permit-with-obligation so that a caller which ignores
   * obligations cannot accidentally proceed.
   *
   * @param awaitingCapabilities the capabilities requiring approval, in deterministic order
   * @param policyVersions the snapshot versions decided against
   */
  record RequireApproval(
      java.util.List<String> awaitingCapabilities, Map<String, String> policyVersions)
      implements Decision {

    /** Validates the approval requirement. */
    public RequireApproval {
      awaitingCapabilities =
          awaitingCapabilities == null ? List.of() : List.copyOf(awaitingCapabilities);
      policyVersions = policyVersions == null ? Map.of() : Map.copyOf(policyVersions);
    }

    @Override
    public boolean permitted() {
      return false;
    }
  }

  /**
   * The request is refused by the named binding domain (GV-D2 deny-overrides).
   *
   * @param reason the binding denial reason
   * @param explanation a short, content-free internal explanation for the audit trail
   * @param policyVersions the snapshot versions decided against
   */
  record Deny(DenialReason reason, String explanation, Map<String, String> policyVersions)
      implements Decision {

    /** Validates the denial. */
    public Deny {
      Preconditions.requireNonNull(reason, "reason");
      Preconditions.requireNonBlank(explanation, "explanation");
      policyVersions = policyVersions == null ? Map.of() : Map.copyOf(policyVersions);
    }

    @Override
    public boolean permitted() {
      return false;
    }
  }
}

package io.reliabilityai.gateway.ports;

import io.reliabilityai.gateway.canonical.secret.CredentialLease;
import io.reliabilityai.gateway.common.Preconditions;

/**
 * The Secrets Provider port (C14, Doc 26 §7). Materializes a short-lived, single-use, in-memory
 * credential lease supplied only to the Provider Adapter; fail-closed to {@code
 * CredentialUnavailable}. Owns no store; holds credentials only in process memory for the active
 * invocation (Doc 26 §33.1).
 */
public interface SecretsProviderPort {

  /**
   * Materializes a credential lease for the given request (Doc 26 §7). Fail-closed on any
   * uncertainty.
   *
   * @param request the credential request (tenant/route scope; no credential)
   * @return a materialized lease or a fail-closed unavailable outcome
   */
  MaterializationResult materialize(CredentialRequest request);

  /** The terminal materialization outcome (Doc 26 §D11 — fail closed). */
  sealed interface MaterializationResult
      permits MaterializationResult.Leased, MaterializationResult.CredentialUnavailable {

    /**
     * A successfully materialized, single-use credential lease.
     *
     * @param lease the credential lease (never serialized/logged; zeroized on close, Doc 26 §17.1)
     */
    record Leased(CredentialLease lease) implements MaterializationResult {
      /** Compact constructor validating the lease. */
      public Leased {
        Preconditions.requireNonNull(lease, "lease");
      }
    }

    /**
     * A fail-closed unavailable outcome (Doc 26 §21). Downstream ⇒ adapter {@code auth_failed}.
     *
     * @param reason the content-free unavailability reason
     */
    record CredentialUnavailable(String reason) implements MaterializationResult {
      /** Compact constructor validating the reason. */
      public CredentialUnavailable {
        Preconditions.requireNonBlank(reason, "reason");
      }
    }
  }
}

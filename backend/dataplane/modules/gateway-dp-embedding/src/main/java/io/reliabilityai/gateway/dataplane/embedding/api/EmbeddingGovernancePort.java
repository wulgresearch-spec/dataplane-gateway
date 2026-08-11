package io.reliabilityai.gateway.dataplane.embedding.api;

import io.reliabilityai.gateway.canonical.identity.TenantScope;

/**
 * The spend decision, taken before any provider is called (AD-030 SS8).
 *
 * <p>Before, not after, and that ordering is the whole point. Asking permission once the money is
 * spent is not governance, it is reporting. The estimate handed here is the pipeline best guess at
 * what the call will cost; SS8.2 records how far that guess can be from the invoice and why.
 *
 * <p>This is a port because the Governance Engine owns budgets and this module must not. It is not
 * the memory governance port: that one decides whether content may be stored, this one decides
 * whether money may be spent, and conflating them would let a storage policy silently authorise
 * expenditure.
 */
public interface EmbeddingGovernancePort {

  /**
   * Governance that permits every spend. Correct for a single-tenant deployment with no budgets.
   */
  EmbeddingGovernancePort PERMISSIVE =
      (tenant, modelId, inputs, estimatedTokens, estimatedCostMicros) -> Decision.ALLOW;

  /** What governance decided. */
  enum Decision {
    /** Proceed. */
    ALLOW,
    /** Refuse: the spend is not permitted. */
    DENY
  }

  /**
   * Decides whether an embedding spend may proceed.
   *
   * @param tenant whose budget it is
   * @param modelId the neutral model id
   * @param inputs how many texts would be embedded
   * @param estimatedTokens the estimated token count
   * @param estimatedCostMicros the estimated charge in millionths
   * @return the decision
   */
  Decision admitSpend(
      TenantScope tenant,
      String modelId,
      int inputs,
      long estimatedTokens,
      long estimatedCostMicros);
}

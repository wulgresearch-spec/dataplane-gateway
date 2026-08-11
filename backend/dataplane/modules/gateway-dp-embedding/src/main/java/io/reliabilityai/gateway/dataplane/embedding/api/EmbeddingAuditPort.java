package io.reliabilityai.gateway.dataplane.embedding.api;

import io.reliabilityai.gateway.canonical.identity.TenantScope;
import io.reliabilityai.gateway.dataplane.provider.api.ProviderId;

/**
 * The record of what was embedded, for whom, and at what cost (AD-030 SS10).
 *
 * <p>Counts and costs, never content. An audit trail that quoted the embedded text would be a
 * second copy of tenant data in the one store most likely to be exported for compliance review.
 */
public interface EmbeddingAuditPort {

  /** An audit sink that discards everything. */
  EmbeddingAuditPort NOOP =
      new EmbeddingAuditPort() {
        @Override
        public void embedded(
            final TenantScope tenant,
            final ProviderId provider,
            final String modelId,
            final int inputs,
            final EmbeddingUsage usage) {}

        @Override
        public void refused(
            final TenantScope tenant,
            final String modelId,
            final EmbeddingFailure reason,
            final int inputs) {}
      };

  /**
   * Records a completed embedding.
   *
   * @param tenant whose spend this was
   * @param provider which provider served it
   * @param modelId the neutral model id
   * @param inputs how many texts were embedded
   * @param usage what it consumed
   */
  void embedded(
      TenantScope tenant, ProviderId provider, String modelId, int inputs, EmbeddingUsage usage);

  /**
   * Records a refusal, including one governance made before any call.
   *
   * @param tenant whose request was refused
   * @param modelId the neutral model id
   * @param reason the neutral failure kind
   * @param inputs how many texts were affected
   */
  void refused(TenantScope tenant, String modelId, EmbeddingFailure reason, int inputs);
}

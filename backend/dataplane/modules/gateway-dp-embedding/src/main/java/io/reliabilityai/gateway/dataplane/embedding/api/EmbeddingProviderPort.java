package io.reliabilityai.gateway.dataplane.embedding.api;

import io.reliabilityai.gateway.dataplane.provider.api.ProviderId;

/**
 * The one seam every embedding provider sits behind (AD-030 §3).
 *
 * <p><b>Everything above this interface is provider-neutral, and that is enforced structurally
 * rather than by convention.</b> Nothing in this package, in {@code domain} or in {@code
 * application} names a vendor; the only place a vendor appears is an implementation of this port.
 * An architecture test scans for vendor literals outside {@code internal} and fails the build if
 * one appears.
 *
 * <p>The port is <b>batch-shaped</b>. Every provider worth using charges less and answers faster
 * for a batch than for the same inputs one at a time, and a single-text port would make batching
 * impossible to express without a second interface.
 *
 * <p>Implementations <b>must not retry</b>. One call here is one attempt. Retry, backoff, budget
 * and failure policy live in {@code EmbeddingPipeline}, so that they are decided once for every
 * provider rather than reimplemented, differently, inside each adapter.
 */
public interface EmbeddingProviderPort {

  /**
   * Which provider this is.
   *
   * @return the provider identity
   */
  ProviderId id();

  /**
   * What this provider can do.
   *
   * @return the declared capability
   */
  EmbeddingCapability capability();

  /**
   * Embeds a batch. Exactly one attempt.
   *
   * <p>Must not throw for a provider-side refusal: a rate limit, a rejection or an oversized input
   * is a {@link EmbeddingResponse.Outcome.Failed} entry, because a batch of sixty-four with one bad
   * input must still return sixty-three embeddings.
   *
   * @param request the batch, already within {@link EmbeddingCapability} limits
   * @return one outcome per input, in request order
   * @throws EmbeddingTransportException when the attempt failed before any provider verdict, which
   *     is the one condition the caller must distinguish because it is the one that is always
   *     retryable
   */
  EmbeddingResponse embed(EmbeddingRequest request);

  /**
   * Whether this provider is currently usable.
   *
   * @return the health view
   */
  EmbeddingHealth health();
}

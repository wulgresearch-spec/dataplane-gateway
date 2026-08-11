package io.reliabilityai.gateway.dataplane.embedding.internal;

import io.reliabilityai.gateway.canonical.identity.TenantScope;
import io.reliabilityai.gateway.common.Preconditions;
import io.reliabilityai.gateway.dataplane.embedding.api.EmbeddingRequest;
import io.reliabilityai.gateway.dataplane.embedding.api.EmbeddingResponse;
import io.reliabilityai.gateway.dataplane.embedding.application.EmbeddingBatcher;
import io.reliabilityai.gateway.dataplane.memory.api.EmbeddingPort;
import io.reliabilityai.gateway.dataplane.memory.api.MemoryStoreUnavailableException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Bridges the Memory Runtime one-text port onto the batching pipeline (AD-030 §3.1).
 *
 * <p>{@code EmbeddingPort} is {@code float[] embed(String)} — no tenant, no model, no batch. That
 * is the whole of what C17 will hand over, and changing it is a Memory Runtime change this
 * milestone is forbidden to make. Three consequences follow, and all three are limitations rather
 * than choices:
 *
 * <ul>
 *   <li><b>The tenant is fixed at construction.</b> One of these serves one tenant. A deployment
 *       serving many needs one instance per tenant, which is workable but is not what a single
 *       {@code MemoryRuntime} is wired for today. B55.
 *   <li><b>Batching only happens across concurrent callers.</b> A single-threaded writer waits out
 *       the batch timeout and then sends a batch of one. B56.
 *   <li><b>A failure becomes an exception.</b> The port returns {@code float[]}, so there is
 *       nowhere to put a neutral failure reason; every refusal collapses to {@code
 *       MemoryStoreUnavailableException}, which C17 already handles by refusing the write.
 * </ul>
 *
 * <p>None of this is visible above the port, which is the point: C17 keeps working exactly as it
 * did, and everything the pipeline offers is available to any caller willing to use the richer API.
 */
public final class PipelineEmbeddingPort implements EmbeddingPort {

  private final EmbeddingBatcher batcher;

  private final TenantScope tenant;

  private final String modelId;

  private final long waitMillis;

  /**
   * Creates the bridge.
   *
   * @param batcher the coalescing batcher
   * @param tenant the tenant this instance serves
   * @param modelId the neutral model id
   * @param waitMillis how long a caller waits before the write is refused
   */
  public PipelineEmbeddingPort(
      final EmbeddingBatcher batcher,
      final TenantScope tenant,
      final String modelId,
      final long waitMillis) {
    this.batcher = Preconditions.requireNonNull(batcher, "batcher");
    this.tenant = Preconditions.requireNonNull(tenant, "tenant");
    this.modelId = Preconditions.requireNonBlank(modelId, "modelId");
    this.waitMillis = waitMillis;
  }

  @Override
  public float[] embed(final String text) {
    final EmbeddingResponse.Outcome outcome;
    try {
      outcome =
          batcher
              .submit(tenant, modelId, text, EmbeddingRequest.EmbeddingPurpose.WRITE)
              .get(waitMillis, TimeUnit.MILLISECONDS);
    } catch (final InterruptedException interrupted) {
      Thread.currentThread().interrupt();
      throw new MemoryStoreUnavailableException("embedding was interrupted");
    } catch (final TimeoutException tooSlow) {
      throw new MemoryStoreUnavailableException(
          "embedding did not complete within the wait budget");
    } catch (final ExecutionException failed) {
      throw new MemoryStoreUnavailableException("embedding failed");
    }
    if (outcome instanceof EmbeddingResponse.Outcome.Embedded embedded) {
      return embedded.embedding().vector();
    }
    // The reason is dropped here because the port cannot carry it. It is not lost: the metrics and
    // audit ports recorded it before this point.
    throw new MemoryStoreUnavailableException("the embedding provider refused this content");
  }
}

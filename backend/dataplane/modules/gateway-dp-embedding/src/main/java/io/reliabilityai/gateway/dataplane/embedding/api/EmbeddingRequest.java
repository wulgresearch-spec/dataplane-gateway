package io.reliabilityai.gateway.dataplane.embedding.api;

import io.reliabilityai.gateway.canonical.identity.TenantScope;
import io.reliabilityai.gateway.common.Preconditions;
import java.util.List;

/**
 * One or more texts to embed, for one tenant, under one model (AD-030 §4).
 *
 * <p>Batch-shaped from the start. A single-text request is a batch of one, so nothing downstream
 * needs two code paths and the batcher never has to widen a narrower type.
 *
 * <p>The tenant is present because cost, budget, cache isolation and audit all need it. That it is
 * <em>absent</em> from {@code EmbeddingPort}, the interface the Memory Runtime actually calls, is
 * the central limitation of this milestone — AD-030 §3.1 and B55.
 *
 * @param tenant whose spend and whose cache this belongs to
 * @param modelId the neutral model identifier
 * @param texts the inputs, in order; the response aligns with this order
 * @param purpose why the embedding is wanted, for audit and metrics dimensions
 */
public record EmbeddingRequest(
    TenantScope tenant, String modelId, List<String> texts, EmbeddingPurpose purpose) {

  /** Why an embedding was asked for. */
  public enum EmbeddingPurpose {
    /** Indexing content on the write path. */
    WRITE,
    /** Embedding a query on the read path. */
    QUERY,
    /** Re-embedding existing content, such as after a model change. */
    BACKFILL
  }

  /**
   * Validates and freezes the request.
   *
   * @param tenant the owning tenant
   * @param modelId the neutral model id
   * @param texts the inputs
   * @param purpose the reason
   */
  public EmbeddingRequest {
    Preconditions.requireNonNull(tenant, "tenant");
    Preconditions.requireNonBlank(modelId, "modelId");
    Preconditions.requireNonNull(purpose, "purpose");
    texts = List.copyOf(Preconditions.requireNonNull(texts, "texts"));
    if (texts.isEmpty()) {
      throw new IllegalArgumentException("a request must carry at least one text");
    }
    for (final String text : texts) {
      if (text == null) {
        throw new IllegalArgumentException("a request may not carry a null text");
      }
    }
  }

  /**
   * A single-text request.
   *
   * @param tenant the owning tenant
   * @param modelId the neutral model id
   * @param text the input
   * @param purpose the reason
   * @return the request
   */
  public static EmbeddingRequest of(
      final TenantScope tenant,
      final String modelId,
      final String text,
      final EmbeddingPurpose purpose) {
    return new EmbeddingRequest(tenant, modelId, List.of(text), purpose);
  }

  /**
   * How many inputs this request carries.
   *
   * @return the input count
   */
  public int size() {
    return texts.size();
  }

  /**
   * Renders the request without rendering the texts.
   *
   * <p>The inputs are tenant content, frequently the same content the PII engine has just
   * classified as sensitive. A request logged in full would undo that.
   *
   * @return a description carrying no input text
   */
  @Override
  public String toString() {
    return "EmbeddingRequest[tenant="
        + tenant.org()
        + "/"
        + tenant.tenant()
        + ", model="
        + modelId
        + ", inputs="
        + texts.size()
        + ", purpose="
        + purpose
        + "]";
  }
}

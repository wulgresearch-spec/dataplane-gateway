package io.reliabilityai.gateway.dataplane.memory.api;

import java.util.List;

/**
 * The similarity index (AD-026 §12).
 *
 * <p>Satisfied by pgvector, Milvus, Pinecone, Qdrant, Elasticsearch or OpenSearch adapters. A
 * technology that serves both this and {@link MemoryStorePort} implements both; the runtime cannot
 * tell and must not care.
 *
 * <p>An embedding is an opaque {@code float[]} (MEM-3). The runtime never computes one, never
 * normalises one and never interprets one beyond the similarity the index reports. It does not know
 * the dimensionality, the distance metric or whether the index is approximate — all three are the
 * adapter's business, and encoding any of them here would make the port fit one technology.
 */
public interface VectorIndexPort {

  /**
   * Indexes an embedding against a record.
   *
   * @param scope the record's scope, so the index can partition by tenant
   * @param id the record the embedding belongs to
   * @param embedding the opaque vector
   * @throws MemoryStoreUnavailableException when the index cannot be reached
   */
  void index(MemoryScope scope, MemoryRecordId id, float[] embedding);

  /**
   * Finds the records most similar to a vector, within a narrowed scope.
   *
   * @param scope the narrowed scope; the index must not consider anything outside it
   * @param types the memory kinds to consider
   * @param embedding the query vector
   * @param limit the most matches to return
   * @return matches, most similar first
   * @throws MemoryStoreUnavailableException when the index cannot be reached
   */
  List<Similarity> similar(
      MemoryScope scope, java.util.Set<MemoryType> types, float[] embedding, int limit);

  /**
   * Removes a record's embedding.
   *
   * <p>Idempotent. Called on every delete, including for records that were never indexed, because
   * the alternative is an index entry that dereferences to nothing — and a semantic hit that
   * resolves to a missing record is worse than a record that is temporarily not findable (AD-026
   * §11.2).
   *
   * @param scope the record's scope
   * @param id the record whose embedding to remove
   * @throws MemoryStoreUnavailableException when the index cannot be reached
   */
  void remove(MemoryScope scope, MemoryRecordId id);

  /**
   * One similarity match.
   *
   * @param id the matching record
   * @param score the index's similarity figure, of adapter-specific scale
   */
  record Similarity(MemoryRecordId id, double score) {

    /**
     * Validates the match.
     *
     * @param id the record identity
     * @param score the similarity figure
     */
    public Similarity {
      io.reliabilityai.gateway.common.Preconditions.requireNonNull(id, "id");
      if (Double.isNaN(score)) {
        throw new IllegalArgumentException("score must be a number");
      }
    }
  }
}

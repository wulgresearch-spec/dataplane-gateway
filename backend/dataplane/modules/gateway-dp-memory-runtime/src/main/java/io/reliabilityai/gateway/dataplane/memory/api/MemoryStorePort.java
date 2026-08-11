package io.reliabilityai.gateway.dataplane.memory.api;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * The durable record store (AD-026 §12).
 *
 * <p>One port, satisfied by PostgreSQL, MongoDB, Redis, Elasticsearch or OpenSearch adapters — none
 * of which this module names or knows about (MEM-1). What the port deliberately does <b>not</b>
 * expose: a query language, an index hint, a consistency level, a connection or transaction handle,
 * or a paging cursor format. Each would leak one technology's model into the runtime and make a
 * portable record (MEM-4) impossible.
 *
 * <p><b>What an adapter must guarantee</b> (AD-026 §12.3), restated here because this is the file
 * an adapter author reads:
 *
 * <ol>
 *   <li>{@link #put} is durable before it returns, or it throws. Never "queued".
 *   <li>{@link #put} is idempotent on the record's scope and write key.
 *   <li>{@link #search} returns <b>only</b> records matching the narrowed scope. The runtime
 *       re-verifies, but an adapter that needs re-verification to be correct is a broken adapter.
 *   <li>Unavailability is reported by throwing {@link MemoryStoreUnavailableException},
 *       <b>never</b> by returning an empty result. An adapter that returns empty on failure turns a
 *       store outage into "this user has no memories" — which reads as data loss, and in a
 *       write-if-absent flow causes it.
 *   <li>Never return more than the requested limit.
 *   <li>Stored bytes come back byte-identical.
 *   <li>{@link #delete} is idempotent and reports whether anything was removed.
 * </ol>
 */
public interface MemoryStorePort {

  /**
   * Stores a record durably.
   *
   * @param record the record to store
   * @param idempotencyKey the scope-qualified write key; a repeat is the same write
   * @return what happened: the effective record, and whether this call is what stored it
   * @throws MemoryStoreUnavailableException when the store cannot be reached
   */
  PutResult put(MemoryRecord record, String idempotencyKey);

  /**
   * What a {@link #put} did.
   *
   * <p>The {@code created} flag is not decoration. An idempotent operation that cannot say whether
   * it did anything is under-specified: the runtime needs to know so it can audit a replay as a
   * replay rather than as a fresh write, and so it does not re-index content that is already
   * indexed.
   *
   * <p>It cannot be inferred by comparing identities, because a deterministic id factory gives a
   * retried write the same id by design — which is the whole point of deterministic ids, and the
   * reason this flag has to come from the layer that actually knows.
   *
   * @param record the effective record: the newly stored one, or the pre-existing one on a replay
   * @param created true when this call stored the record, false when the write key had been seen
   *     before
   */
  record PutResult(MemoryRecord record, boolean created) {

    /**
     * Validates the result.
     *
     * @param record the effective record
     * @param created whether this call stored it
     */
    public PutResult {
      io.reliabilityai.gateway.common.Preconditions.requireNonNull(record, "record");
    }

    /**
     * A freshly stored record.
     *
     * @param record the record
     * @return the result
     */
    public static PutResult stored(final MemoryRecord record) {
      return new PutResult(record, true);
    }

    /**
     * A record that was already there under the same write key.
     *
     * @param existing the pre-existing record
     * @return the result
     */
    public static PutResult replayed(final MemoryRecord existing) {
      return new PutResult(existing, false);
    }
  }

  /**
   * Loads one record by identity.
   *
   * @param scope the narrowed scope the caller may see
   * @param id the record to load
   * @return the record, or empty when it does not exist or is outside the scope
   * @throws MemoryStoreUnavailableException when the store cannot be reached
   */
  Optional<MemoryRecord> get(MemoryScope scope, MemoryRecordId id);

  /**
   * Searches within a narrowed scope.
   *
   * <p>The query's scope has already been intersected with the caller's authority (MEM-15), so an
   * adapter that honours it cannot return another tenant's record.
   *
   * @param query the narrowed query
   * @return matching records with their adapter-native relevance scores
   * @throws MemoryStoreUnavailableException when the store cannot be reached
   */
  List<ScoredRecord> search(MemoryQuery query);

  /**
   * Removes a record.
   *
   * @param scope the narrowed scope
   * @param id the record to remove
   * @return true when something was removed, false when it was already absent
   * @throws MemoryStoreUnavailableException when the store cannot be reached
   */
  boolean delete(MemoryScope scope, MemoryRecordId id);

  /**
   * Lists records whose expiry has passed, for the lifecycle sweeper.
   *
   * <p>Returns records under legal hold too. Filtering them here would put a compliance rule inside
   * nine adapters; the sweeper checks the hold itself, unconditionally, on every path (MEM-20).
   *
   * @param now the instant to evaluate expiry against
   * @param limit the most records to return in one pass
   * @return expired records, oldest first
   * @throws MemoryStoreUnavailableException when the store cannot be reached
   */
  List<MemoryRecord> expired(Instant now, int limit);

  /**
   * Records the fact that a record was read, for the usage ranking signals.
   *
   * <p>Best-effort by contract: a failure here must not fail the read that triggered it. Losing an
   * access count degrades ranking slightly; failing the read loses the answer entirely.
   *
   * @param scope the narrowed scope
   * @param id the record that was read
   * @param at the instant of the read
   */
  void touch(MemoryScope scope, MemoryRecordId id, Instant at);

  /**
   * A record with the adapter's own relevance score.
   *
   * <p>The score is <b>not</b> normalised and is not comparable across adapters — a BM25-family
   * figure has no upper bound while a cosine is bounded. The ranker normalises within a result set
   * and fuses on rank rather than on raw score, precisely so that swapping an adapter cannot
   * silently change relevance (AD-026 §7.3).
   *
   * @param record the matching record
   * @param score the adapter's native relevance figure, of unspecified scale
   */
  record ScoredRecord(MemoryRecord record, double score) {

    /**
     * Validates the pair.
     *
     * @param record the record
     * @param score the native score
     */
    public ScoredRecord {
      io.reliabilityai.gateway.common.Preconditions.requireNonNull(record, "record");
      if (Double.isNaN(score)) {
        throw new IllegalArgumentException("score must be a number");
      }
    }

    /**
     * Pairs a record with a neutral score, for modes that produce no relevance figure.
     *
     * @param record the record
     * @return the pair with a zero score
     */
    public static ScoredRecord unscored(final MemoryRecord record) {
      return new ScoredRecord(record, 0.0d);
    }
  }
}

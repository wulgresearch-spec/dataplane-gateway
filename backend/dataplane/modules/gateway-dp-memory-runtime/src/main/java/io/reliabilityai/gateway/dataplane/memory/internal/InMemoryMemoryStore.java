package io.reliabilityai.gateway.dataplane.memory.internal;

import io.reliabilityai.gateway.common.Preconditions;
import io.reliabilityai.gateway.dataplane.memory.api.MemoryQuery;
import io.reliabilityai.gateway.dataplane.memory.api.MemoryRecord;
import io.reliabilityai.gateway.dataplane.memory.api.MemoryRecordId;
import io.reliabilityai.gateway.dataplane.memory.api.MemoryScope;
import io.reliabilityai.gateway.dataplane.memory.api.MemoryStorePort;
import io.reliabilityai.gateway.dataplane.memory.api.MemoryStoreUnavailableException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * The reference record store.
 *
 * <p><b>Not durable, and named so.</b> It exists to make the port's contract executable: the
 * adapter conformance suite is written against {@link MemoryStorePort}, and this is simply its
 * first subject. A production deployment supplies a real adapter and runs the same suite unmodified
 * (AD-026 §15.3).
 *
 * <p>Its keyword matching is a substring count, not a relevance model. That is deliberate — this
 * class must demonstrate the <em>contract</em>, not compete with a search engine, and a fake that
 * pretended to rank well would make the ranker's tests meaningless.
 *
 * <p>Thread-safe. Records live in a concurrent map keyed by scope and id, so the tenant partition
 * is part of the key rather than a field that could be forgotten in a comparison.
 */
public final class InMemoryMemoryStore implements MemoryStorePort {

  private final Map<String, MemoryRecord> records = new ConcurrentHashMap<>();
  private final Map<String, MemoryRecordId> byIdempotencyKey = new ConcurrentHashMap<>();

  /**
   * The reverse of {@link #byIdempotencyKey}, so a delete can drop the write key in constant time.
   *
   * <p>Without it, delete has to scan every idempotency entry to find the one pointing at the
   * record — which made a 1000-record sweep take 2.7 seconds in the benchmark. That is a wart worth
   * fixing even in a reference adapter, because a reference adapter is what an author of a real one
   * reads first.
   */
  private final Map<String, String> idempotencyKeyByRecord = new ConcurrentHashMap<>();

  private final AtomicBoolean available = new AtomicBoolean(true);

  /**
   * Makes every subsequent operation report unavailability.
   *
   * <p>Present so that fail-closed behaviour is testable. AD-026 §11 requires a read to refuse
   * rather than return a partial result when the store is down, and a claim like that is worth
   * nothing without a way to actually take the store away.
   *
   * @param up whether the store should respond
   */
  public void setAvailable(final boolean up) {
    available.set(up);
  }

  @Override
  public PutResult put(final MemoryRecord record, final String idempotencyKey) {
    Preconditions.requireNonNull(record, "record");
    Preconditions.requireNonBlank(idempotencyKey, "idempotencyKey");
    requireAvailable("put");

    final MemoryRecordId existing = byIdempotencyKey.get(idempotencyKey);
    if (existing != null) {
      final MemoryRecord stored = records.get(key(record.scope(), existing));
      if (stored != null) {
        // The same write key has been seen. Return the original rather than storing a duplicate —
        // a retried write after a lost acknowledgement must not become two memories, because a
        // duplicate distorts every ranking signal that counts frequency.
        return PutResult.replayed(stored);
      }
    }
    final String recordKey = key(record.scope(), record.id());
    records.put(recordKey, record);
    byIdempotencyKey.put(idempotencyKey, record.id());
    idempotencyKeyByRecord.put(recordKey, idempotencyKey);
    return PutResult.stored(record);
  }

  @Override
  public Optional<MemoryRecord> get(final MemoryScope scope, final MemoryRecordId id) {
    Preconditions.requireNonNull(scope, "scope");
    Preconditions.requireNonNull(id, "id");
    requireAvailable("get");

    // Scoped lookup, not a global one filtered afterwards. A record in another tenant is not found
    // here, rather than found and discarded (MEM-16).
    final MemoryRecord exact = records.get(key(scope, id));
    if (exact != null) {
      return Optional.of(exact);
    }
    // The caller's scope may be broader than the record's — a workspace-scoped caller reading a
    // user-private record it owns. Fall back to a scan that still honours visibility.
    for (final MemoryRecord candidate : records.values()) {
      if (candidate.id().equals(id) && candidate.scope().visibleTo(scope)) {
        return Optional.of(candidate);
      }
    }
    return Optional.empty();
  }

  @Override
  public List<ScoredRecord> search(final MemoryQuery query) {
    Preconditions.requireNonNull(query, "query");
    requireAvailable("search");

    final List<ScoredRecord> matches = new ArrayList<>();
    for (final MemoryRecord record : records.values()) {
      if (!record.scope().visibleTo(query.scope())) {
        continue;
      }
      if (!query.types().contains(record.type())) {
        continue;
      }
      if (record.archived() && !query.includeArchived()) {
        continue;
      }
      if (!query.withinWindow(record.createdAt())) {
        continue;
      }
      if (!query.matchesMetadata(record.metadata())) {
        continue;
      }
      final double score = relevance(record, query);
      if (query.mode().needsText() && score <= 0.0d) {
        continue;
      }
      matches.add(new ScoredRecord(record, score));
    }

    matches.sort(
        Comparator.comparingDouble(ScoredRecord::score)
            .reversed()
            .thenComparing(scored -> scored.record().id().value()));
    return matches.size() <= query.limit()
        ? List.copyOf(matches)
        : List.copyOf(matches.subList(0, query.limit()));
  }

  @Override
  public boolean delete(final MemoryScope scope, final MemoryRecordId id) {
    Preconditions.requireNonNull(scope, "scope");
    Preconditions.requireNonNull(id, "id");
    requireAvailable("delete");

    final String recordKey = key(scope, id);
    final MemoryRecord removed = records.remove(recordKey);
    if (removed != null) {
      // Constant time, via the reverse index. The obvious alternative — scanning the idempotency
      // map
      // for entries pointing at this id — is O(n) per delete and turns a sweep into a quadratic.
      final String writeKey = idempotencyKeyByRecord.remove(recordKey);
      if (writeKey != null) {
        byIdempotencyKey.remove(writeKey);
      }
      return true;
    }
    // Idempotent: deleting something already gone is not an error, which is what lets the sweeper
    // re-run a failed pass without special-casing anything.
    return false;
  }

  @Override
  public List<MemoryRecord> expired(final Instant now, final int limit) {
    Preconditions.requireNonNull(now, "now");
    requireAvailable("expired");

    final List<MemoryRecord> due = new ArrayList<>();
    for (final MemoryRecord record : records.values()) {
      if (record.expiredAt(now)) {
        due.add(record);
      }
    }
    due.sort(Comparator.comparing(MemoryRecord::createdAt));
    return due.size() <= limit ? List.copyOf(due) : List.copyOf(due.subList(0, limit));
  }

  @Override
  public void touch(final MemoryScope scope, final MemoryRecordId id, final Instant at) {
    Preconditions.requireNonNull(scope, "scope");
    Preconditions.requireNonNull(id, "id");
    Preconditions.requireNonNull(at, "at");
    if (!available.get()) {
      // Best-effort by contract. A failure to record an access must not fail the read that caused
      // it.
      return;
    }
    records.computeIfPresent(key(scope, id), (unused, record) -> record.accessedAt(at));
  }

  /**
   * Returns how many records are held.
   *
   * @return the record count
   */
  public int size() {
    return records.size();
  }

  /** Forgets everything. For tests that want a clean store without building another. */
  public void clear() {
    records.clear();
    byIdempotencyKey.clear();
    idempotencyKeyByRecord.clear();
  }

  /**
   * A deliberately simple relevance figure.
   *
   * <p>Counts occurrences of the query text. Unbounded on purpose: a real keyword engine's score is
   * unbounded too, and the ranker must normalise whatever it is given rather than assume a range
   * (AD-026 §7.3).
   */
  private static double relevance(final MemoryRecord record, final MemoryQuery query) {
    if (!query.mode().needsText()) {
      return 0.0d;
    }
    final String needle = query.text().orElse("").toLowerCase(java.util.Locale.ROOT);
    if (needle.isBlank() || !record.content().readable()) {
      return 0.0d;
    }
    final String haystack = record.content().body().toLowerCase(java.util.Locale.ROOT);
    int count = 0;
    int at = haystack.indexOf(needle);
    while (at >= 0) {
      count++;
      at = haystack.indexOf(needle, at + needle.length());
    }
    return count;
  }

  private void requireAvailable(final String operation) {
    if (!available.get()) {
      // Thrown, never signalled by an empty list (AD-026 §12.3 A4). An empty result is
      // indistinguishable from "this tenant has no memories", which reads as data loss.
      throw new MemoryStoreUnavailableException("in-memory store marked unavailable: " + operation);
    }
  }

  private static String key(final MemoryScope scope, final MemoryRecordId id) {
    return scope.key() + "|" + id.value();
  }
}

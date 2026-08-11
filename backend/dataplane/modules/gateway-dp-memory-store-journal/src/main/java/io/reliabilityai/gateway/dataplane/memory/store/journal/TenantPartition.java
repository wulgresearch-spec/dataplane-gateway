package io.reliabilityai.gateway.dataplane.memory.store.journal;

import io.reliabilityai.gateway.common.Preconditions;
import io.reliabilityai.gateway.dataplane.memory.api.MemoryRecord;
import io.reliabilityai.gateway.dataplane.memory.api.MemoryRecordId;
import io.reliabilityai.gateway.dataplane.memory.api.MemoryStorePort.PutResult;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.ReentrantLock;

/**
 * One tenant's records: a durable log plus the in-memory index built from it.
 *
 * <p><b>The tenant is a filesystem boundary, not a column.</b> Each partition owns its own
 * directory and its own index, so a query can only reach another tenant's records by being handed
 * the wrong partition — which is a single lookup that the store performs from the caller's
 * already-narrowed scope. There is no predicate anywhere that a bug could weaken into matching two
 * tenants at once.
 *
 * <p><b>Why records live in memory as well as on disk.</b> {@code search} must filter on scope,
 * type, metadata and time and then score what survives. Doing that from disk would mean reading
 * every record on every query. Holding them in memory and treating the log as the durable record of
 * truth is the same shape as an append-only-file database, and its cost is honest: capacity is
 * bounded by heap, and that is recorded as a blocker rather than hidden.
 *
 * <p><b>Concurrency, in two halves.</b> Writes take a per-partition lock so that appending, syncing
 * and publishing to the index are one indivisible step — the log and the index can therefore never
 * disagree, which is what makes recovery sound. Reads take no lock at all, working from a
 * concurrent map. Different tenants never contend with each other for anything.
 */
final class TenantPartition implements AutoCloseable {

  /**
   * A record with the stamp its last write carried.
   *
   * <p>The stamp is what optimistic concurrency compares. It is separate from {@code
   * MemoryRecord.version()} because that is a domain concept owned by C17 — an adapter that
   * reinterpreted it would be making a policy decision, which is precisely what an adapter must not
   * do.
   *
   * @param record the record
   * @param stamp a monotonically increasing write stamp, unique within the partition
   */
  record Stamped(MemoryRecord record, long stamp) {}

  private final String tenantKey;
  private final SegmentLog log;
  private final ReentrantLock writeLock = new ReentrantLock();
  private final AtomicLong stamps = new AtomicLong();

  /** Live records by id. Read without a lock; written only under {@link #writeLock}. */
  private final Map<String, Stamped> records = new ConcurrentHashMap<>();

  /** Write key to record id, for idempotence (AD-026 A2). */
  private final Map<String, String> byWriteKey = new ConcurrentHashMap<>();

  /** The reverse, so a delete can drop the write key without scanning. */
  private final Map<String, String> writeKeyByRecord = new ConcurrentHashMap<>();

  /** Touch entries appended since the last flush, so a compaction knows when it is worthwhile. */
  private final AtomicLong unflushedTouches = new AtomicLong();

  /**
   * Opens a partition, replaying its log.
   *
   * @param tenantKey the stable tenant key this partition holds
   * @param directory where the segments live
   * @param maxSegmentBytes the segment rotation size
   * @param syncOnAppend whether record writes fsync before returning
   */
  TenantPartition(
      final String tenantKey,
      final Path directory,
      final long maxSegmentBytes,
      final boolean syncOnAppend) {
    this.tenantKey = Preconditions.requireNonBlank(tenantKey, "tenantKey");
    this.log = new SegmentLog(directory, maxSegmentBytes, syncOnAppend, this::replay);
  }

  /** Applies one recovered entry. Called only during construction, so no lock is needed. */
  private void replay(final JournalCodec.Entry entry) {
    switch (entry.kind()) {
      case PUT -> {
        final MemoryRecord record = entry.record().orElseThrow();
        final String id = record.id().value();
        records.put(id, new Stamped(record, stamps.incrementAndGet()));
        entry
            .idempotencyKey()
            .ifPresent(
                key -> {
                  byWriteKey.put(key, id);
                  writeKeyByRecord.put(id, key);
                });
      }
      case DELETE -> {
        final String id = entry.recordId().value();
        records.remove(id);
        final String key = writeKeyByRecord.remove(id);
        if (key != null) {
          byWriteKey.remove(key);
        }
      }
      case TOUCH -> {
        // Replayed onto whatever the record currently is. A touch for a record later deleted is a
        // no-op, which is why deletion order in the log matters and why replay is strictly
        // sequential.
        final Stamped existing = records.get(entry.recordId().value());
        if (existing != null) {
          final MemoryRecord updated =
              withAccess(existing.record(), entry.touchedAt().orElseThrow(), entry.accessCount());
          records.put(entry.recordId().value(), new Stamped(updated, stamps.incrementAndGet()));
        }
      }
    }
  }

  /**
   * Stores a record, or returns the one already stored under the same write key.
   *
   * <p>Atomic: the append, the fsync and the index publish happen as one step under the partition
   * lock, so a reader can never observe a record that is not durable, and recovery can never
   * rebuild an index that differs from the one that was live.
   *
   * @param record the record to store
   * @param writeKey the scope-qualified idempotency key
   * @return what happened
   */
  PutResult put(final MemoryRecord record, final String writeKey) {
    writeLock.lock();
    try {
      final String existingId = byWriteKey.get(writeKey);
      if (existingId != null) {
        final Stamped existing = records.get(existingId);
        if (existing != null) {
          // AD-026 A2. A retried write after a lost acknowledgement is the same write, and nothing
          // is
          // appended — otherwise the log would grow by one entry per retry for no change in state.
          return PutResult.replayed(existing.record());
        }
      }
      log.append(JournalCodec.Entry.put(record, writeKey));
      final String id = record.id().value();
      records.put(id, new Stamped(record, stamps.incrementAndGet()));
      byWriteKey.put(writeKey, id);
      writeKeyByRecord.put(id, writeKey);
      return PutResult.stored(record);
    } finally {
      writeLock.unlock();
    }
  }

  /**
   * Stores a record only if the live one still carries the stamp the caller saw.
   *
   * <p>Genuine optimistic concurrency: a caller reads, decides, and writes back naming the stamp it
   * based its decision on. If another writer intervened the stamp has moved and this returns empty
   * rather than blocking or overwriting. Nothing above the adapter is forced to use it — the port
   * has no such method, and adding one would have changed C17.
   *
   * @param record the record to store
   * @param writeKey the idempotency key
   * @param expectedStamp the stamp the caller observed
   * @return the result, or empty when another writer got there first
   */
  Optional<PutResult> putIfStamp(
      final MemoryRecord record, final String writeKey, final long expectedStamp) {
    writeLock.lock();
    try {
      final Stamped current = records.get(record.id().value());
      final long liveStamp = current == null ? 0L : current.stamp();
      if (liveStamp != expectedStamp) {
        return Optional.empty();
      }
      return Optional.of(put(record, writeKey));
    } finally {
      writeLock.unlock();
    }
  }

  /**
   * Returns a record and the stamp it currently carries.
   *
   * @param id the record
   * @return the stamped record, or empty when absent
   */
  Optional<Stamped> stamped(final MemoryRecordId id) {
    return Optional.ofNullable(records.get(id.value()));
  }

  /**
   * Returns a record.
   *
   * @param id the record
   * @return the record, or empty when absent
   */
  Optional<MemoryRecord> get(final MemoryRecordId id) {
    final Stamped found = records.get(id.value());
    return found == null ? Optional.empty() : Optional.of(found.record());
  }

  /**
   * Returns every live record in this partition.
   *
   * @return the records; a snapshot of the map's values, safe to iterate while writes continue
   */
  Collection<MemoryRecord> all() {
    final List<MemoryRecord> live = new ArrayList<>(records.size());
    for (final Stamped stamped : records.values()) {
      live.add(stamped.record());
    }
    return live;
  }

  /**
   * Removes a record.
   *
   * @param id the record to remove
   * @return true when something was removed
   */
  boolean delete(final MemoryRecordId id) {
    writeLock.lock();
    try {
      if (!records.containsKey(id.value())) {
        // AD-026 A7: idempotent. Deleting something already gone is not an error, and appending a
        // tombstone for a record that never existed would grow the log for nothing.
        return false;
      }
      log.append(JournalCodec.Entry.delete(id));
      records.remove(id.value());
      final String writeKey = writeKeyByRecord.remove(id.value());
      if (writeKey != null) {
        byWriteKey.remove(writeKey);
      }
      return true;
    } finally {
      writeLock.unlock();
    }
  }

  /**
   * Records that a record was read.
   *
   * <p>Deliberately <b>not</b> fsynced per call. A touch happens on every hit of every read, and
   * syncing each one would make reads pay a disk round trip to update a ranking signal. The port
   * declares touch best-effort for exactly this reason, so the cost of a crash is a few lost access
   * counts — which degrades ranking slightly and loses no memory.
   *
   * @param id the record read
   * @param at when
   */
  void touch(final MemoryRecordId id, final Instant at) {
    writeLock.lock();
    try {
      final Stamped current = records.get(id.value());
      if (current == null) {
        return;
      }
      final long count = current.record().accessCount() + 1L;
      log.append(JournalCodec.Entry.touch(id, at, count));
      records.put(
          id.value(),
          new Stamped(withAccess(current.record(), at, count), stamps.incrementAndGet()));
      unflushedTouches.incrementAndGet();
    } finally {
      writeLock.unlock();
    }
  }

  /**
   * Rewrites the log to contain only what is live.
   *
   * <p>Needed because the log is append-only: a much-read record leaves a touch entry per read, and
   * a deleted record leaves its writes and its tombstone forever. Compaction bounds that growth.
   *
   * @return how many bytes the log occupies afterwards
   */
  long compact() {
    writeLock.lock();
    try {
      final List<JournalCodec.Entry> live = new ArrayList<>(records.size());
      for (final Map.Entry<String, Stamped> entry : records.entrySet()) {
        final String writeKey = writeKeyByRecord.getOrDefault(entry.getKey(), entry.getKey());
        live.add(JournalCodec.Entry.put(entry.getValue().record(), writeKey));
      }
      unflushedTouches.set(0L);
      return log.compact(live);
    } finally {
      writeLock.unlock();
    }
  }

  /**
   * Copies this partition's log into a directory.
   *
   * @param target where to copy it
   * @return how many segments were copied
   */
  int snapshotTo(final Path target) {
    writeLock.lock();
    try {
      return log.snapshotTo(target);
    } finally {
      writeLock.unlock();
    }
  }

  /** Forces buffered touch entries to disk. */
  void flush() {
    writeLock.lock();
    try {
      log.flush();
      unflushedTouches.set(0L);
    } finally {
      writeLock.unlock();
    }
  }

  /**
   * Returns how many records this partition holds.
   *
   * @return the live record count
   */
  int size() {
    return records.size();
  }

  /**
   * Returns the tenant key this partition is for.
   *
   * @return the tenant key
   */
  String tenantKey() {
    return tenantKey;
  }

  /**
   * Returns how many times this partition has forced data to disk.
   *
   * @return the sync count
   */
  long syncCount() {
    return log.syncCount();
  }

  /**
   * Returns how large the log is on disk.
   *
   * @return the byte count
   */
  long logBytes() {
    return log.sizeBytes();
  }

  /**
   * Returns how many segments the log spans.
   *
   * @return the segment count
   */
  int segmentCount() {
    return log.segmentCount();
  }

  @Override
  public void close() {
    writeLock.lock();
    try {
      log.close();
    } finally {
      writeLock.unlock();
    }
  }

  /**
   * Applies an access to a record.
   *
   * <p>Written here rather than using {@code MemoryRecord.accessedAt} because replay must set an
   * absolute count from the log, not increment whatever happens to be in memory — replaying a touch
   * twice would otherwise double-count.
   */
  private static MemoryRecord withAccess(
      final MemoryRecord record, final Instant at, final long count) {
    return new MemoryRecord(
        record.id(),
        record.scope(),
        record.type(),
        record.content(),
        record.classification(),
        record.metadata(),
        record.createdAt(),
        record.expiresAt(),
        record.version(),
        record.importance(),
        record.tainted(),
        record.legalHold(),
        record.archived(),
        record.indexPending(),
        Optional.of(at),
        count);
  }
}

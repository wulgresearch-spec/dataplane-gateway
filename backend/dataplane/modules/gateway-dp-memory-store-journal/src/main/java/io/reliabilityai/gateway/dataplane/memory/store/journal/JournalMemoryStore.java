package io.reliabilityai.gateway.dataplane.memory.store.journal;

import io.reliabilityai.gateway.common.Preconditions;
import io.reliabilityai.gateway.dataplane.memory.api.MemoryQuery;
import io.reliabilityai.gateway.dataplane.memory.api.MemoryRecord;
import io.reliabilityai.gateway.dataplane.memory.api.MemoryRecordId;
import io.reliabilityai.gateway.dataplane.memory.api.MemoryScope;
import io.reliabilityai.gateway.dataplane.memory.api.MemoryStorePort;
import io.reliabilityai.gateway.dataplane.memory.api.MemoryStoreUnavailableException;
import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * A durable {@link MemoryStorePort} backed by per-tenant append-only journals (AD-027, closes B22).
 *
 * <p><b>This class owns persistence and nothing else.</b> It does not rank, classify, authorize,
 * decide a TTL, apply a policy, embed anything or interpret a retrieval strategy. Every one of
 * those belongs to C17 and none of them appears here — which is the property that lets the memory
 * runtime stay storage-neutral while this stays storage-specific.
 *
 * <p><b>What it does provide</b>, against AD-026 §12.3:
 *
 * <ul>
 *   <li><b>A1</b> — {@code put} fsyncs before returning. Never "queued".
 *   <li><b>A2</b> — {@code put} is idempotent on the scope-qualified write key.
 *   <li><b>A3</b> — {@code search} reaches only the caller's tenant partition, and re-checks
 *       visibility within it.
 *   <li><b>A4</b> — unavailability throws; it is never an empty result.
 *   <li><b>A5</b> — the query limit is honoured.
 *   <li><b>A6</b> — records round-trip byte-identical through the codec.
 *   <li><b>A7</b> — {@code delete} is idempotent and reports whether anything went.
 * </ul>
 *
 * <p><b>Scope limit, stated plainly.</b> Live records are held in memory and the journal is the
 * durable record of truth, so capacity is bounded by heap rather than by disk. That is the right
 * trade for a single-node deployment and the wrong one for a very large tenant; AD-027 §9 records
 * it as a blocker rather than leaving it to be discovered.
 */
public final class JournalMemoryStore implements MemoryStorePort, AutoCloseable {

  /** How large a segment grows before the log rotates. */
  private static final long DEFAULT_SEGMENT_BYTES = 8L * 1024 * 1024;

  private final Path root;
  private final long maxSegmentBytes;
  private final boolean syncOnAppend;
  private final Map<String, TenantPartition> partitions = new ConcurrentHashMap<>();
  private final AtomicBoolean available = new AtomicBoolean(true);
  private final AtomicBoolean closed = new AtomicBoolean();

  /**
   * Opens a store, recovering every tenant partition already on disk.
   *
   * <p>Recovery happens here rather than lazily, so a node with a corrupt journal finds out at
   * startup instead of during the first request that happens to touch it.
   *
   * @param root the directory to hold tenant partitions under; created if absent
   */
  public JournalMemoryStore(final Path root) {
    this(root, DEFAULT_SEGMENT_BYTES, true);
  }

  /**
   * Opens a store with explicit durability and rotation settings.
   *
   * @param root the directory to hold tenant partitions under
   * @param maxSegmentBytes the size at which a segment rotates
   * @param syncOnAppend whether record writes fsync before returning; <b>false is not durable</b>
   *     and exists only so a benchmark can separate the cost of the engine from the cost of the
   *     disk
   */
  public JournalMemoryStore(
      final Path root, final long maxSegmentBytes, final boolean syncOnAppend) {
    this.root = Preconditions.requireNonNull(root, "root");
    this.maxSegmentBytes = maxSegmentBytes;
    this.syncOnAppend = syncOnAppend;
    try {
      Files.createDirectories(root);
    } catch (final IOException failure) {
      throw new MemoryStoreUnavailableException("cannot create store root " + root, failure);
    }
    recoverPartitions();
  }

  // ---- MemoryStorePort ----------------------------------------------------------------------

  @Override
  public PutResult put(final MemoryRecord record, final String idempotencyKey) {
    Preconditions.requireNonNull(record, "record");
    Preconditions.requireNonBlank(idempotencyKey, "idempotencyKey");
    requireAvailable("put");
    return partitionFor(record.scope()).put(record, idempotencyKey);
  }

  @Override
  public Optional<MemoryRecord> get(final MemoryScope scope, final MemoryRecordId id) {
    Preconditions.requireNonNull(scope, "scope");
    Preconditions.requireNonNull(id, "id");
    requireAvailable("get");

    final TenantPartition partition = partitions.get(tenantKeyOf(scope));
    if (partition == null) {
      return Optional.empty();
    }
    // Found within the tenant, then checked for visibility. A record the caller may not see is
    // reported exactly as one that does not exist — distinguishing them would make this an oracle
    // for
    // the existence of other principals' records (AD-026 §10.3).
    return partition.get(id).filter(record -> record.scope().visibleTo(scope));
  }

  @Override
  public List<ScoredRecord> search(final MemoryQuery query) {
    Preconditions.requireNonNull(query, "query");
    requireAvailable("search");

    final TenantPartition partition = partitions.get(tenantKeyOf(query.scope()));
    if (partition == null) {
      return List.of();
    }

    final List<ScoredRecord> matches = new ArrayList<>();
    for (final MemoryRecord record : partition.all()) {
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
    // AD-026 A5. Truncated here rather than left to the caller: returning more than asked for would
    // hand the runtime an unbounded allocation it explicitly bounded.
    return matches.size() <= query.limit()
        ? List.copyOf(matches)
        : List.copyOf(matches.subList(0, query.limit()));
  }

  @Override
  public boolean delete(final MemoryScope scope, final MemoryRecordId id) {
    Preconditions.requireNonNull(scope, "scope");
    Preconditions.requireNonNull(id, "id");
    requireAvailable("delete");

    final TenantPartition partition = partitions.get(tenantKeyOf(scope));
    if (partition == null) {
      return false;
    }
    // Visibility is checked before removal, so a caller cannot delete a record it could not have
    // read.
    final Optional<MemoryRecord> target =
        partition.get(id).filter(record -> record.scope().visibleTo(scope));
    return target.isPresent() && partition.delete(id);
  }

  @Override
  public List<MemoryRecord> expired(final Instant now, final int limit) {
    Preconditions.requireNonNull(now, "now");
    requireAvailable("expired");

    final List<MemoryRecord> due = new ArrayList<>();
    // Across every tenant: the sweeper is a platform actor, not a tenant one. Records under legal
    // hold
    // are returned too — filtering them here would put a compliance rule inside the adapter, and
    // the
    // sweeper checks the hold itself on every path (AD-026 MEM-20).
    for (final TenantPartition partition : partitions.values()) {
      for (final MemoryRecord record : partition.all()) {
        if (record.expiredAt(now)) {
          due.add(record);
        }
      }
    }
    due.sort(Comparator.comparing(MemoryRecord::createdAt).thenComparing(r -> r.id().value()));
    return due.size() <= limit ? List.copyOf(due) : List.copyOf(due.subList(0, limit));
  }

  @Override
  public void touch(final MemoryScope scope, final MemoryRecordId id, final Instant at) {
    Preconditions.requireNonNull(scope, "scope");
    Preconditions.requireNonNull(id, "id");
    Preconditions.requireNonNull(at, "at");
    if (!available.get()) {
      // Best-effort by contract: a failure to record an access must not fail the read that caused
      // it.
      return;
    }
    final TenantPartition partition = partitions.get(tenantKeyOf(scope));
    if (partition == null) {
      return;
    }
    if (partition.get(id).filter(record -> record.scope().visibleTo(scope)).isPresent()) {
      partition.touch(id, at);
    }
  }

  // ---- Adapter-specific operations, deliberately outside the port ------------------------------

  /**
   * Rewrites every partition's log to contain only live records.
   *
   * <p>Not on the port, because compaction is a property of an append-only engine and meaningless
   * to a store that updates in place. Exposing it through {@code MemoryStorePort} would leak this
   * engine's model into the runtime and make a portable record impossible.
   *
   * @return how many bytes the store occupies afterwards
   */
  public long compact() {
    requireAvailable("compact");
    long total = 0L;
    for (final TenantPartition partition : partitions.values()) {
      total += partition.compact();
    }
    return total;
  }

  /**
   * Copies the whole store into a directory, as a point-in-time snapshot.
   *
   * <p>Consistent per partition rather than globally: each partition is copied while holding its
   * own write lock, so no partition is captured mid-write, but two partitions may be captured
   * microseconds apart. A globally consistent snapshot would need a store-wide write barrier, which
   * would stall every tenant to serve one — the wrong trade for a multi-tenant plane, and AD-027 §7
   * says so.
   *
   * @param target where to write the snapshot
   * @return how many partitions were captured
   */
  public int snapshotTo(final Path target) {
    Preconditions.requireNonNull(target, "target");
    requireAvailable("snapshot");
    try {
      Files.createDirectories(target);
    } catch (final IOException failure) {
      throw new MemoryStoreUnavailableException("cannot create snapshot directory", failure);
    }
    int captured = 0;
    for (final TenantPartition partition : partitions.values()) {
      partition.snapshotTo(target.resolve(partition.tenantKey()));
      captured++;
    }
    return captured;
  }

  /**
   * Attempts a version-checked write.
   *
   * <p>Optimistic concurrency, offered as an adapter capability rather than a port method. A caller
   * reads a record with {@link #stampOf}, decides, and writes back naming the stamp it saw; if
   * another writer intervened this returns empty instead of overwriting. Nothing in C17 uses it,
   * and that is the point — adding it to the port would have changed the memory runtime, which this
   * milestone forbids.
   *
   * @param record the record to store
   * @param idempotencyKey the write key
   * @param expectedStamp the stamp the caller observed
   * @return the result, or empty when the record changed underneath
   */
  public Optional<PutResult> putIfUnchanged(
      final MemoryRecord record, final String idempotencyKey, final long expectedStamp) {
    Preconditions.requireNonNull(record, "record");
    Preconditions.requireNonBlank(idempotencyKey, "idempotencyKey");
    requireAvailable("putIfUnchanged");
    return partitionFor(record.scope()).putIfStamp(record, idempotencyKey, expectedStamp);
  }

  /**
   * Returns the concurrency stamp a record currently carries.
   *
   * @param scope the record's scope
   * @param id the record
   * @return the stamp, or empty when the record is absent or invisible
   */
  public Optional<Long> stampOf(final MemoryScope scope, final MemoryRecordId id) {
    Preconditions.requireNonNull(scope, "scope");
    Preconditions.requireNonNull(id, "id");
    final TenantPartition partition = partitions.get(tenantKeyOf(scope));
    if (partition == null) {
      return Optional.empty();
    }
    return partition
        .stamped(id)
        .filter(stamped -> stamped.record().scope().visibleTo(scope))
        .map(TenantPartition.Stamped::stamp);
  }

  /**
   * Forces buffered access counters to disk.
   *
   * @return this store, for chaining in a shutdown sequence
   */
  public JournalMemoryStore flush() {
    for (final TenantPartition partition : partitions.values()) {
      partition.flush();
    }
    return this;
  }

  /**
   * Simulates the storage becoming unreachable.
   *
   * <p>Present so that fail-closed behaviour is testable. AD-026 A4 says unavailability must throw
   * rather than return empty, and that claim is worth nothing without a way to take the store away.
   *
   * @param up whether the store should respond
   */
  public void setAvailable(final boolean up) {
    available.set(up);
  }

  /**
   * Returns how many records the store holds across every tenant.
   *
   * @return the live record count
   */
  public int size() {
    int total = 0;
    for (final TenantPartition partition : partitions.values()) {
      total += partition.size();
    }
    return total;
  }

  /**
   * Returns how many tenant partitions exist.
   *
   * @return the partition count
   */
  public int partitionCount() {
    return partitions.size();
  }

  /**
   * Returns how many times the store has forced data to disk.
   *
   * <p>Reported so that "every acknowledged write is durable" is checkable rather than merely
   * stated. It counts calls, not platters: a filesystem that lies about {@code fsync} will still
   * lie, and no in-process test can catch that (AD-027 §9).
   *
   * @return the sync count across every partition
   */
  public long syncCount() {
    long total = 0L;
    for (final TenantPartition partition : partitions.values()) {
      total += partition.syncCount();
    }
    return total;
  }

  /**
   * Returns how large the store is on disk.
   *
   * @return the byte count across every partition's log
   */
  public long diskBytes() {
    long total = 0L;
    for (final TenantPartition partition : partitions.values()) {
      total += partition.logBytes();
    }
    return total;
  }

  @Override
  public void close() {
    if (!closed.compareAndSet(false, true)) {
      return;
    }
    for (final TenantPartition partition : partitions.values()) {
      partition.close();
    }
  }

  // ---- internals ---------------------------------------------------------------------------

  private TenantPartition partitionFor(final MemoryScope scope) {
    return partitions.computeIfAbsent(
        tenantKeyOf(scope),
        key -> new TenantPartition(key, root.resolve(key), maxSegmentBytes, syncOnAppend));
  }

  /**
   * The directory name a tenant's partition lives under.
   *
   * <p>Derived from organisation and tenant only — never workspace, owner or partition. Those are
   * narrower dimensions the runtime already enforces, and putting them in the path would create a
   * directory per user, which is a filesystem problem rather than an isolation improvement.
   *
   * <p>Sanitised for containment and suffixed with a digest of the raw value. Sanitising alone is
   * not injective: {@code a/b} and {@code a-b} collapse to one name, and two tenants sharing a
   * partition would be the exact cross-tenant failure this design exists to prevent.
   */
  private static String tenantKeyOf(final MemoryScope scope) {
    // Length-prefixed, so no two org/tenant pairs can produce the same digest input. A plain
    // separator character would let ("ab","c") and ("a","bc") collide, which for a tenant boundary
    // means two tenants sharing one partition.
    final String raw =
        scope.tenant().org().length() + ":" + scope.tenant().org() + ":" + scope.tenant().tenant();
    return sanitize(scope.tenant().org())
        + "-"
        + sanitize(scope.tenant().tenant())
        + "-"
        + shortDigest(raw);
  }

  private static String sanitize(final String raw) {
    final StringBuilder safe = new StringBuilder(raw.length());
    for (int i = 0; i < raw.length() && i < 48; i++) {
      final char c = raw.charAt(i);
      final boolean ok =
          (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9') || c == '_';
      safe.append(ok ? c : '_');
    }
    return safe.length() == 0 ? "_" : safe.toString();
  }

  private static String shortDigest(final String raw) {
    try {
      final byte[] digest =
          java.security.MessageDigest.getInstance("SHA-256")
              .digest(raw.getBytes(java.nio.charset.StandardCharsets.UTF_8));
      final StringBuilder hex = new StringBuilder(12);
      for (int i = 0; i < 6; i++) {
        hex.append(String.format("%02x", digest[i]));
      }
      return hex.toString();
    } catch (final java.security.NoSuchAlgorithmException impossible) {
      throw new IllegalStateException("SHA-256 is required of every Java platform", impossible);
    }
  }

  private void recoverPartitions() {
    try (DirectoryStream<Path> found = Files.newDirectoryStream(root)) {
      for (final Path candidate : found) {
        if (Files.isDirectory(candidate)) {
          final String key = candidate.getFileName().toString();
          partitions.put(key, new TenantPartition(key, candidate, maxSegmentBytes, syncOnAppend));
        }
      }
    } catch (final IOException failure) {
      throw new MemoryStoreUnavailableException("cannot scan store root " + root, failure);
    }
  }

  /**
   * A deliberately simple relevance figure: how often the query text occurs.
   *
   * <p>Unbounded, like a real keyword engine's. The runtime normalises within a result set and
   * fuses on rank precisely so that an adapter's scale cannot decide relevance (AD-026 §7.3) — so
   * this being crude changes ordering quality, never correctness.
   *
   * <p>A sealed body is not searched. Matching ciphertext would be meaningless, and decrypting to
   * search would put key handling inside a storage adapter, which MEM-8 forbids.
   */
  private static double relevance(final MemoryRecord record, final MemoryQuery query) {
    if (!query.mode().needsText()) {
      return 0.0d;
    }
    final String needle = query.text().orElse("").toLowerCase(Locale.ROOT);
    if (needle.isBlank() || !record.content().readable()) {
      return 0.0d;
    }
    final String haystack = record.content().body().toLowerCase(Locale.ROOT);
    int count = 0;
    int at = haystack.indexOf(needle);
    while (at >= 0) {
      count++;
      at = haystack.indexOf(needle, at + needle.length());
    }
    return count;
  }

  private void requireAvailable(final String operation) {
    if (closed.get()) {
      throw new MemoryStoreUnavailableException("store is closed: " + operation);
    }
    if (!available.get()) {
      // AD-026 A4. Thrown, never an empty result: an empty result is indistinguishable from "this
      // tenant has no memories", which reads as data loss and, in a write-if-absent flow, causes
      // it.
      throw new MemoryStoreUnavailableException("journal store marked unavailable: " + operation);
    }
  }
}

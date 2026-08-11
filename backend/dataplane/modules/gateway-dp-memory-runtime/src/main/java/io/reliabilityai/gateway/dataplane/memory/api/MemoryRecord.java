package io.reliabilityai.gateway.dataplane.memory.api;

import io.reliabilityai.gateway.common.Preconditions;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;

/**
 * One stored memory (AD-026 §4).
 *
 * <p>Immutable, and <b>portable</b> (MEM-4): every field is a plain value, so any conforming
 * adapter can store and return the record without loss. There is no adapter-specific field, no
 * technology handle and nothing that only one store could represent.
 *
 * @param id the record identity
 * @param scope where it lives and who may reach it; always complete (MEM-14)
 * @param type which of the seven kinds this is
 * @param content the body, sealed or plain, with its plaintext digest
 * @param classification what the classifier determined, never what the caller claimed
 * @param metadata caller-supplied predicates, held in a deterministic order
 * @param createdAt when it was first written
 * @param expiresAt when it becomes invisible, or empty when it does not expire
 * @param version the version number, starting at 1
 * @param importance the caller's declared significance, already clamped by policy
 * @param tainted whether the content is untrusted; always true for {@link MemoryType#TOOL}
 * @param legalHold whether deletion is suspended for this record
 * @param archived whether it has moved to cold storage
 * @param indexPending whether the vector index write has not yet succeeded
 * @param lastAccessedAt when it was last read, or empty when never
 * @param accessCount how many times it has been read
 */
public record MemoryRecord(
    MemoryRecordId id,
    MemoryScope scope,
    MemoryType type,
    MemoryContent content,
    DataClassification classification,
    Map<String, String> metadata,
    Instant createdAt,
    Optional<Instant> expiresAt,
    int version,
    double importance,
    boolean tainted,
    boolean legalHold,
    boolean archived,
    boolean indexPending,
    Optional<Instant> lastAccessedAt,
    long accessCount) {

  /**
   * The most metadata entries a record may carry. Cardinality is a cost, and an unbounded one is a
   * DoS.
   */
  public static final int MAX_METADATA_ENTRIES = 64;

  /**
   * Validates and canonicalises the record.
   *
   * @param id the record identity
   * @param scope the complete scope
   * @param type the memory kind
   * @param content the body
   * @param classification the established classification
   * @param metadata the caller predicates
   * @param createdAt the creation instant
   * @param expiresAt the expiry instant, if any
   * @param version the version number
   * @param importance the clamped importance
   * @param tainted the taint flag
   * @param legalHold the hold flag
   * @param archived the archive flag
   * @param indexPending whether indexing is outstanding
   * @param lastAccessedAt the last read instant, if any
   * @param accessCount the read count
   */
  public MemoryRecord {
    Preconditions.requireNonNull(id, "id");
    Preconditions.requireNonNull(scope, "scope");
    Preconditions.requireNonNull(type, "type");
    Preconditions.requireNonNull(content, "content");
    Preconditions.requireNonNull(classification, "classification");
    Preconditions.requireNonNull(createdAt, "createdAt");
    Preconditions.requireNonNull(expiresAt, "expiresAt");
    Preconditions.requireNonNull(lastAccessedAt, "lastAccessedAt");
    Preconditions.requireNonNegative(accessCount, "accessCount");
    if (version < 1) {
      throw new IllegalArgumentException("version must be >= 1, was " + version);
    }
    if (importance < 0.0d || importance > 1.0d) {
      throw new IllegalArgumentException("importance must be within [0,1], was " + importance);
    }
    Preconditions.requireNonNull(metadata, "metadata");
    if (metadata.size() > MAX_METADATA_ENTRIES) {
      throw new IllegalArgumentException(
          "metadata exceeds " + MAX_METADATA_ENTRIES + " entries: " + metadata.size());
    }
    // Sorted, not merely copied. Two nodes building the same record must produce byte-identical
    // serializations, and Map.copyOf gives no ordering guarantee.
    metadata = java.util.Collections.unmodifiableSortedMap(new TreeMap<>(metadata));
    // A credential is never storable, whatever a policy says (AD-026 §10). Enforced in the
    // constructor
    // so no code path anywhere can produce such a record.
    if (!classification.storable()) {
      throw new IllegalArgumentException(
          "content classified " + classification + " may never be stored as memory");
    }
    // The one type whose taint a caller cannot argue with.
    if (type.alwaysTainted() && !tainted) {
      throw new IllegalArgumentException(type + " memories are tainted by construction");
    }
  }

  /**
   * Reports whether this record has expired at a given instant.
   *
   * <p>Evaluated on read as well as by the sweeper (AD-026 §11). A record must not remain visible
   * past its expiry merely because a background pass is late.
   *
   * @param now the instant to evaluate at, from the injected clock
   * @return true when the record is past its expiry
   */
  public boolean expiredAt(final Instant now) {
    Preconditions.requireNonNull(now, "now");
    return expiresAt.map(expiry -> !now.isBefore(expiry)).orElse(false);
  }

  /**
   * Reports whether this record may be deleted, expired or archived.
   *
   * <p>MEM-20 is absolute: a legal hold survives TTL expiry, archival, supersession, explicit
   * deletion and tenant offboarding. Every removal path calls this, unconditionally.
   *
   * @return false when a legal hold is in force
   */
  public boolean removable() {
    return !legalHold;
  }

  /**
   * Returns the record with a read recorded against it.
   *
   * <p>Feeds the {@code RECENCY_OF_USE} and {@code USAGE_FREQUENCY} ranking signals. Returns a new
   * record — the type is immutable, and a ranking signal that mutated the thing it ranked would
   * make two identical queries return different orders.
   *
   * @param at the instant of the read
   * @return a new record with the access recorded
   */
  public MemoryRecord accessedAt(final Instant at) {
    Preconditions.requireNonNull(at, "at");
    return new MemoryRecord(
        id,
        scope,
        type,
        content,
        classification,
        metadata,
        createdAt,
        expiresAt,
        version,
        importance,
        tainted,
        legalHold,
        archived,
        indexPending,
        Optional.of(at),
        accessCount + 1);
  }

  /**
   * Returns the record with the vector index marked written.
   *
   * @return a new record with {@code indexPending} cleared
   */
  public MemoryRecord indexed() {
    return new MemoryRecord(
        id,
        scope,
        type,
        content,
        classification,
        metadata,
        createdAt,
        expiresAt,
        version,
        importance,
        tainted,
        legalHold,
        archived,
        false,
        lastAccessedAt,
        accessCount);
  }

  /**
   * Returns the record under a legal hold.
   *
   * @param held whether the hold is in force
   * @return a new record with the hold applied or released
   */
  public MemoryRecord withLegalHold(final boolean held) {
    return new MemoryRecord(
        id,
        scope,
        type,
        content,
        classification,
        metadata,
        createdAt,
        expiresAt,
        version,
        importance,
        tainted,
        held,
        archived,
        indexPending,
        lastAccessedAt,
        accessCount);
  }

  /**
   * Returns the record marked archived.
   *
   * @return a new archived record
   * @throws IllegalStateException when a legal hold forbids it
   */
  public MemoryRecord archivedRecord() {
    if (!removable()) {
      throw new IllegalStateException(
          "record " + id + " is under legal hold and cannot be archived");
    }
    return new MemoryRecord(
        id,
        scope,
        type,
        content,
        classification,
        metadata,
        createdAt,
        expiresAt,
        version,
        importance,
        tainted,
        legalHold,
        true,
        indexPending,
        lastAccessedAt,
        accessCount);
  }

  /**
   * Returns the age of this record.
   *
   * @param now the instant to measure to
   * @return the elapsed duration since creation, never negative
   */
  public java.time.Duration ageAt(final Instant now) {
    Preconditions.requireNonNull(now, "now");
    final java.time.Duration age = java.time.Duration.between(createdAt, now);
    return age.isNegative() ? java.time.Duration.ZERO : age;
  }
}

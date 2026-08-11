package io.reliabilityai.gateway.dataplane.memory.api;

import io.reliabilityai.gateway.canonical.identity.CorrelationId;
import io.reliabilityai.gateway.canonical.identity.PrincipalId;
import io.reliabilityai.gateway.common.ContentFree;
import io.reliabilityai.gateway.common.Preconditions;
import java.time.Instant;
import java.util.Optional;

/**
 * Where memory facts are recorded (MEM-12, MEM-23, MEM-26).
 *
 * <p>Every event is {@link ContentFree}: digests, scopes, classifications and counts, never tenant
 * content. An audit trail that quoted what it audited would be a second copy of the data, with its
 * own retention obligation and its own breach radius, and it would survive the deletion of the
 * thing it described.
 *
 * <p><b>Emitted for every admitted operation, including reads that matched nothing</b> (AD-026
 * §6.2). An access log with holes where the misses were is not an access log — and a miss is
 * precisely the signal that matters when someone is probing for records they cannot see.
 *
 * <p>The runtime introduces no new sink. An adapter feeds C10 Audit, under the retention and
 * redaction rules that already exist.
 */
public interface MemoryAuditPort {

  /** An audit port that discards everything. For tests and for a deployment with no sink wired. */
  MemoryAuditPort NOOP = event -> {};

  /** What kind of thing happened. */
  enum EventType {
    /** A memory was stored. */
    WRITE,
    /** A query ran, whether or not it matched. */
    READ,
    /** A memory was destroyed, leaving proof. */
    DELETE,
    /** An operation was refused. */
    REFUSAL,
    /** A memory expired and was swept. */
    EXPIRY,
    /** A memory moved to cold storage. */
    ARCHIVE,
    /** A legal hold was applied or released. */
    LEGAL_HOLD
  }

  /**
   * One immutable, content-free memory fact.
   *
   * @param type what happened
   * @param stage which pipeline stage produced the event
   * @param principal who did it
   * @param scopeKey the stable scope key, which identifies the partition without naming its
   *     contents
   * @param memoryType which memory kind, when the event concerns one
   * @param recordId which record, when the event concerns one
   * @param contentDigest the content digest, when the event concerns a body
   * @param classification what the classifier established, when known
   * @param reasonCode a stable, low-cardinality outcome code
   * @param resultCount how many records the operation touched or returned
   * @param correlationId the caller's correlation, passed through unchanged
   * @param at when it happened, from the injected clock
   */
  record MemoryAuditEvent(
      EventType type,
      MemoryStage stage,
      PrincipalId principal,
      String scopeKey,
      Optional<MemoryType> memoryType,
      Optional<MemoryRecordId> recordId,
      Optional<String> contentDigest,
      Optional<DataClassification> classification,
      String reasonCode,
      int resultCount,
      CorrelationId correlationId,
      Instant at)
      implements ContentFree {

    /**
     * Validates the event.
     *
     * @param type the event kind
     * @param stage the producing stage
     * @param principal the acting principal
     * @param scopeKey the scope key
     * @param memoryType the memory kind, if any
     * @param recordId the record, if any
     * @param contentDigest the content digest, if any
     * @param classification the classification, if known
     * @param reasonCode the outcome code
     * @param resultCount the touched or returned count
     * @param correlationId the caller correlation
     * @param at the recording instant
     */
    public MemoryAuditEvent {
      Preconditions.requireNonNull(type, "type");
      Preconditions.requireNonNull(stage, "stage");
      Preconditions.requireNonNull(principal, "principal");
      Preconditions.requireNonBlank(scopeKey, "scopeKey");
      Preconditions.requireNonNull(memoryType, "memoryType");
      Preconditions.requireNonNull(recordId, "recordId");
      Preconditions.requireNonNull(contentDigest, "contentDigest");
      Preconditions.requireNonNull(classification, "classification");
      Preconditions.requireNonBlank(reasonCode, "reasonCode");
      Preconditions.requireNonNull(correlationId, "correlationId");
      Preconditions.requireNonNull(at, "at");
      if (resultCount < 0) {
        throw new IllegalArgumentException("resultCount must be non-negative");
      }
    }
  }

  /**
   * Records one fact.
   *
   * <p>Called only <em>after</em> the underlying operation is durable, so no audit event can claim
   * a write that a crash then unmakes.
   *
   * @param event the fact to record
   */
  void record(MemoryAuditEvent event);
}

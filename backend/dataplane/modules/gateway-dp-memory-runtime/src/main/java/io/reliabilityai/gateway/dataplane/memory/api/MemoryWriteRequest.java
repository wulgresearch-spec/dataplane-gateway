package io.reliabilityai.gateway.dataplane.memory.api;

import io.reliabilityai.gateway.common.Preconditions;
import java.time.Duration;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;

/**
 * A request to store a memory (AD-026 §5).
 *
 * <p>Note what the caller may <b>ask for</b> and what it may <b>decide</b>. It asks for a TTL and
 * an importance; policy decides both, and may only tighten (AD-026 §5.1, §7.2). It states a scope,
 * but the pipeline narrows that against the caller's authority before anything is stored. It cannot
 * state a classification at all — that is the classifier's job, because a caller that could declare
 * its own content non-personal would have opted out of every PII rule by saying so.
 *
 * @param scope where the memory should live
 * @param type which of the seven kinds
 * @param body the plaintext to store
 * @param metadata caller-supplied predicates for later filtering
 * @param requestedTtl the lifetime the caller would like; policy may shorten it and never lengthens
 *     it
 * @param requestedImportance the significance the caller claims, clamped by policy before use
 * @param writeKey an idempotency key: two writes with the same key in the same scope are one write
 * @param region the region the caller is writing from, checked against residency policy
 */
public record MemoryWriteRequest(
    MemoryScope scope,
    MemoryType type,
    String body,
    Map<String, String> metadata,
    Optional<Duration> requestedTtl,
    double requestedImportance,
    String writeKey,
    String region) {

  /**
   * Validates and canonicalises the request.
   *
   * @param scope the requested scope
   * @param type the memory kind
   * @param body the plaintext
   * @param metadata the caller predicates
   * @param requestedTtl the requested lifetime
   * @param requestedImportance the claimed importance
   * @param writeKey the idempotency key
   * @param region the writing region
   */
  public MemoryWriteRequest {
    Preconditions.requireNonNull(scope, "scope");
    Preconditions.requireNonNull(type, "type");
    Preconditions.requireNonNull(body, "body");
    Preconditions.requireNonNull(requestedTtl, "requestedTtl");
    Preconditions.requireNonBlank(writeKey, "writeKey");
    Preconditions.requireNonBlank(region, "region");
    Preconditions.requireNonNull(metadata, "metadata");
    metadata = java.util.Collections.unmodifiableSortedMap(new TreeMap<>(metadata));

    if (body.length() > MemoryContent.MAX_BYTES) {
      // Refused, never truncated. A silently truncated memory is a wrong memory, and it will be
      // retrieved later by something that has no way to know it is incomplete.
      throw new IllegalArgumentException(
          "body exceeds " + MemoryContent.MAX_BYTES + " characters: " + body.length());
    }
    if (metadata.size() > MemoryRecord.MAX_METADATA_ENTRIES) {
      throw new IllegalArgumentException(
          "metadata exceeds " + MemoryRecord.MAX_METADATA_ENTRIES + " entries");
    }
    if (requestedImportance < 0.0d
        || requestedImportance > 1.0d
        || Double.isNaN(requestedImportance)) {
      throw new IllegalArgumentException(
          "requestedImportance must be within [0,1], was " + requestedImportance);
    }
    if (requestedTtl.isPresent()
        && (requestedTtl.get().isNegative() || requestedTtl.get().isZero())) {
      throw new IllegalArgumentException("requestedTtl must be positive when present");
    }
  }

  /**
   * A write with no TTL preference, default importance and no metadata.
   *
   * @param scope the target scope
   * @param type the memory kind
   * @param body the plaintext
   * @param writeKey the idempotency key
   * @param region the writing region
   * @return the request
   */
  public static MemoryWriteRequest of(
      final MemoryScope scope,
      final MemoryType type,
      final String body,
      final String writeKey,
      final String region) {
    return new MemoryWriteRequest(
        scope, type, body, Map.of(), Optional.empty(), 0.5d, writeKey, region);
  }

  /**
   * Returns this request with its scope replaced by a narrowed one.
   *
   * @param narrowed the narrowed scope
   * @return the request against the narrowed scope
   */
  public MemoryWriteRequest withScope(final MemoryScope narrowed) {
    Preconditions.requireNonNull(narrowed, "narrowed");
    return new MemoryWriteRequest(
        narrowed, type, body, metadata, requestedTtl, requestedImportance, writeKey, region);
  }

  /**
   * Returns this request with its body replaced, as redaction produces.
   *
   * @param redactedBody the body with personal spans removed
   * @return the request carrying the redacted body
   */
  public MemoryWriteRequest withBody(final String redactedBody) {
    Preconditions.requireNonNull(redactedBody, "redactedBody");
    return new MemoryWriteRequest(
        scope, type, redactedBody, metadata, requestedTtl, requestedImportance, writeKey, region);
  }

  /**
   * Returns the stable identity a write key maps to within a scope.
   *
   * <p>Idempotence is per scope, not global: the same key used by two tenants is two different
   * writes, and collapsing them would be a cross-tenant data leak dressed up as deduplication.
   *
   * @return the deduplication key
   */
  public String idempotencyKey() {
    return scope.key() + "|" + type.name() + "|" + writeKey;
  }
}

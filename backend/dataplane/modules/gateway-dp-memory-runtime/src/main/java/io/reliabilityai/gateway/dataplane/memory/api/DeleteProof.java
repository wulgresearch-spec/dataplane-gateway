package io.reliabilityai.gateway.dataplane.memory.api;

import io.reliabilityai.gateway.canonical.identity.PrincipalId;
import io.reliabilityai.gateway.common.ContentFree;
import io.reliabilityai.gateway.common.Preconditions;
import java.time.Instant;

/**
 * Durable evidence that a memory was deleted (MEM-25, AD-026 §10.5).
 *
 * <p>Without one, "we deleted it" is an assertion. With one it is evidence — which is what a
 * regulator, an auditor and a customer exercising erasure rights each actually require, and none of
 * them can be satisfied by the absence of a record.
 *
 * <p><b>The proof does not contain the content.</b> It carries the content's digest, which is
 * enough to confirm that a specific known thing was removed, and not enough to reconstruct it. A
 * proof that quoted what it deleted would be a copy of the thing that was supposed to be gone — the
 * exact failure that makes so many "audit trails" a liability rather than an asset.
 *
 * <p>{@link ContentFree}: safe to emit to telemetry and to retain long after the content is
 * destroyed.
 *
 * @param recordId the record that was deleted
 * @param scopeKey the stable scope key it lived under
 * @param contentDigest the digest of the destroyed content
 * @param deletedBy the principal who performed the deletion
 * @param reason why, as a stable low-cardinality code
 * @param deletedAt when
 * @param proofDigest a digest binding every field above, so tampering with any of them is
 *     detectable
 */
public record DeleteProof(
    MemoryRecordId recordId,
    String scopeKey,
    String contentDigest,
    PrincipalId deletedBy,
    String reason,
    Instant deletedAt,
    String proofDigest)
    implements ContentFree {

  /**
   * Validates the proof.
   *
   * @param recordId the deleted record
   * @param scopeKey the scope key
   * @param contentDigest the destroyed content's digest
   * @param deletedBy the deleting principal
   * @param reason the deletion reason
   * @param deletedAt the deletion instant
   * @param proofDigest the binding digest
   */
  public DeleteProof {
    Preconditions.requireNonNull(recordId, "recordId");
    Preconditions.requireNonBlank(scopeKey, "scopeKey");
    Preconditions.requireNonBlank(contentDigest, "contentDigest");
    Preconditions.requireNonNull(deletedBy, "deletedBy");
    Preconditions.requireNonBlank(reason, "reason");
    Preconditions.requireNonNull(deletedAt, "deletedAt");
    Preconditions.requireNonBlank(proofDigest, "proofDigest");
  }

  /**
   * Returns the canonical string the proof digest is taken over.
   *
   * <p>Length-prefixed so that no two different field combinations can produce the same input —
   * without that, a record id ending in a digit and a scope key beginning with one could be
   * rearranged to forge a matching proof.
   *
   * @return the canonical binding string
   */
  public String bindingString() {
    return bindingString(recordId, scopeKey, contentDigest, deletedBy, reason, deletedAt);
  }

  /**
   * Builds the canonical binding string for a prospective proof.
   *
   * @param recordId the record being deleted
   * @param scopeKey the scope key
   * @param contentDigest the content digest
   * @param deletedBy the deleting principal
   * @param reason the reason code
   * @param deletedAt the deletion instant
   * @return the canonical binding string
   */
  public static String bindingString(
      final MemoryRecordId recordId,
      final String scopeKey,
      final String contentDigest,
      final PrincipalId deletedBy,
      final String reason,
      final Instant deletedAt) {
    final StringBuilder binding = new StringBuilder(160);
    part(binding, recordId.value());
    part(binding, scopeKey);
    part(binding, contentDigest);
    part(binding, deletedBy.value());
    part(binding, reason);
    part(binding, deletedAt.toString());
    return binding.toString();
  }

  /**
   * Verifies that this proof's digest matches its own fields.
   *
   * <p>A proof whose digest does not match has been altered since it was written, and an altered
   * delete proof is worse than none: it asserts a deletion that may not have happened.
   *
   * @param digester the same digest function used to create the proof
   * @return true when the proof is internally consistent
   */
  public boolean verify(final java.util.function.UnaryOperator<String> digester) {
    Preconditions.requireNonNull(digester, "digester");
    return proofDigest.equals(digester.apply(bindingString()));
  }

  private static void part(final StringBuilder binding, final String value) {
    binding.append(value.length()).append(':').append(value).append(';');
  }
}

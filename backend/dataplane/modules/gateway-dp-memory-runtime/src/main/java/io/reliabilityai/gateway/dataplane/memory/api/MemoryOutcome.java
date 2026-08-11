package io.reliabilityai.gateway.dataplane.memory.api;

import io.reliabilityai.gateway.common.Preconditions;
import java.util.List;

/**
 * What a memory operation produced.
 *
 * <p>A sealed result rather than an exception for the ordinary refusals, because a refusal is a
 * normal outcome of a governed system and callers must handle it. Exceptions are reserved for the
 * cases where the runtime genuinely cannot answer — an unreachable store, a corrupt record — which
 * the caller can do nothing about except retry or stop.
 */
public sealed interface MemoryOutcome {

  /**
   * Returns the reason code, suitable for a metrics dimension.
   *
   * @return a short, stable, low-cardinality code
   */
  String reasonCode();

  /**
   * Reports whether the operation did what was asked.
   *
   * @return true only for the success variants
   */
  boolean succeeded();

  /**
   * A write that was stored.
   *
   * @param id the record written
   * @param version the version number written
   * @param sealed whether the content was sealed at rest
   * @param redacted whether personal spans were removed before storing
   * @param indexPending whether the vector index write is still outstanding
   * @param deduplicated whether an identical write key had already stored this
   */
  record Written(
      MemoryRecordId id,
      int version,
      boolean sealed,
      boolean redacted,
      boolean indexPending,
      boolean deduplicated)
      implements MemoryOutcome {

    /**
     * Validates the outcome.
     *
     * @param id the record identity
     * @param version the version written
     * @param sealed whether sealed at rest
     * @param redacted whether redacted
     * @param indexPending whether indexing is outstanding
     * @param deduplicated whether this was an idempotent replay
     */
    public Written {
      Preconditions.requireNonNull(id, "id");
      if (version < 1) {
        throw new IllegalArgumentException("version must be >= 1");
      }
    }

    @Override
    public String reasonCode() {
      return deduplicated ? "written.deduplicated" : "written";
    }

    @Override
    public boolean succeeded() {
      return true;
    }
  }

  /**
   * A read that completed, whether or not it matched anything.
   *
   * @param hits the ranked results, longest-scoring first
   * @param mode the mode actually used, which may differ from the one requested
   * @param degraded whether the vector path was unavailable and the query fell back to keyword
   * @param examined how many records the adapters returned before ranking and policy filtering
   */
  record Retrieved(List<MemoryHit> hits, RetrievalMode mode, boolean degraded, int examined)
      implements MemoryOutcome {

    /**
     * Validates and freezes the outcome.
     *
     * @param hits the ranked results
     * @param mode the mode used
     * @param degraded whether the query degraded
     * @param examined the pre-filter count
     */
    public Retrieved {
      hits = hits == null ? List.of() : List.copyOf(hits);
      Preconditions.requireNonNull(mode, "mode");
      if (examined < 0) {
        throw new IllegalArgumentException("examined must be non-negative");
      }
    }

    @Override
    public String reasonCode() {
      return degraded ? "retrieved.degraded" : "retrieved";
    }

    @Override
    public boolean succeeded() {
      return true;
    }

    /**
     * Reports whether anything matched.
     *
     * <p>An empty result is still a successful read, and still audited (MEM-12). A caller that
     * treats empty as failure will retry, and retrying a correct answer is how a read amplification
     * starts.
     *
     * @return true when no record matched
     */
    public boolean empty() {
      return hits.isEmpty();
    }
  }

  /**
   * A delete that removed content and left proof.
   *
   * @param proof the durable evidence that the deletion happened
   */
  record Deleted(DeleteProof proof) implements MemoryOutcome {

    /**
     * Validates the outcome.
     *
     * @param proof the delete proof
     */
    public Deleted {
      Preconditions.requireNonNull(proof, "proof");
    }

    @Override
    public String reasonCode() {
      return "deleted";
    }

    @Override
    public boolean succeeded() {
      return true;
    }
  }

  /**
   * The operation was refused, and nothing changed.
   *
   * <p>Carries a low-cardinality code rather than a free-text message. A refusal reason that varied
   * per request could not be alerted on, and a refusal nobody can alert on is one nobody notices.
   *
   * @param code the stable refusal code
   * @param stage which pipeline stage refused
   * @param detail an operator-facing explanation, never returned to an untrusted caller verbatim
   */
  record Refused(String code, MemoryStage stage, String detail) implements MemoryOutcome {

    /**
     * Validates the refusal.
     *
     * @param code the stable code
     * @param stage the refusing stage
     * @param detail the explanation
     */
    public Refused {
      Preconditions.requireNonBlank(code, "code");
      Preconditions.requireNonNull(stage, "stage");
      Preconditions.requireNonBlank(detail, "detail");
    }

    @Override
    public String reasonCode() {
      return code;
    }

    @Override
    public boolean succeeded() {
      return false;
    }
  }
}

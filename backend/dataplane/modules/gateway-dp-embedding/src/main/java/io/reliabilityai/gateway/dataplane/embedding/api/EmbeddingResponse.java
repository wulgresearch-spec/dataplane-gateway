package io.reliabilityai.gateway.dataplane.embedding.api;

import io.reliabilityai.gateway.common.Preconditions;
import java.util.List;
import java.util.Optional;

/**
 * The outcome of an embedding request, one entry per input (AD-030 §9).
 *
 * <p><b>Per-input outcomes, not all-or-nothing.</b> A batch of sixty-four in which one input is too
 * long must return sixty-three embeddings and one refusal. Failing the whole batch would make a
 * single oversized record able to block every write batched alongside it, and callers would have no
 * way to tell which input was at fault.
 *
 * @param outcomes one entry per input, in request order
 * @param usage what the whole request consumed
 */
public record EmbeddingResponse(List<Outcome> outcomes, EmbeddingUsage usage) {

  /** What became of one input. */
  public sealed interface Outcome permits Outcome.Embedded, Outcome.Failed {

    /**
     * Whether this input produced an embedding.
     *
     * @return true when embedded
     */
    boolean ok();

    /**
     * An input that was embedded.
     *
     * @param embedding the produced embedding
     */
    record Embedded(CanonicalEmbedding embedding) implements Outcome {

      /**
       * Validates the outcome.
       *
       * @param embedding the produced embedding
       */
      public Embedded {
        Preconditions.requireNonNull(embedding, "embedding");
      }

      @Override
      public boolean ok() {
        return true;
      }
    }

    /**
     * An input that did not produce an embedding.
     *
     * @param reason the neutral failure kind
     * @param detail an operator-facing note that quotes no input text
     */
    record Failed(EmbeddingFailure reason, String detail) implements Outcome {

      /**
       * Validates the outcome.
       *
       * @param reason the failure kind
       * @param detail the note
       */
      public Failed {
        Preconditions.requireNonNull(reason, "reason");
        Preconditions.requireNonNull(detail, "detail");
      }

      @Override
      public boolean ok() {
        return false;
      }
    }
  }

  /**
   * Validates and freezes the response.
   *
   * @param outcomes the per-input outcomes
   * @param usage the consumption record
   */
  public EmbeddingResponse {
    outcomes = List.copyOf(Preconditions.requireNonNull(outcomes, "outcomes"));
    Preconditions.requireNonNull(usage, "usage");
    if (outcomes.isEmpty()) {
      throw new IllegalArgumentException("a response must carry at least one outcome");
    }
  }

  /**
   * The embedding at one position.
   *
   * @param index the input position
   * @return the embedding, or empty when that input failed
   */
  public Optional<CanonicalEmbedding> at(final int index) {
    final Outcome outcome = outcomes.get(index);
    return outcome instanceof Outcome.Embedded embedded
        ? Optional.of(embedded.embedding())
        : Optional.empty();
  }

  /**
   * Whether every input produced an embedding.
   *
   * @return true when nothing failed
   */
  public boolean complete() {
    return outcomes.stream().allMatch(Outcome::ok);
  }

  /**
   * How many inputs produced an embedding.
   *
   * @return the success count
   */
  public int embedded() {
    return (int) outcomes.stream().filter(Outcome::ok).count();
  }

  /**
   * The failures, in request order.
   *
   * @return the failed outcomes
   */
  public List<Outcome.Failed> failures() {
    return outcomes.stream()
        .filter(outcome -> outcome instanceof Outcome.Failed)
        .map(outcome -> (Outcome.Failed) outcome)
        .toList();
  }
}

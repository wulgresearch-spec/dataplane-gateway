package io.reliabilityai.gateway.dataplane.schemalock.api;

import io.reliabilityai.gateway.common.Preconditions;
import io.reliabilityai.gateway.dataplane.schemalock.domain.FailureClass;

/**
 * The StreamGuard consumption seam (Doc 17 §43/§43.1). StreamGuard delivers <b>reconstructed
 * canonical deltas</b> (each a syntactically well-formed incremental fragment, RB-4) and a
 * <b>transport verdict</b>; SchemaLock performs <b>logical-object assembly + schema validation</b>
 * and gates the terminal success (RB-5/RB-6/RB-8). SchemaLock never touches raw bytes, UTF-8,
 * ordering, or dedup — those are StreamGuard's (RB-2/3/12/13, SL-A13).
 */
public interface StreamSourcePort {

  /**
   * Pulls the next stream item in delivered order (Doc 17 §43).
   *
   * @return the next delta, transport completion, or transport failure
   */
  StreamItem next();

  /**
   * A StreamGuard-delivered stream item (Doc 17 §43.1). Sealed: a well-formed delta fragment, a
   * clean transport completion, or a transport failure (Doc 18 §22 mapped to a neutral class).
   */
  sealed interface StreamItem
      permits StreamItem.Delta, StreamItem.TransportComplete, StreamItem.TransportFailed {

    /**
     * A reconstructed, well-formed incremental fragment (RB-4).
     *
     * @param fragment the incremental JSON text fragment
     */
    record Delta(String fragment) implements StreamItem {
      /** Compact constructor validating the fragment. */
      public Delta {
        Preconditions.requireNonNull(fragment, "fragment");
      }
    }

    /**
     * The transport completed cleanly (StreamGuard PROVEN); SchemaLock now completion-validates.
     */
    record TransportComplete() implements StreamItem {}

    /**
     * The transport failed (StreamGuard FAILED); SchemaLock surfaces the mapped class (Doc 17
     * §43.1).
     *
     * @param failureClass the neutral failure class (e.g. {@code TRUNCATED}/{@code MALFORMED})
     */
    record TransportFailed(FailureClass failureClass) implements StreamItem {
      /** Compact constructor validating the class. */
      public TransportFailed {
        Preconditions.requireNonNull(failureClass, "failureClass");
      }
    }
  }
}

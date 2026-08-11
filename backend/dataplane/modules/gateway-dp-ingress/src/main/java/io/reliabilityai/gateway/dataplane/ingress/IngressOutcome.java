package io.reliabilityai.gateway.dataplane.ingress;

import io.reliabilityai.gateway.canonical.io.CanonicalError;
import io.reliabilityai.gateway.canonical.io.CanonicalResponse;
import io.reliabilityai.gateway.common.Preconditions;

/**
 * What the pipeline adapter returns to the server (Doc 30).
 *
 * <p>Expressed in canonical types plus the refusing stage's name. The ingress module deliberately
 * does not know the pipeline's stage enum — it only needs a label to map onto an HTTP status and to
 * report back to the caller, and keeping it a string is what stops the composition root leaking in
 * here.
 */
public sealed interface IngressOutcome
    permits IngressOutcome.Completed, IngressOutcome.Streamed, IngressOutcome.Refused {

  /**
   * The request traversed the pipeline and produced a response.
   *
   * @param response the canonical response
   */
  record Completed(CanonicalResponse response) implements IngressOutcome {

    /** Validates the completion. */
    public Completed {
      Preconditions.requireNonNull(response, "response");
    }
  }

  /**
   * The request was admitted and the provider is streaming; the server writes chunks as they
   * arrive.
   *
   * @param stream the guarded canonical stream
   */
  record Streamed(IngressStream stream) implements IngressOutcome {

    /** Validates the streamed outcome. */
    public Streamed {
      Preconditions.requireNonNull(stream, "stream");
    }
  }

  /**
   * A pipeline stage refused.
   *
   * @param stage the refusing stage's name
   * @param error the canonical error, already content-free
   */
  record Refused(String stage, CanonicalError error) implements IngressOutcome {

    /** Validates the refusal. */
    public Refused {
      Preconditions.requireNonBlank(stage, "stage");
      Preconditions.requireNonNull(error, "error");
    }
  }
}

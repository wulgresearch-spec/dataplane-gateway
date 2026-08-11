package io.reliabilityai.gateway.dataplane.app.pipeline;

import io.reliabilityai.gateway.canonical.io.CanonicalResponse;
import io.reliabilityai.gateway.common.Preconditions;

/**
 * The result of running one request through the pipeline: either a canonical response or a
 * canonical refusal naming the stage that stopped it. Sealed, so a caller cannot forget to handle
 * the refusal path — fail-closed is enforced by the type system rather than by convention.
 */
public sealed interface PipelineOutcome
    permits PipelineOutcome.Completed, PipelineOutcome.Streamed, PipelineOutcome.Refused {

  /**
   * The stage-by-stage record of this request.
   *
   * @return the trace
   */
  StageTrace trace();

  /**
   * The request traversed every mandatory stage and produced a response.
   *
   * @param response the canonical response
   * @param trace the stage trace
   */
  record Completed(CanonicalResponse response, StageTrace trace) implements PipelineOutcome {

    /** Validates the completion. */
    public Completed {
      Preconditions.requireNonNull(response, "response");
      Preconditions.requireNonNull(trace, "trace");
    }
  }

  /**
   * The request was admitted and the provider is streaming. The transport is already guarded; the
   * caller pulls canonical chunks and the remaining stages run as the stream terminates.
   *
   * <p>The trace is still being written when this is returned — the post-transport stages have not
   * run yet. Read it after the stream finishes.
   *
   * @param stream the guarded canonical stream
   * @param trace the stage trace, completed as the stream terminates
   */
  record Streamed(CanonicalStream stream, StageTrace trace) implements PipelineOutcome {

    /** Validates the streamed outcome. */
    public Streamed {
      Preconditions.requireNonNull(stream, "stream");
      Preconditions.requireNonNull(trace, "trace");
    }
  }

  /**
   * A stage refused; no downstream stage ran, nothing was metered and nothing was published.
   *
   * @param refusal the refusing stage and its canonical error
   * @param trace the stage trace, ending at the refusing stage
   */
  record Refused(StageRefusal refusal, StageTrace trace) implements PipelineOutcome {

    /** Validates the refusal. */
    public Refused {
      Preconditions.requireNonNull(refusal, "refusal");
      Preconditions.requireNonNull(trace, "trace");
    }
  }
}

package io.reliabilityai.gateway.ports;

import io.reliabilityai.gateway.canonical.decision.RouteTarget;
import io.reliabilityai.gateway.canonical.io.CanonicalError;
import io.reliabilityai.gateway.canonical.io.CanonicalRequest;
import io.reliabilityai.gateway.canonical.io.CanonicalResponse;
import io.reliabilityai.gateway.canonical.stream.StreamChunk;
import io.reliabilityai.gateway.common.Preconditions;
import java.util.concurrent.Flow;

/**
 * The Provider Adapter port — the Anti-Corruption Layer (C1, Doc 25 §7, AD-002/AD-007). Invoked by
 * Reliability per attempt; translates canonical↔provider, executes one provider attempt, and
 * returns canonical objects only. Provider SDKs/types/names never cross this boundary (Doc 25
 * §PA-D1). One {@code invoke} == exactly one provider attempt (Doc 25 §17.1 TO-5).
 */
public interface ProviderAdapterPort {

  /**
   * Executes exactly one provider attempt and returns a canonical outcome (Doc 25 §7). Fail-closed
   * to a {@link CanonicalError}; the streaming variant carries a consumer-paced canonical stream.
   *
   * @param request the canonical request
   * @param routeTarget the selected route target
   * @param budget the per-attempt transport budget (handed by Reliability)
   * @return a canonical invocation result
   */
  ProviderInvocationResult invoke(
      CanonicalRequest request, RouteTarget routeTarget, AttemptBudget budget);

  /**
   * The canonical outcome of one provider attempt (Doc 25 §6). Sealed: a unary response, a
   * consumer-paced canonical stream (guarded downstream by StreamGuard, Doc 18), or a canonical
   * error (Reliability decides failover, Doc 25 §17.1).
   */
  sealed interface ProviderInvocationResult
      permits ProviderInvocationResult.Unary,
          ProviderInvocationResult.Streaming,
          ProviderInvocationResult.StreamingTransport,
          ProviderInvocationResult.Failed {

    /**
     * A unary (non-streaming) canonical response.
     *
     * @param response the canonical response
     */
    record Unary(CanonicalResponse response) implements ProviderInvocationResult {
      /** Compact constructor validating the response. */
      public Unary {
        Preconditions.requireNonNull(response, "response");
      }
    }

    /**
     * A canonical stream (consumer-paced; StreamGuard guards integrity, Doc 18/25 §31.1).
     *
     * @param chunks the ordered canonical stream publisher
     */
    record Streaming(Flow.Publisher<StreamChunk> chunks) implements ProviderInvocationResult {
      /** Compact constructor validating the publisher. */
      public Streaming {
        Preconditions.requireNonNull(chunks, "chunks");
      }
    }

    /**
     * A provider stream delivered <b>before</b> deframing, so the STREAM_GUARD stage can verify the
     * transport the provider actually produced (Doc 18, AD-018).
     *
     * <p>This is the variant a real streaming provider returns. {@link Streaming} hands over
     * canonical chunks, which means the framing — and with it every property StreamGuard exists to
     * prove — has already been discarded; a stream delivered that way cannot be honestly guarded.
     * This variant keeps the original bytes intact until the guard has seen them, and carries the
     * provider's own decoder so the pipeline can turn verified payloads into canonical chunks
     * without learning the provider's dialect.
     *
     * @param source the provider's raw, still-framed transport stream
     * @param decoder the provider's decoder for already-guarded payloads
     */
    record StreamingTransport(ProviderTransportStream source, ProviderStreamDecoder decoder)
        implements ProviderInvocationResult {
      /** Compact constructor validating the stream and its decoder. */
      public StreamingTransport {
        Preconditions.requireNonNull(source, "source");
        Preconditions.requireNonNull(decoder, "decoder");
      }
    }

    /**
     * A fail-closed canonical provider error (Reliability decides retry/failover).
     *
     * @param error the canonical error
     */
    record Failed(CanonicalError error) implements ProviderInvocationResult {
      /** Compact constructor validating the error. */
      public Failed {
        Preconditions.requireNonNull(error, "error");
      }
    }
  }
}

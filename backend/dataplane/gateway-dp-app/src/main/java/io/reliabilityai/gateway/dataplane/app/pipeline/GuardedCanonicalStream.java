package io.reliabilityai.gateway.dataplane.app.pipeline;

import io.reliabilityai.gateway.canonical.io.CanonicalUsage;
import io.reliabilityai.gateway.canonical.io.FinishReason;
import io.reliabilityai.gateway.canonical.io.UsageClass;
import io.reliabilityai.gateway.canonical.stream.StreamChunk;
import io.reliabilityai.gateway.canonical.stream.StreamState;
import io.reliabilityai.gateway.common.Preconditions;
import io.reliabilityai.gateway.dataplane.streamguard.api.TransportEvent;
import io.reliabilityai.gateway.dataplane.streamguard.api.TransportSession;
import io.reliabilityai.gateway.dataplane.streamguard.api.TransportVerdict;
import io.reliabilityai.gateway.ports.ProviderStreamDecoder;
import java.util.Optional;

/**
 * The canonical stream the pipeline hands back for a guarded provider response.
 *
 * <p>Each verified payload is decoded and forwarded the moment StreamGuard releases it — nothing is
 * accumulated for delivery, so first-token latency is the provider's, not the gateway's. What
 * <em>is</em> accumulated is only what the later stages need: the concatenated text for SchemaLock
 * and the usage the provider reported, both content-bounded by StreamGuard's own session limits.
 *
 * <p><b>An honest property of streaming integrity.</b> StreamGuard validates incrementally and can
 * only <em>prove</em> a stream intact once it completes. A stream that fails at frame 500 will
 * already have forwarded frames 1–499. That is inherent to streaming, not a shortcut: the
 * alternative is buffering the whole response, which defeats streaming entirely. What the gateway
 * guarantees instead is that the failure is surfaced as a {@link StreamState#TERMINAL_FAILED}
 * terminal, and that a failed stream is never metered or billed as delivered.
 *
 * <p>Finalization — SchemaLock, metering, cost, emitter — runs exactly once, as the terminal chunk
 * is produced, in the frozen stage order.
 */
public final class GuardedCanonicalStream implements CanonicalStream {

  /** Runs the post-transport stages once the stream reaches its terminal. */
  @FunctionalInterface
  public interface StreamFinalizer {

    /**
     * Finalizes a completed stream.
     *
     * @param text the concatenated delta text, for structured-output validation
     * @param usage the usage the provider reported, or {@code null} when it reported none
     * @param integrityProven whether StreamGuard proved the transport intact
     * @param finishReason the provider's finish reason
     * @return the terminal chunk to hand the consumer
     */
    StreamChunk.Terminal finalizeStream(
        String text, CanonicalUsage usage, boolean integrityProven, FinishReason finishReason);
  }

  private final TransportSession session;
  private final ProviderStreamDecoder decoder;
  private final StreamFinalizer finalizer;
  private final java.util.function.Consumer<String> textObserver;

  private final StringBuilder text = new StringBuilder();
  private CanonicalUsage reportedUsage;
  private FinishReason finishReason = FinishReason.STOP;
  private boolean finished;
  private boolean cancelled;

  /**
   * Creates the stream.
   *
   * @param session the guarded transport session
   * @param decoder the provider's decoder for guarded payloads
   * @param finalizer the post-transport stages
   */
  public GuardedCanonicalStream(
      final TransportSession session,
      final ProviderStreamDecoder decoder,
      final StreamFinalizer finalizer) {
    this(session, decoder, finalizer, fragment -> {});
  }

  /**
   * Creates the stream with an observer notified of each delta as it is forwarded.
   *
   * <p>The observer is how incremental structured-output validation is fed without this class
   * knowing anything about SchemaLock.
   *
   * @param session the guarded transport session
   * @param decoder the provider's decoder for guarded payloads
   * @param finalizer the post-transport stages
   * @param textObserver notified with each delta's text, in order
   */
  public GuardedCanonicalStream(
      final TransportSession session,
      final ProviderStreamDecoder decoder,
      final StreamFinalizer finalizer,
      final java.util.function.Consumer<String> textObserver) {
    this.session = Preconditions.requireNonNull(session, "session");
    this.decoder = Preconditions.requireNonNull(decoder, "decoder");
    this.finalizer = Preconditions.requireNonNull(finalizer, "finalizer");
    this.textObserver = Preconditions.requireNonNull(textObserver, "textObserver");
  }

  @Override
  public StreamChunk next() {
    if (finished) {
      throw new IllegalStateException("stream already finished");
    }
    while (true) {
      final TransportEvent event;
      try {
        event = session.next();
      } catch (final RuntimeException transportFailure) {
        // A provider disconnect or guard fault ends the stream; it never propagates as an exception
        // into the consumer, which would leave the response half-written with no terminal.
        return terminate(false);
      }

      if (event instanceof TransportEvent.Completed completed) {
        final TransportVerdict verdict = completed.verdict();
        return terminate(verdict.integrity() == TransportVerdict.Integrity.PROVEN);
      }

      final TransportEvent.Delta delta = (TransportEvent.Delta) event;
      final Optional<StreamChunk> chunk;
      try {
        chunk = decoder.decode(delta.delta().payload());
      } catch (final RuntimeException undecodable) {
        // The bytes were intact but meaningless in the provider's dialect: a correctness failure,
        // not a transport failure.
        finishReason = FinishReason.ERROR;
        return terminate(false);
      }
      if (chunk.isEmpty()) {
        continue; // keep-alive or role-only opener: nothing canonical to forward
      }

      final StreamChunk canonical = chunk.orElseThrow();
      if (canonical instanceof StreamChunk.Delta textDelta) {
        text.append(textDelta.text());
        textObserver.accept(textDelta.text());
        return canonical;
      }
      if (canonical instanceof StreamChunk.Usage usage) {
        reportedUsage = usage.usage();
        return canonical;
      }
      if (canonical instanceof StreamChunk.Terminal terminal) {
        finishReason = terminal.finishReason();
        continue; // the guard's own Completed event is the authority on how the stream ended
      }
      return canonical; // tool-call delta: forwarded verbatim
    }
  }

  private StreamChunk.Terminal terminate(final boolean integrityProven) {
    finished = true;
    final StreamChunk.Terminal terminal;
    try {
      terminal =
          finalizer.finalizeStream(
              text.toString(),
              // Never a bare null: a provider that reported no usage still needs a usage figure the
              // accounting stages can read, marked ESTIMATED so it is not mistaken for a measured
              // zero.
              usageOrEstimated(),
              integrityProven && !cancelled,
              cancelled ? FinishReason.ERROR : finishReason);
    } catch (final RuntimeException finalizationFailure) {
      // Accounting must never strand the consumer without a terminal.
      return new StreamChunk.Terminal(StreamState.TERMINAL_FAILED, FinishReason.ERROR);
    }
    return terminal;
  }

  @Override
  public boolean finished() {
    return finished;
  }

  @Override
  public void cancel(final String reason) {
    if (finished) {
      return;
    }
    cancelled = true;
    try {
      session.cancel(reason == null ? "cancelled" : reason);
    } catch (final RuntimeException ignored) {
      // best effort: the consumer is already gone
    }
  }

  @Override
  public void close() {
    cancel("closed");
  }

  /**
   * The usage the provider reported, if any. Zeroed and {@link UsageClass#ESTIMATED} when the
   * provider reported none, so accounting can tell a measured zero from an unmeasured one.
   *
   * @return the reported usage, never {@code null}
   */
  public CanonicalUsage usageOrEstimated() {
    return reportedUsage == null
        ? new CanonicalUsage(0L, 0L, 0L, 0L, 0L, UsageClass.ESTIMATED)
        : reportedUsage;
  }
}

package io.reliabilityai.gateway.dataplane.app.pipeline;

import static org.assertj.core.api.Assertions.assertThat;

import io.reliabilityai.gateway.canonical.io.CanonicalUsage;
import io.reliabilityai.gateway.canonical.io.FinishReason;
import io.reliabilityai.gateway.canonical.io.UsageClass;
import io.reliabilityai.gateway.canonical.stream.StreamChunk;
import io.reliabilityai.gateway.canonical.stream.StreamState;
import io.reliabilityai.gateway.dataplane.app.MandatoryStage;
import io.reliabilityai.gateway.dataplane.streamguard.application.StreamGuardService;
import io.reliabilityai.gateway.ports.ProviderAdapterPort.ProviderInvocationResult;
import io.reliabilityai.gateway.ports.ProviderStreamDecoder;
import io.reliabilityai.gateway.ports.ProviderTransportStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;

/**
 * End-to-end streaming through the pipeline with the <b>real</b> StreamGuard.
 *
 * <p>The guard is deliberately not stubbed: the whole point of this milestone is that the integrity
 * proof is genuine, so these tests feed real SSE bytes through {@link StreamGuardService} and
 * assert on the verdicts it actually produces.
 */
class StreamingPipelineTest {

  /** A provider transport stream driven from a fixed script of byte frames. */
  private static final class ScriptedTransport implements ProviderTransportStream {
    private final BlockingQueue<Chunk> frames = new ArrayBlockingQueue<>(64);
    private final AtomicBoolean cancelled = new AtomicBoolean();

    ScriptedTransport(final String... sseFrames) {
      for (final String frame : sseFrames) {
        frames.add(new Chunk.Data(frame.getBytes(StandardCharsets.UTF_8)));
      }
      frames.add(new Chunk.Closed());
    }

    ScriptedTransport(final List<byte[]> raw, final boolean close) {
      for (final byte[] frame : raw) {
        frames.add(new Chunk.Data(frame));
      }
      if (close) {
        frames.add(new Chunk.Closed());
      }
    }

    @Override
    public Framing framing() {
      return Framing.SSE;
    }

    @Override
    public Chunk read() throws InterruptedException {
      if (cancelled.get()) {
        return new Chunk.Closed();
      }
      final Chunk chunk = frames.poll(2, TimeUnit.SECONDS);
      return chunk == null ? new Chunk.Closed() : chunk;
    }

    @Override
    public void cancel() {
      cancelled.set(true);
    }

    boolean cancelled() {
      return cancelled.get();
    }
  }

  /** A minimal OpenAI-shaped decoder: enough to prove the seam, not a provider implementation. */
  private static final ProviderStreamDecoder DECODER =
      payload -> {
        // Deliberately not trimmed: delta text is content, and stripping whitespace here would
        // silently corrupt a response that legitimately begins with a space.
        final String text = new String(payload, StandardCharsets.UTF_8);
        if (text.isBlank()) {
          return Optional.empty();
        }
        if ("[DONE]".equals(text.trim())) {
          return Optional.of(
              new StreamChunk.Terminal(StreamState.TERMINAL_COMPLETE, FinishReason.STOP));
        }
        if (text.trim().startsWith("USAGE:")) {
          final long prompt = Long.parseLong(text.trim().substring(6));
          return Optional.of(
              new StreamChunk.Usage(
                  new CanonicalUsage(prompt, 1L, 0L, 0L, 0L, UsageClass.AUTHORITATIVE)));
        }
        if (text.trim().startsWith("KEEPALIVE")) {
          return Optional.empty();
        }
        if (text.trim().startsWith("TOOL:")) {
          return Optional.of(
              new StreamChunk.ToolCallDelta(
                  new io.reliabilityai.gateway.canonical.io.CanonicalToolCall(
                      text.trim().substring(5), "{}", "call-1")));
        }
        return Optional.of(new StreamChunk.Delta(text));
      };

  private static String sse(final String data) {
    return "data: " + data + "\n\n";
  }

  private final RequestPipelineTest.Harness harness = new RequestPipelineTest.Harness();

  private PipelineOutcome stream(final ProviderTransportStream transport) {
    harness.streamingTransport =
        new ProviderInvocationResult.StreamingTransport(transport, DECODER);
    return harness.pipeline().execute(harness.inbound());
  }

  /** Drains a streamed outcome to its terminal, collecting every chunk. */
  private static List<StreamChunk> drain(final PipelineOutcome outcome) {
    final CanonicalStream stream = ((PipelineOutcome.Streamed) outcome).stream();
    final List<StreamChunk> chunks = new ArrayList<>();
    while (!stream.finished()) {
      chunks.add(stream.next());
    }
    return chunks;
  }

  // ---- successful streaming --------------------------------------------------------------------

  @Test
  void streamsOrderedDeltasAndTerminatesCleanly() {
    final PipelineOutcome outcome =
        stream(new ScriptedTransport(sse("Hello"), sse(" world"), sse("[DONE]")));

    assertThat(outcome).isInstanceOf(PipelineOutcome.Streamed.class);
    final List<StreamChunk> chunks = drain(outcome);

    assertThat(((StreamChunk.Delta) chunks.get(0)).text()).isEqualTo("Hello");
    assertThat(((StreamChunk.Delta) chunks.get(1)).text()).isEqualTo(" world");
    final StreamChunk.Terminal terminal = (StreamChunk.Terminal) chunks.get(chunks.size() - 1);
    assertThat(terminal.state()).isEqualTo(StreamState.TERMINAL_COMPLETE);
  }

  @Test
  void forwardsEachDeltaBeforeTheStreamCompletes() {
    final PipelineOutcome outcome =
        stream(new ScriptedTransport(sse("first"), sse("second"), sse("[DONE]")));
    final CanonicalStream stream = ((PipelineOutcome.Streamed) outcome).stream();

    final StreamChunk first = stream.next();

    // The first token is available while the stream is still open — nothing is buffered to the end.
    assertThat(((StreamChunk.Delta) first).text()).isEqualTo("first");
    assertThat(stream.finished()).isFalse();
    assertThat(harness.publishes.get()).isZero(); // finalization has not run yet
    drain(outcome);
  }

  @Test
  void streamsToolCallsAndUsage() {
    final List<StreamChunk> chunks =
        drain(stream(new ScriptedTransport(sse("TOOL:lookup"), sse("USAGE:7"), sse("[DONE]"))));

    assertThat(chunks.get(0)).isInstanceOf(StreamChunk.ToolCallDelta.class);
    assertThat(((StreamChunk.ToolCallDelta) chunks.get(0)).toolCall().name()).isEqualTo("lookup");
    assertThat(((StreamChunk.Usage) chunks.get(1)).usage().prompt()).isEqualTo(7L);
  }

  @Test
  void ignoresKeepAliveFrames() {
    final List<StreamChunk> chunks =
        drain(stream(new ScriptedTransport(sse("KEEPALIVE"), sse("x"), sse("[DONE]"))));

    assertThat(chunks).hasSize(2); // one delta, one terminal
    assertThat(((StreamChunk.Delta) chunks.get(0)).text()).isEqualTo("x");
  }

  // ---- stage ordering and accounting ------------------------------------------------------------

  @Test
  void aDrainedStreamRunsEveryMandatoryStageInFrozenOrder() {
    final PipelineOutcome outcome =
        stream(new ScriptedTransport(sse("hi"), sse("USAGE:3"), sse("[DONE]")));
    drain(outcome);

    // Identical stage sequence to a unary request: streaming does not bypass anything.
    assertThat(outcome.trace().stages()).containsExactly(MandatoryStage.values());
  }

  @Test
  void streamGuardIsRecordedOnlyOnceItsVerdictExists() {
    final PipelineOutcome outcome = stream(new ScriptedTransport(sse("hi"), sse("[DONE]")));

    // Before draining, the transport has not finished, so no integrity claim can have been made.
    assertThat(outcome.trace().stages()).doesNotContain(MandatoryStage.STREAM_GUARD);

    drain(outcome);
    assertThat(outcome.trace().stages()).contains(MandatoryStage.STREAM_GUARD);
  }

  @Test
  void meteringCostAndEmitterRunExactlyOncePerStream() {
    drain(stream(new ScriptedTransport(sse("hi"), sse("USAGE:5"), sse("[DONE]"))));

    assertThat(harness.meterAttempts.get()).isEqualTo(1);
    assertThat(harness.finalizations.get()).isEqualTo(1);
    assertThat(harness.publishes.get()).isEqualTo(1);
  }

  @Test
  void providerReportedUsageReachesMetering() {
    drain(stream(new ScriptedTransport(sse("hi"), sse("USAGE:11"), sse("[DONE]"))));

    assertThat(harness.meteredFacts.get(0).reportedUsage().prompt()).isEqualTo(11L);
    assertThat(harness.meteredFacts.get(0).reportedUsageClass())
        .isEqualTo(UsageClass.AUTHORITATIVE);
  }

  @Test
  void aStreamWithNoReportedUsageIsMeteredAsEstimated() {
    drain(stream(new ScriptedTransport(sse("hi"), sse("[DONE]"))));

    // A measured zero and an unmeasured stream must stay distinguishable downstream.
    assertThat(harness.meteredFacts.get(0).reportedUsageClass()).isEqualTo(UsageClass.ESTIMATED);
  }

  // ---- transport integrity failures -------------------------------------------------------------

  @Test
  void aTruncatedStreamTerminatesAsFailedAndIsNeverMetered() {
    // The provider closes mid-frame: no terminal SSE event ever arrives.
    final ScriptedTransport truncated =
        new ScriptedTransport(List.of("data: par".getBytes(StandardCharsets.UTF_8)), true);

    final PipelineOutcome outcome = stream(truncated);
    final List<StreamChunk> chunks = drain(outcome);

    final StreamChunk.Terminal terminal = (StreamChunk.Terminal) chunks.get(chunks.size() - 1);
    assertThat(terminal.state()).isEqualTo(StreamState.TERMINAL_FAILED);
    assertThat(outcome.trace().refusalStage()).contains(MandatoryStage.STREAM_GUARD);
    // A stream whose transport was not proven intact must never be billed as delivered.
    assertThat(harness.meterAttempts.get()).isZero();
    assertThat(harness.publishes.get()).isZero();
  }

  @Test
  void aProviderDisconnectBeforeAnyFrameFailsClosed() {
    final PipelineOutcome outcome = stream(new ScriptedTransport(List.of(), true));
    final List<StreamChunk> chunks = drain(outcome);

    assertThat(((StreamChunk.Terminal) chunks.get(chunks.size() - 1)).state())
        .isEqualTo(StreamState.TERMINAL_FAILED);
    assertThat(harness.publishes.get()).isZero();
  }

  @Test
  void invalidUtf8IsRejectedByTheGuardRatherThanSubstituted() {
    // A lone continuation byte is not valid UTF-8; substituting U+FFFD would corrupt the answer.
    final byte[] corrupt = new byte[] {'d', 'a', 't', 'a', ':', ' ', (byte) 0x80, '\n', '\n'};
    final PipelineOutcome outcome = stream(new ScriptedTransport(List.of(corrupt), true));

    final List<StreamChunk> chunks = drain(outcome);
    assertThat(((StreamChunk.Terminal) chunks.get(chunks.size() - 1)).state())
        .isEqualTo(StreamState.TERMINAL_FAILED);
    assertThat(harness.meterAttempts.get()).isZero();
  }

  @Test
  void framesSplitAcrossReadsAreReassembled() {
    // Partial frames are normal on a socket; the guard must join them, not drop them.
    final ScriptedTransport split =
        new ScriptedTransport(
            List.of(
                "data: hel".getBytes(StandardCharsets.UTF_8),
                "lo\n\n".getBytes(StandardCharsets.UTF_8),
                sse("[DONE]").getBytes(StandardCharsets.UTF_8)),
            true);

    final List<StreamChunk> chunks = drain(stream(split));

    assertThat(((StreamChunk.Delta) chunks.get(0)).text()).isEqualTo("hello");
  }

  // ---- cancellation
  // ------------------------------------------------------------------------------

  @Test
  void downstreamCancellationPropagatesBackToTheProvider() {
    final ScriptedTransport transport =
        new ScriptedTransport(sse("a"), sse("b"), sse("c"), sse("[DONE]"));
    final PipelineOutcome outcome = stream(transport);
    final CanonicalStream canonical = ((PipelineOutcome.Streamed) outcome).stream();

    canonical.next(); // consume one chunk, then abandon
    canonical.cancel("client-gone");

    assertThat(transport.cancelled()).isTrue();
  }

  @Test
  void aCancelledStreamIsNeverBilledAsDelivered() {
    final ScriptedTransport transport = new ScriptedTransport(sse("a"), sse("b"), sse("[DONE]"));
    final PipelineOutcome outcome = stream(transport);
    final CanonicalStream canonical = ((PipelineOutcome.Streamed) outcome).stream();

    canonical.next();
    canonical.cancel("client-gone");
    while (!canonical.finished()) {
      canonical.next();
    }

    assertThat(harness.meterAttempts.get()).isZero();
    assertThat(harness.publishes.get()).isZero();
  }

  @Test
  void closingTheStreamCancelsIt() {
    final ScriptedTransport transport = new ScriptedTransport(sse("a"), sse("[DONE]"));
    final PipelineOutcome outcome = stream(transport);

    ((PipelineOutcome.Streamed) outcome).stream().close();

    assertThat(transport.cancelled()).isTrue();
  }

  // ---- backward compatibility and exhaustiveness
  // --------------------------------------------------

  @Test
  void unaryBehaviourIsUnchanged() {
    harness.streamingTransport = null; // the default unary path

    final PipelineOutcome outcome = harness.pipeline().execute(harness.inbound());

    assertThat(outcome).isInstanceOf(PipelineOutcome.Completed.class);
    assertThat(outcome.trace().stages()).containsExactly(MandatoryStage.values());
    assertThat(harness.publishes.get()).isEqualTo(1);
  }

  @Test
  void theLegacyStreamingVariantIsRefusedBecauseItCannotBeGuarded() {
    harness.legacyStreaming = true;

    final PipelineOutcome outcome = harness.pipeline().execute(harness.inbound());

    // Canonical chunks have already discarded the framing; serving them would mean claiming an
    // integrity proof the gateway cannot make.
    final PipelineOutcome.Refused refused = (PipelineOutcome.Refused) outcome;
    assertThat(refused.refusal().stage()).isEqualTo(MandatoryStage.ADAPTER);
    assertThat(refused.refusal().reason()).isEqualTo("unguardable-stream");
    assertThat(harness.publishes.get()).isZero();
  }

  @Test
  void theSealedHierarchyIsHandledExhaustively() {
    // A compile-time switch over the sealed type: adding a variant without handling it here fails
    // the build rather than silently falling through at runtime.
    final List<ProviderInvocationResult> all =
        List.of(
            new ProviderInvocationResult.Unary(RequestPipelineTest.Harness.unaryResponse()),
            new ProviderInvocationResult.Streaming(subscriber -> {}),
            new ProviderInvocationResult.StreamingTransport(new ScriptedTransport(), DECODER),
            new ProviderInvocationResult.Failed(RequestPipelineTest.Harness.anError()));

    for (final ProviderInvocationResult result : all) {
      final String label =
          switch (result) {
            case ProviderInvocationResult.Unary u -> "unary";
            case ProviderInvocationResult.Streaming s -> "streaming";
            case ProviderInvocationResult.StreamingTransport t -> "streaming-transport";
            case ProviderInvocationResult.Failed f -> "failed";
          };
      assertThat(label).isNotBlank();
    }
    assertThat(all).hasSize(4);
  }

  // ---- structured output during streaming
  // ---------------------------------------------------------

  @Test
  void structuredOutputIsValidatedIncrementallyAsFragmentsArrive() {
    harness.schema =
        Optional.of(
            new io.reliabilityai.gateway.dataplane.schemalock.api.CompiledSchema(
                new io.reliabilityai.gateway.dataplane.schemalock.domain.SchemaId("abc"), "v1", 1));

    final PipelineOutcome outcome =
        stream(new ScriptedTransport(sse("{\"a\":"), sse("1}"), sse("[DONE]")));
    final List<StreamChunk> chunks = drain(outcome);

    // SchemaLock saw each fragment as it was forwarded, not one concatenated blob at the end.
    assertThat(harness.streamedFragments).containsExactly("{\"a\":", "1}");
    assertThat(((StreamChunk.Terminal) chunks.get(chunks.size() - 1)).state())
        .isEqualTo(StreamState.TERMINAL_COMPLETE);
    assertThat(outcome.trace().stages()).containsExactly(MandatoryStage.values());
  }

  @Test
  void nonConformantStreamedOutputIsRefusedAndNeverBilled() {
    harness.schema =
        Optional.of(
            new io.reliabilityai.gateway.dataplane.schemalock.api.CompiledSchema(
                new io.reliabilityai.gateway.dataplane.schemalock.domain.SchemaId("abc"), "v1", 1));
    harness.conformant = false;

    final PipelineOutcome outcome = stream(new ScriptedTransport(sse("garbage"), sse("[DONE]")));
    final List<StreamChunk> chunks = drain(outcome);

    assertThat(((StreamChunk.Terminal) chunks.get(chunks.size() - 1)).state())
        .isEqualTo(StreamState.TERMINAL_FAILED);
    assertThat(outcome.trace().refusalStage()).contains(MandatoryStage.SCHEMA_LOCK);
    // A rejected answer is never metered or sealed as delivered.
    assertThat(harness.meterAttempts.get()).isZero();
    assertThat(harness.publishes.get()).isZero();
  }

  @Test
  void duplicateFramesAreSurfacedByTheGuardRatherThanDeliveredTwice() {
    // The same SSE event id repeated: StreamGuard's dedup window is what must catch this.
    final String duplicated = "id: 1\ndata: once\n\n";
    final List<StreamChunk> chunks =
        drain(
            stream(
                new ScriptedTransport(
                    List.of(
                        duplicated.getBytes(StandardCharsets.UTF_8),
                        duplicated.getBytes(StandardCharsets.UTF_8),
                        sse("[DONE]").getBytes(StandardCharsets.UTF_8)),
                    true)));

    final long deltas = chunks.stream().filter(StreamChunk.Delta.class::isInstance).count();
    // Either the guard suppressed the repeat, or it failed the stream — never delivered twice as
    // though the provider had genuinely said it twice.
    final StreamChunk.Terminal terminal = (StreamChunk.Terminal) chunks.get(chunks.size() - 1);
    assertThat(deltas <= 1 || terminal.state() == StreamState.TERMINAL_FAILED).isTrue();
  }

  // ---- determinism and concurrency
  // ----------------------------------------------------------------

  @Test
  void repeatedIdenticalStreamsProduceIdenticalChunksAndStageOrder() {
    final List<StreamChunk> first =
        drain(stream(new ScriptedTransport(sse("a"), sse("b"), sse("USAGE:2"), sse("[DONE]"))));

    for (int run = 0; run < 25; run++) {
      final RequestPipelineTest.Harness repeat = new RequestPipelineTest.Harness();
      repeat.streamingTransport =
          new ProviderInvocationResult.StreamingTransport(
              new ScriptedTransport(sse("a"), sse("b"), sse("USAGE:2"), sse("[DONE]")), DECODER);
      final PipelineOutcome outcome = repeat.pipeline().execute(repeat.inbound());
      assertThat(drain(outcome)).isEqualTo(first);
      assertThat(outcome.trace().stages()).containsExactly(MandatoryStage.values());
    }
  }

  @Test
  void oneHundredConcurrentStreamsEachCompleteIndependently() throws Exception {
    final int streams = 100;
    final CountDownLatch go = new CountDownLatch(1);
    final CountDownLatch done = new CountDownLatch(streams);
    final List<String> failures = new CopyOnWriteArrayList<>();

    for (int i = 0; i < streams; i++) {
      final String token = "tok-" + i;
      Thread.ofVirtual()
          .start(
              () -> {
                try {
                  go.await();
                  final RequestPipelineTest.Harness local = new RequestPipelineTest.Harness();
                  local.streamingTransport =
                      new ProviderInvocationResult.StreamingTransport(
                          new ScriptedTransport(sse(token), sse("USAGE:1"), sse("[DONE]")),
                          DECODER);
                  final PipelineOutcome outcome = local.pipeline().execute(local.inbound());
                  final List<StreamChunk> chunks = drain(outcome);
                  if (!(chunks.get(0) instanceof StreamChunk.Delta delta)
                      || !delta.text().equals(token)) {
                    failures.add("crossed streams");
                  }
                  if (local.publishes.get() != 1) {
                    failures.add("finalization count");
                  }
                } catch (final InterruptedException e) {
                  Thread.currentThread().interrupt();
                } catch (final RuntimeException e) {
                  failures.add(e.getClass().getSimpleName());
                } finally {
                  done.countDown();
                }
              });
    }

    go.countDown();
    assertThat(done.await(120, TimeUnit.SECONDS)).isTrue();
    assertThat(failures).isEmpty();
  }
}

package io.reliabilityai.gateway.dataplane.streamguard.application;

import static org.assertj.core.api.Assertions.assertThat;

import io.reliabilityai.gateway.dataplane.streamguard.api.FramingType;
import io.reliabilityai.gateway.dataplane.streamguard.api.StreamGuardPolicy;
import io.reliabilityai.gateway.dataplane.streamguard.api.TransportEvent;
import io.reliabilityai.gateway.dataplane.streamguard.api.TransportOutcomeSink;
import io.reliabilityai.gateway.dataplane.streamguard.api.TransportSession;
import io.reliabilityai.gateway.dataplane.streamguard.api.TransportSourcePort;
import io.reliabilityai.gateway.dataplane.streamguard.api.TransportVerdict;
import io.reliabilityai.gateway.dataplane.streamguard.domain.AwsEventStreamMessages;
import io.reliabilityai.gateway.dataplane.streamguard.domain.TransportFailureClass;
import io.reliabilityai.gateway.ports.ClockPort;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Fail-closed, effectively-once transport-integrity tests for the StreamGuard session (Doc 18). */
class StreamSessionTest {

  private static final Instant T0 = Instant.parse("2026-07-24T00:00:00Z");
  private static final StreamGuardPolicy POLICY =
      new StreamGuardPolicy(
          1_000_000L, 65_536, 128, Duration.ofSeconds(30), Duration.ofSeconds(300));

  private final AdvancingClock clock = new AdvancingClock();

  private StreamGuardService guard() {
    return new StreamGuardService(clock, TransportOutcomeSink.NO_OP);
  }

  private static byte[] utf8(final String s) {
    return s.getBytes(StandardCharsets.UTF_8);
  }

  /** Drains a session to its terminal verdict, collecting emitted delta payloads as strings. */
  private static Drained drain(final TransportSession session) {
    final List<String> deltas = new ArrayList<>();
    while (true) {
      final TransportEvent event = session.next();
      if (event instanceof TransportEvent.Delta d) {
        deltas.add(new String(d.delta().payload(), StandardCharsets.UTF_8));
      } else {
        return new Drained(deltas, ((TransportEvent.Completed) event).verdict());
      }
    }
  }

  private record Drained(List<String> deltas, TransportVerdict verdict) {}

  @Test
  void ssePassesCleanStreamThroughToProvenCompletion() {
    final FakeSource src = FakeSource.sse("data: hello\n\n", "data: world\n\n", "data: [DONE]\n\n");
    final Drained out = drain(guard().openTransport(src, POLICY));
    assertThat(out.deltas()).containsExactly("hello", "world");
    assertThat(out.verdict().integrity()).isEqualTo(TransportVerdict.Integrity.PROVEN);
    assertThat(out.verdict().completed()).isTrue();
    assertThat(out.verdict().deltasEmitted()).isEqualTo(2);
  }

  @Test
  void sseChunkBoundarySplitIsInvariant() {
    // The same logical stream split at arbitrary byte boundaries yields the identical delta
    // sequence.
    final FakeSource src =
        FakeSource.sseRaw("data: hel", "lo\n", "\ndata: wo", "rld\n\ndata: [DONE]\n\n");
    final Drained out = drain(guard().openTransport(src, POLICY));
    assertThat(out.deltas()).containsExactly("hello", "world");
    assertThat(out.verdict().integrity()).isEqualTo(TransportVerdict.Integrity.PROVEN);
  }

  @Test
  void closeWithoutTerminalFailsTruncatedNeverSilentComplete() {
    final FakeSource src = FakeSource.sse("data: partial\n\n"); // no [DONE]
    final Drained out = drain(guard().openTransport(src, POLICY));
    assertThat(out.deltas()).containsExactly("partial");
    assertThat(out.verdict().integrity()).isEqualTo(TransportVerdict.Integrity.FAILED);
    assertThat(out.verdict().failureClass()).isEqualTo(TransportFailureClass.TRUNCATED);
    assertThat(out.verdict().completed()).isFalse();
  }

  @Test
  void heartbeatsResetLivenessAndAreNeverEmitted() {
    final FakeSource src =
        FakeSource.sse(": keepalive\n\n", "data: x\n\n", ": ping\n\n", "data: [DONE]\n\n");
    final Drained out = drain(guard().openTransport(src, POLICY));
    assertThat(out.deltas()).containsExactly("x"); // comments never surface as data
    assertThat(out.verdict().integrity()).isEqualTo(TransportVerdict.Integrity.PROVEN);
  }

  @Test
  void malformedUtf8FailsDecodeNeverSubstitutes() {
    // 0xFF is never valid UTF-8; strict decode must reject, not substitute U+FFFD.
    final Deque<TransportSourcePort.SourceChunk> chunks = new ArrayDeque<>();
    chunks.add(
        new TransportSourcePort.SourceChunk.Data(
            concat(utf8("data: "), new byte[] {(byte) 0xFF}, utf8("\n\n"))));
    chunks.add(new TransportSourcePort.SourceChunk.Closed());
    final FakeSource src = new FakeSource(FramingType.SSE, chunks);
    final Drained out = drain(guard().openTransport(src, POLICY));
    assertThat(out.deltas()).isEmpty();
    assertThat(out.verdict().failureClass()).isEqualTo(TransportFailureClass.DECODE);
  }

  @Test
  void duplicateWithinWindowSuppressedOnce() {
    final FakeSource src = FakeSource.sse("data: dup\n\n", "data: dup\n\n", "data: [DONE]\n\n");
    final Drained out = drain(guard().openTransport(src, POLICY));
    assertThat(out.deltas()).containsExactly("dup"); // second identical payload suppressed (§17)
    assertThat(out.verdict().deltasEmitted()).isEqualTo(1);
  }

  @Test
  void sessionByteOverflowFailsClosed() {
    final StreamGuardPolicy tiny =
        new StreamGuardPolicy(8L, 65_536, 128, Duration.ofSeconds(30), Duration.ofSeconds(300));
    final FakeSource src = FakeSource.sse("data: waytoolong\n\n", "data: [DONE]\n\n");
    final Drained out = drain(guard().openTransport(src, tiny));
    assertThat(out.verdict().failureClass()).isEqualTo(TransportFailureClass.OVERFLOW);
  }

  @Test
  void framingBufferOverflowFailsClosed() {
    final StreamGuardPolicy tinyFrame =
        new StreamGuardPolicy(1_000_000L, 4, 128, Duration.ofSeconds(30), Duration.ofSeconds(300));
    final FakeSource src = FakeSource.sse("data: farbeyondfour\n\n");
    final Drained out = drain(guard().openTransport(src, tinyFrame));
    assertThat(out.verdict().failureClass()).isEqualTo(TransportFailureClass.OVERFLOW);
  }

  @Test
  void inactivityTimeoutFailsClosed() {
    // Advance the clock past the inactivity budget between chunks.
    clock.stepSeconds = 60; // > 30s inactivity budget
    final FakeSource src = FakeSource.sse("data: a\n\n", "data: b\n\n", "data: [DONE]\n\n");
    final Drained out = drain(guard().openTransport(src, POLICY));
    assertThat(out.verdict().failureClass()).isEqualTo(TransportFailureClass.TIMEOUT);
  }

  @Test
  void ndjsonWellFormedLinesEmittedTerminalOnDone() {
    final FakeSource src =
        new FakeSource(
            FramingType.NDJSON,
            queue(
                new TransportSourcePort.SourceChunk.Data(utf8("{\"a\":1}\n{\"b\":2}\n[DONE]\n")),
                new TransportSourcePort.SourceChunk.Closed()));
    final Drained out = drain(guard().openTransport(src, POLICY));
    assertThat(out.deltas()).containsExactly("{\"a\":1}", "{\"b\":2}");
    assertThat(out.verdict().integrity()).isEqualTo(TransportVerdict.Integrity.PROVEN);
  }

  @Test
  void ndjsonMalformedFragmentFailsDecode() {
    final FakeSource src =
        new FakeSource(
            FramingType.NDJSON,
            queue(
                new TransportSourcePort.SourceChunk.Data(utf8("{\"a\":1\n")), // unbalanced brace
                new TransportSourcePort.SourceChunk.Closed()));
    final Drained out = drain(guard().openTransport(src, POLICY));
    assertThat(out.verdict().failureClass()).isEqualTo(TransportFailureClass.DECODE);
  }

  @Test
  void jsonArrayChunkedElementsEmittedTerminalOnClose() {
    // A single top-level JSON array delivered in chunks: each element surfaces as one delta and the
    // closing bracket is the explicit transport terminal (PROVEN, never silently truncated).
    final FakeSource src =
        new FakeSource(
            FramingType.JSON_ARRAY_CHUNKED,
            queue(
                new TransportSourcePort.SourceChunk.Data(utf8("[{\"a\":1},")),
                new TransportSourcePort.SourceChunk.Data(utf8(" {\"b\":2}]")),
                new TransportSourcePort.SourceChunk.Closed()));
    final Drained out = drain(guard().openTransport(src, POLICY));
    assertThat(out.deltas()).containsExactly("{\"a\":1}", "{\"b\":2}");
    assertThat(out.verdict().integrity()).isEqualTo(TransportVerdict.Integrity.PROVEN);
    assertThat(out.verdict().completed()).isTrue();
  }

  @Test
  void jsonArrayChunkedMalformedFailsDecode() {
    final FakeSource src =
        new FakeSource(
            FramingType.JSON_ARRAY_CHUNKED,
            queue(
                new TransportSourcePort.SourceChunk.Data(
                    utf8("{\"a\":1}")), // not a top-level array
                new TransportSourcePort.SourceChunk.Closed()));
    final Drained out = drain(guard().openTransport(src, POLICY));
    assertThat(out.verdict().failureClass()).isEqualTo(TransportFailureClass.DECODE);
  }

  @Test
  void awsEventStreamDataReachesDownstreamAndProvesOnEnd() {
    // Two content messages then an explicit end message: decoded DATA reaches downstream in order
    // and
    // the end message is the explicit transport terminal (PROVEN, joint-gated §30).
    final FakeSource src =
        new FakeSource(
            FramingType.AWS_EVENT_STREAM,
            queue(
                new TransportSourcePort.SourceChunk.Data(
                    concat(AwsEventStreamMessages.event("a"), AwsEventStreamMessages.event("b"))),
                new TransportSourcePort.SourceChunk.Data(AwsEventStreamMessages.end()),
                new TransportSourcePort.SourceChunk.Closed()));
    final Drained out = drain(guard().openTransport(src, POLICY));
    assertThat(out.deltas()).containsExactly("a", "b");
    assertThat(out.verdict().integrity()).isEqualTo(TransportVerdict.Integrity.PROVEN);
    assertThat(out.verdict().completed()).isTrue();
  }

  @Test
  void awsEventStreamHeartbeatIsNeverEmitted() {
    // Heartbeat resets liveness but is never surfaced as a delta (Doc 18 §23).
    final FakeSource src =
        new FakeSource(
            FramingType.AWS_EVENT_STREAM,
            queue(
                new TransportSourcePort.SourceChunk.Data(AwsEventStreamMessages.heartbeat()),
                new TransportSourcePort.SourceChunk.Data(AwsEventStreamMessages.event("x")),
                new TransportSourcePort.SourceChunk.Data(AwsEventStreamMessages.heartbeat()),
                new TransportSourcePort.SourceChunk.Data(AwsEventStreamMessages.end()),
                new TransportSourcePort.SourceChunk.Closed()));
    final Drained out = drain(guard().openTransport(src, POLICY));
    assertThat(out.deltas()).containsExactly("x");
    assertThat(out.verdict().integrity()).isEqualTo(TransportVerdict.Integrity.PROVEN);
  }

  @Test
  void awsEventStreamCorruptionFailsClosed() {
    final byte[] corrupt =
        AwsEventStreamMessages.withByteFlipped(
            AwsEventStreamMessages.event("hello"),
            AwsEventStreamMessages.event("hello").length - 1); // corrupt the message CRC
    final FakeSource src =
        new FakeSource(
            FramingType.AWS_EVENT_STREAM,
            queue(
                new TransportSourcePort.SourceChunk.Data(corrupt),
                new TransportSourcePort.SourceChunk.Closed()));
    final Drained out = drain(guard().openTransport(src, POLICY));
    assertThat(out.deltas()).isEmpty(); // partially-validated data never reaches downstream
    assertThat(out.verdict().failureClass()).isEqualTo(TransportFailureClass.CORRUPTION);
  }

  @Test
  void unsupportedFramingFailsClosed() {
    // Every FramingType now resolves to a decoder, so the UNSUPPORTED_FRAMING branch is reached
    // only
    // defensively (a null decoder). Constructing the session directly exercises that fail-closed
    // path.
    final FakeSource src = FakeSource.sse("data: a\n\n");
    final StreamSession session =
        new StreamSession(src, null, POLICY, clock, TransportOutcomeSink.NO_OP);
    final Drained out = drain(session);
    assertThat(out.verdict().failureClass()).isEqualTo(TransportFailureClass.UNSUPPORTED_FRAMING);
  }

  @Test
  void everyFramingTypeResolvesToADecoder() {
    // The factory switch is total over FramingType — no framing silently falls through to
    // fail-closed.
    for (final FramingType framing : FramingType.values()) {
      assertThat(FramingDecoderFactory.create(framing, POLICY))
          .as("decoder for %s", framing)
          .isPresent();
    }
  }

  @Test
  void cancelYieldsCancelledVerdictAndClosesSource() {
    final FakeSource src = FakeSource.sse("data: a\n\n", "data: [DONE]\n\n");
    final TransportSession session = guard().openTransport(src, POLICY);
    session.cancel("client abort");
    final TransportEvent event = session.next();
    final TransportVerdict verdict = ((TransportEvent.Completed) event).verdict();
    assertThat(verdict.failureClass()).isEqualTo(TransportFailureClass.CANCELLED);
    assertThat(src.cancelled).isTrue();
  }

  @Test
  void terminalVerdictIsIdempotent() {
    final FakeSource src = FakeSource.sse("data: a\n\n", "data: [DONE]\n\n");
    final TransportSession session = guard().openTransport(src, POLICY);
    final Drained out = drain(session);
    assertThat(out.verdict().integrity()).isEqualTo(TransportVerdict.Integrity.PROVEN);
    // further next() returns the same terminal verdict, never a new delta
    final TransportEvent again = session.next();
    assertThat(((TransportEvent.Completed) again).verdict().integrity())
        .isEqualTo(TransportVerdict.Integrity.PROVEN);
  }

  private static byte[] concat(final byte[]... parts) {
    int len = 0;
    for (final byte[] p : parts) {
      len += p.length;
    }
    final byte[] out = new byte[len];
    int off = 0;
    for (final byte[] p : parts) {
      System.arraycopy(p, 0, out, off, p.length);
      off += p.length;
    }
    return out;
  }

  @SafeVarargs
  private static Deque<TransportSourcePort.SourceChunk> queue(
      final TransportSourcePort.SourceChunk... chunks) {
    final Deque<TransportSourcePort.SourceChunk> q = new ArrayDeque<>();
    for (final TransportSourcePort.SourceChunk c : chunks) {
      q.add(c);
    }
    return q;
  }

  private static final class FakeSource implements TransportSourcePort {
    private final FramingType framing;
    private final Deque<SourceChunk> chunks;
    private boolean cancelled;

    private FakeSource(final FramingType framing, final Deque<SourceChunk> chunks) {
      this.framing = framing;
      this.chunks = chunks;
    }

    static FakeSource sse(final String... events) {
      final Deque<SourceChunk> q = new ArrayDeque<>();
      for (final String e : events) {
        q.add(new SourceChunk.Data(e.getBytes(StandardCharsets.UTF_8)));
      }
      q.add(new SourceChunk.Closed());
      return new FakeSource(FramingType.SSE, q);
    }

    static FakeSource sseRaw(final String... rawChunks) {
      final Deque<SourceChunk> q = new ArrayDeque<>();
      for (final String c : rawChunks) {
        q.add(new SourceChunk.Data(c.getBytes(StandardCharsets.UTF_8)));
      }
      q.add(new SourceChunk.Closed());
      return new FakeSource(FramingType.SSE, q);
    }

    @Override
    public FramingType framing() {
      return framing;
    }

    @Override
    public SourceChunk read() {
      return chunks.isEmpty() ? new SourceChunk.Closed() : chunks.poll();
    }

    @Override
    public void cancel() {
      cancelled = true;
    }
  }

  private static final class AdvancingClock implements ClockPort {
    private Instant now = T0;
    private long stepSeconds;

    @Override
    public Instant now() {
      final Instant snapshot = now;
      now = now.plusSeconds(stepSeconds);
      return snapshot;
    }
  }
}

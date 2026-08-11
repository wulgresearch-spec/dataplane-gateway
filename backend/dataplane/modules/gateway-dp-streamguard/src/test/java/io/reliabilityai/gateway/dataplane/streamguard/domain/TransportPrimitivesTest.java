package io.reliabilityai.gateway.dataplane.streamguard.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Unit tests for the pure StreamGuard transport primitives (Doc 18 §13/§16/§17/§20.1). */
class TransportPrimitivesTest {

  private static byte[] b(final String s) {
    return s.getBytes(StandardCharsets.UTF_8);
  }

  // --- Utf8Validator (§13): strict, reject, never substitute ---

  @Test
  void utf8AcceptsValidMultibyte() throws Exception {
    Utf8Validator.validateStrict(b("héllo — 世界 🌍")); // no throw
  }

  @Test
  void utf8RejectsInvalidByte() {
    assertThatThrownBy(() -> Utf8Validator.validateStrict(new byte[] {(byte) 0xFF}))
        .isInstanceOf(TransportIntegrityException.class);
  }

  @Test
  void utf8RejectsIncompleteTrailingSequence() {
    // Lead byte of a 3-byte sequence with no continuation bytes.
    assertThatThrownBy(() -> Utf8Validator.validateStrict(new byte[] {(byte) 0xE4}))
        .isInstanceOf(TransportIntegrityException.class);
  }

  @Test
  void utf8RejectsOverlongEncoding() {
    // Overlong encoding of '/' (0x2F) as 0xC0 0xAF — must be rejected, never decoded.
    assertThatThrownBy(() -> Utf8Validator.validateStrict(new byte[] {(byte) 0xC0, (byte) 0xAF}))
        .isInstanceOf(TransportIntegrityException.class);
  }

  // --- JsonStructuralScanner (§20.1): structural only ---

  @Test
  void structuralScannerAcceptsWellFormed() {
    assertThat(JsonStructuralScanner.isCompleteWellFormed(b("{\"a\":[1,2,{\"b\":\"c}\"}]}")))
        .isTrue();
  }

  @Test
  void structuralScannerRejectsUnbalanced() {
    assertThat(JsonStructuralScanner.isCompleteWellFormed(b("{\"a\":1"))).isFalse();
    assertThat(JsonStructuralScanner.isCompleteWellFormed(b("}"))).isFalse();
    assertThat(JsonStructuralScanner.isCompleteWellFormed(b(""))).isFalse();
  }

  @Test
  void structuralScannerIgnoresBracesInsideStrings() {
    assertThat(JsonStructuralScanner.isCompleteWellFormed(b("{\"k\":\"}{][\"}"))).isTrue();
  }

  @Test
  void structuralScannerRejectsTrailingContentAfterCompleteValue() {
    // A complete top-level container followed by anything non-whitespace is not well-formed (RB-4).
    assertThat(JsonStructuralScanner.isCompleteWellFormed(b("{}garbage"))).isFalse();
    assertThat(JsonStructuralScanner.isCompleteWellFormed(b("[1,2]{}"))).isFalse();
    assertThat(JsonStructuralScanner.isCompleteWellFormed(b("{\"a\":1}  \n")))
        .isTrue(); // trailing ws ok
  }

  // --- Sequencer (§16): monotonic ---

  @Test
  void sequencerIsMonotonic() throws Exception {
    final Sequencer seq = new Sequencer();
    assertThat(seq.nextSeq()).isEqualTo(0L);
    assertThat(seq.nextSeq()).isEqualTo(1L);
    assertThat(seq.nextSeq()).isEqualTo(2L);
    assertThat(seq.assigned()).isEqualTo(3L);
  }

  // --- DedupWindow (§17): bounded within-window suppression ---

  @Test
  void dedupSuppressesWithinWindowAndReadmitsBeyondIt() {
    final DedupWindow window = new DedupWindow(2);
    final long a = IntegrityCheckpoint.hash(b("a"));
    final long c = IntegrityCheckpoint.hash(b("c"));
    final long d = IntegrityCheckpoint.hash(b("d"));
    assertThat(window.isDuplicate(a)).isFalse();
    window.record(a);
    assertThat(window.isDuplicate(a)).isTrue(); // within window
    window.record(c);
    window.record(d); // evicts 'a' (capacity 2)
    assertThat(window.isDuplicate(a)).isFalse(); // beyond window — no longer suppressed
  }

  @Test
  void integrityCheckpointHashIsDeterministic() {
    assertThat(IntegrityCheckpoint.hash(b("payload")))
        .isEqualTo(IntegrityCheckpoint.hash(b("payload")));
    assertThat(IntegrityCheckpoint.hash(b("payload")))
        .isNotEqualTo(IntegrityCheckpoint.hash(b("payloae")));
  }

  // --- Framing decoders ---

  @Test
  void sseAccumulatesMultiDataFieldsWithNewline() throws Exception {
    final SseFramingDecoder sse = new SseFramingDecoder(65_536);
    final var frames = sse.push(b("data: line1\ndata: line2\n\n"));
    assertThat(frames).hasSize(1);
    assertThat(new String(frames.get(0).payload(), StandardCharsets.UTF_8))
        .isEqualTo("line1\nline2");
  }

  @Test
  void sseRecognizesDoneTerminal() throws Exception {
    final SseFramingDecoder sse = new SseFramingDecoder(65_536);
    final var frames = sse.push(b("data: [DONE]\n\n"));
    assertThat(frames).hasSize(1);
    assertThat(frames.get(0).kind()).isEqualTo(RawFrame.Kind.TERMINAL);
    assertThat(sse.terminalObserved()).isTrue();
  }

  @Test
  void ndjsonRejectsMalformedLine() {
    final NdjsonFramingDecoder nd = new NdjsonFramingDecoder(65_536);
    assertThatThrownBy(() -> nd.push(b("{\"a\":1\n")))
        .isInstanceOf(TransportIntegrityException.class);
  }

  // --- JsonArrayFramingDecoder (§21): chunked top-level JSON array ---

  private static String txt(final RawFrame f) {
    return new String(f.payload(), StandardCharsets.UTF_8);
  }

  @Test
  void jsonArrayEmitsScalarElementsThenTerminal() throws Exception {
    final JsonArrayFramingDecoder d = new JsonArrayFramingDecoder(65_536);
    final var frames = d.push(b("[1, \"two\", true]"));
    assertThat(frames).hasSize(4);
    assertThat(frames.get(0).kind()).isEqualTo(RawFrame.Kind.DATA);
    assertThat(txt(frames.get(0))).isEqualTo("1");
    assertThat(txt(frames.get(1))).isEqualTo("\"two\"");
    assertThat(txt(frames.get(2))).isEqualTo("true");
    assertThat(frames.get(3).kind()).isEqualTo(RawFrame.Kind.TERMINAL);
    assertThat(d.terminalObserved()).isTrue();
  }

  @Test
  void jsonArrayEmitsObjectAndNestedElements() throws Exception {
    final JsonArrayFramingDecoder d = new JsonArrayFramingDecoder(65_536);
    final var frames = d.push(b("[{\"a\":1},[2,3],{\"k\":[4]}]"));
    assertThat(frames).hasSize(4);
    assertThat(txt(frames.get(0))).isEqualTo("{\"a\":1}");
    assertThat(txt(frames.get(1))).isEqualTo("[2,3]");
    assertThat(txt(frames.get(2))).isEqualTo("{\"k\":[4]}");
    assertThat(frames.get(3).kind()).isEqualTo(RawFrame.Kind.TERMINAL);
  }

  @Test
  void jsonArrayIgnoresDelimitersInsideStrings() throws Exception {
    final JsonArrayFramingDecoder d = new JsonArrayFramingDecoder(65_536);
    final var frames = d.push(b("[\"a],b\", \"c\\\"]\"]"));
    assertThat(frames).hasSize(3);
    assertThat(txt(frames.get(0))).isEqualTo("\"a],b\"");
    assertThat(txt(frames.get(1))).isEqualTo("\"c\\\"]\"");
    assertThat(frames.get(2).kind()).isEqualTo(RawFrame.Kind.TERMINAL);
  }

  @Test
  void jsonArrayReassemblesElementsSplitAcrossChunks() throws Exception {
    final JsonArrayFramingDecoder d = new JsonArrayFramingDecoder(65_536);
    assertThat(d.push(b("[{\"a\":"))).isEmpty(); // mid-element, nothing complete yet
    assertThat(d.push(b("1},"))).hasSize(1); // element completes on the top-level comma
    final var tail = d.push(b("2]"));
    assertThat(txt(tail.get(0))).isEqualTo("2");
    assertThat(tail.get(1).kind()).isEqualTo(RawFrame.Kind.TERMINAL);
  }

  @Test
  void jsonEmptyArrayIsTerminalWithNoData() throws Exception {
    final JsonArrayFramingDecoder d = new JsonArrayFramingDecoder(65_536);
    final var frames = d.push(b("[ ]"));
    assertThat(frames).hasSize(1);
    assertThat(frames.get(0).kind()).isEqualTo(RawFrame.Kind.TERMINAL);
    assertThat(d.terminalObserved()).isTrue();
  }

  @Test
  void jsonArrayRejectsStreamNotOpeningWithBracket() {
    final JsonArrayFramingDecoder d = new JsonArrayFramingDecoder(65_536);
    assertThatThrownBy(() -> d.push(b("{\"a\":1}")))
        .isInstanceOf(TransportIntegrityException.class);
  }

  @Test
  void jsonArrayRejectsTrailingComma() {
    final JsonArrayFramingDecoder d = new JsonArrayFramingDecoder(65_536);
    assertThatThrownBy(() -> d.push(b("[1,]"))).isInstanceOf(TransportIntegrityException.class);
  }

  @Test
  void jsonArrayRejectsEmptyElement() {
    final JsonArrayFramingDecoder d = new JsonArrayFramingDecoder(65_536);
    assertThatThrownBy(() -> d.push(b("[1,,2]"))).isInstanceOf(TransportIntegrityException.class);
  }

  @Test
  void jsonArrayRejectsTrailingContentAfterClose() {
    final JsonArrayFramingDecoder d = new JsonArrayFramingDecoder(65_536);
    assertThatThrownBy(() -> d.push(b("[1]x"))).isInstanceOf(TransportIntegrityException.class);
  }

  @Test
  void jsonArrayRejectsStructurallyBrokenElement() {
    final JsonArrayFramingDecoder d = new JsonArrayFramingDecoder(65_536);
    // "{}{}" is two complete values in one element slot: structural scanning rejects trailing
    // content
    // after a complete top-level value (RB-4), so the element boundary fails closed (DECODE).
    assertThatThrownBy(() -> d.push(b("[{}{}]"))).isInstanceOf(TransportIntegrityException.class);
  }

  @Test
  void jsonArrayRejectsUnbalancedCloseAtElementLevel() {
    final JsonArrayFramingDecoder d = new JsonArrayFramingDecoder(65_536);
    assertThatThrownBy(() -> d.push(b("[}]"))).isInstanceOf(TransportIntegrityException.class);
  }

  @Test
  void jsonArrayFailsClosedOnOverflow() {
    final JsonArrayFramingDecoder d = new JsonArrayFramingDecoder(4);
    assertThatThrownBy(() -> d.push(b("[123456789]")))
        .isInstanceOf(TransportIntegrityException.class);
  }

  // --- AwsEventStreamFramingDecoder (§22): binary prelude/headers/payload + CRC ---

  private static AwsEventStreamFramingDecoder aws() {
    return new AwsEventStreamFramingDecoder(65_536);
  }

  private static byte[] cat(final byte[]... parts) {
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

  private static TransportFailureClass failureOf(final FramingDecoder d, final byte[] chunk) {
    try {
      d.push(chunk);
      return null;
    } catch (final TransportIntegrityException e) {
      return e.failureClass();
    }
  }

  @Test
  void awsDecodesValidEventAsData() throws Exception {
    final var frames = aws().push(AwsEventStreamMessages.event("hello"));
    assertThat(frames).hasSize(1);
    assertThat(frames.get(0).kind()).isEqualTo(RawFrame.Kind.DATA);
    assertThat(new String(frames.get(0).payload(), StandardCharsets.UTF_8)).isEqualTo("hello");
  }

  @Test
  void awsHeartbeatIsHeartbeatKindNotTerminal() throws Exception {
    final AwsEventStreamFramingDecoder d = aws();
    final var frames = d.push(AwsEventStreamMessages.heartbeat());
    assertThat(frames).hasSize(1);
    assertThat(frames.get(0).kind()).isEqualTo(RawFrame.Kind.HEARTBEAT);
    assertThat(d.terminalObserved()).isFalse();
  }

  @Test
  void awsEndMessageIsExplicitTerminal() throws Exception {
    final AwsEventStreamFramingDecoder d = aws();
    final var frames = d.push(AwsEventStreamMessages.end());
    assertThat(frames).hasSize(1);
    assertThat(frames.get(0).kind()).isEqualTo(RawFrame.Kind.TERMINAL);
    assertThat(d.terminalObserved()).isTrue();
  }

  @Test
  void awsExceptionMessageStaysOpaqueData() throws Exception {
    // A provider exception is a provider-native event: StreamGuard stays blind and emits it as
    // opaque
    // DATA (Doc 18 §445) — never an interpreted error kind. Downstream (SchemaLock/C2) classifies
    // it.
    final var frames =
        aws().push(AwsEventStreamMessages.exception("ValidationException", "{\"m\":\"x\"}"));
    assertThat(frames).hasSize(1);
    assertThat(frames.get(0).kind()).isEqualTo(RawFrame.Kind.DATA);
    assertThat(new String(frames.get(0).payload(), StandardCharsets.UTF_8))
        .isEqualTo("{\"m\":\"x\"}");
  }

  @Test
  void awsReassemblesFragmentedMessageByteByByte() throws Exception {
    final byte[] msg = AwsEventStreamMessages.event("streamed");
    final AwsEventStreamFramingDecoder d = aws();
    RawFrame decoded = null;
    for (int i = 0; i < msg.length; i++) {
      final var frames = d.push(new byte[] {msg[i]});
      if (!frames.isEmpty()) {
        decoded = frames.get(0);
      }
    }
    assertThat(decoded).isNotNull();
    assertThat(new String(decoded.payload(), StandardCharsets.UTF_8)).isEqualTo("streamed");
  }

  @Test
  void awsMessageCrcMismatchIsCorruption() {
    final byte[] msg = AwsEventStreamMessages.event("hello");
    // Flip the final byte (inside the trailing message CRC) → whole-message CRC fails.
    final byte[] corrupt = AwsEventStreamMessages.withByteFlipped(msg, msg.length - 1);
    assertThat(failureOf(aws(), corrupt)).isEqualTo(TransportFailureClass.CORRUPTION);
  }

  @Test
  void awsPreludeCrcMismatchIsCorruption() {
    final byte[] msg = AwsEventStreamMessages.event("hello");
    // Flip a headersLength byte (index 5) without recomputing the prelude CRC → prelude CRC fails
    // first,
    // before the (now untrustworthy) length is ever used.
    final byte[] corrupt = AwsEventStreamMessages.withByteFlipped(msg, 5);
    assertThat(failureOf(aws(), corrupt)).isEqualTo(TransportFailureClass.CORRUPTION);
  }

  @Test
  void awsTruncatedFrameBuffersWithoutEmittingOrThrowing() throws Exception {
    final byte[] msg = AwsEventStreamMessages.event("hello");
    final AwsEventStreamFramingDecoder d = aws();
    final var frames = d.push(Arrays.copyOf(msg, msg.length - 3)); // 3 bytes short of complete
    assertThat(frames).isEmpty();
    assertThat(d.terminalObserved()).isFalse();
    assertThat(d.bufferedBytes()).isEqualTo(msg.length - 3); // retained within bound, not emitted
  }

  @Test
  void awsOversizedMessageFailsClosedOverflow() {
    // A validly-CRC'd message whose totalLength exceeds the configured framing bound must OVERFLOW
    // (never allocate/buffer past the bound), and only because its prelude CRC is valid and
    // trusted.
    final AwsEventStreamFramingDecoder tiny = new AwsEventStreamFramingDecoder(16);
    assertThat(failureOf(tiny, AwsEventStreamMessages.event("x")))
        .isEqualTo(TransportFailureClass.OVERFLOW);
  }

  @Test
  void awsUnknownHeaderWireTypeIsDecode() {
    // Valid CRCs but a structurally invalid header (unknown value wire-type) → DECODE after CRC
    // passes.
    final byte[] msg =
        AwsEventStreamMessages.messageRaw(
            AwsEventStreamMessages.headerWithUnknownWireType(":message-type"), new byte[0]);
    assertThat(failureOf(aws(), msg)).isEqualTo(TransportFailureClass.DECODE);
  }

  @Test
  void awsDecodesMultipleConsecutiveMessagesInOrder() throws Exception {
    final byte[] two =
        cat(AwsEventStreamMessages.event("one"), AwsEventStreamMessages.event("two"));
    final var frames = aws().push(two);
    assertThat(frames).hasSize(2);
    assertThat(new String(frames.get(0).payload(), StandardCharsets.UTF_8)).isEqualTo("one");
    assertThat(new String(frames.get(1).payload(), StandardCharsets.UTF_8)).isEqualTo("two");
  }

  @Test
  void awsDecodeIsInvariantAcrossAllChunkBoundaries() throws Exception {
    // Two messages + terminal, split at every possible boundary, must yield the identical frame
    // stream.
    final byte[] stream =
        cat(
            AwsEventStreamMessages.event("alpha"),
            AwsEventStreamMessages.event("beta"),
            AwsEventStreamMessages.end());
    for (int split = 0; split <= stream.length; split++) {
      final AwsEventStreamFramingDecoder d = aws();
      final List<String> kinds = new ArrayList<>();
      final List<String> datas = new ArrayList<>();
      collect(d.push(Arrays.copyOfRange(stream, 0, split)), kinds, datas);
      collect(d.push(Arrays.copyOfRange(stream, split, stream.length)), kinds, datas);
      assertThat(kinds).as("split at %d", split).containsExactly("DATA", "DATA", "TERMINAL");
      assertThat(datas).as("split at %d", split).containsExactly("alpha", "beta");
      assertThat(d.terminalObserved()).isTrue();
    }
  }

  private static void collect(
      final List<RawFrame> frames, final List<String> kinds, final List<String> datas) {
    for (final RawFrame f : frames) {
      kinds.add(f.kind().name());
      if (f.kind() == RawFrame.Kind.DATA) {
        datas.add(new String(f.payload(), StandardCharsets.UTF_8));
      }
    }
  }
}

package io.reliabilityai.gateway.dataplane.streamguard.domain;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.zip.CRC32;

/**
 * AWS binary event-stream framing decoder (Doc 18 §15/§22, {@code FramingType.AWS_EVENT_STREAM}) —
 * the {@code vnd.amazon.eventstream} wire format used by Bedrock-class streaming. Each message is a
 * self-delimiting binary frame:
 *
 * <pre>
 *   [ prelude: totalLength(4) | headersLength(4) | preludeCrc32(4) ]  (12 bytes, big-endian)
 *   [ headers: headersLength bytes ]
 *   [ payload: totalLength - 12 - headersLength - 4 bytes ]
 *   [ messageCrc32(4) ]
 * </pre>
 *
 * <p><b>Integrity (Doc 18 §22/§381):</b> the prelude CRC32 (over the 8 length bytes) is verified
 * <em>before</em> the length fields are trusted, so a corrupt {@code totalLength} can never drive
 * an unbounded allocation; the message CRC32 (over every byte but the trailing checksum) is
 * verified before <em>any</em> frame is emitted. Either mismatch ⇒ {@link
 * TransportFailureClass#CORRUPTION} ⇒ surface — partially-validated data is never emitted, so
 * corrupt framing never reaches SchemaLock.
 *
 * <p><b>Frame mapping (Doc 18 §63 closed taxonomy — data/heartbeat/terminal; §133/§20.1/§445
 * semantic-blindness):</b> the message is classified <em>only</em> by its {@code :message-type} /
 * {@code :event-type} transport headers, never by payload semantics — a {@code heartbeat} becomes a
 * {@link RawFrame.Kind#HEARTBEAT} (liveness only, never emitted, §23), an {@code end} becomes the
 * explicit {@link RawFrame.Kind#TERMINAL} (§21), and every content-bearing message ({@code event},
 * {@code exception}, {@code error}, or any other type) is emitted as an opaque {@link
 * RawFrame.Kind#DATA} frame whose bytes StreamGuard never interprets (a provider {@code exception}
 * is a provider-native event that only SchemaLock/C2 may classify, §445). There is no {@code ERROR}
 * transport kind.
 *
 * <p><b>Bounded (Doc 18 §31/§32):</b> a single message larger than the configured framing bound, or
 * any retained partial larger than it, fails closed with {@link TransportFailureClass#OVERFLOW};
 * structurally malformed framing (short/oversized headers, an unknown header wire-type, a header
 * that overruns its section, or a sub-minimum {@code totalLength}) fails closed with {@link
 * TransportFailureClass#DECODE}. Robust to arbitrary chunk boundaries. Provider-neutral (Doc 18
 * §11). Pure/deterministic (Doc 18 §34); no SDK, reflection, or framework. Stateful,
 * single-session-owned.
 */
public final class AwsEventStreamFramingDecoder implements FramingDecoder {

  private static final int PRELUDE_BYTES = 12;
  private static final int MESSAGE_CRC_BYTES = 4;

  /** Smallest legal message: 12-byte prelude + 0 headers + 0 payload + 4-byte message CRC. */
  private static final int MIN_MESSAGE_BYTES = PRELUDE_BYTES + MESSAGE_CRC_BYTES;

  // AWS event-stream header value wire-types (§ vnd.amazon.eventstream).
  private static final int T_BOOL_TRUE = 0;
  private static final int T_BOOL_FALSE = 1;
  private static final int T_BYTE = 2;
  private static final int T_SHORT = 3;
  private static final int T_INT = 4;
  private static final int T_LONG = 5;
  private static final int T_BYTE_ARRAY = 6;
  private static final int T_STRING = 7;
  private static final int T_TIMESTAMP = 8;
  private static final int T_UUID = 9;

  private final int maxBufferBytes;
  private byte[] pending = new byte[0];
  private boolean terminal;

  /**
   * Creates a bounded AWS event-stream decoder.
   *
   * @param maxBufferBytes the hard cap on a single buffered message / retained partial (Doc 18 §32)
   */
  public AwsEventStreamFramingDecoder(final int maxBufferBytes) {
    if (maxBufferBytes < 1) {
      throw new IllegalArgumentException("maxBufferBytes must be >= 1");
    }
    this.maxBufferBytes = maxBufferBytes;
  }

  @Override
  public List<RawFrame> push(final byte[] chunk) throws TransportIntegrityException {
    final List<RawFrame> frames = new ArrayList<>();
    final byte[] data = concat(pending, chunk);
    int pos = 0;
    final int len = data.length;

    while (len - pos >= PRELUDE_BYTES) {
      // Verify the prelude CRC BEFORE trusting the length fields (Doc 18 §22): a corrupt length
      // must
      // surface as CORRUPTION, never drive an allocation or an OVERFLOW misclassification.
      final CRC32 preludeCrc = new CRC32();
      preludeCrc.update(data, pos, 8);
      if (preludeCrc.getValue() != readUInt32(data, pos + 8)) {
        throw corruption("event-stream prelude crc mismatch");
      }

      final long totalLength = readUInt32(data, pos);
      final long headersLength = readUInt32(data, pos + 4);
      if (totalLength < MIN_MESSAGE_BYTES) {
        throw decode("event-stream totalLength below minimum");
      }
      if (totalLength > maxBufferBytes) {
        throw overflow("event-stream message exceeds framing bound");
      }
      // payloadLength = totalLength - PRELUDE - headersLength - CRC must be >= 0.
      if (headersLength > totalLength - MIN_MESSAGE_BYTES) {
        throw decode("event-stream headersLength overruns message");
      }
      final int total = (int) totalLength;
      if (len - pos < total) {
        break; // full message not yet arrived — retain and await more (bounded, total <= cap)
      }

      // Verify the whole-message CRC (over every byte but the trailing checksum) before emitting.
      final CRC32 messageCrc = new CRC32();
      messageCrc.update(data, pos, total - MESSAGE_CRC_BYTES);
      if (messageCrc.getValue() != readUInt32(data, pos + total - MESSAGE_CRC_BYTES)) {
        throw corruption("event-stream message crc mismatch");
      }

      final int headers = (int) headersLength;
      final Classification kind =
          classify(data, pos + PRELUDE_BYTES, pos + PRELUDE_BYTES + headers);
      final int payloadOffset = pos + PRELUDE_BYTES + headers;
      final int payloadLength = total - PRELUDE_BYTES - headers - MESSAGE_CRC_BYTES;

      switch (kind) {
        case HEARTBEAT -> frames.add(RawFrame.of(RawFrame.Kind.HEARTBEAT, new byte[0]));
        case TERMINAL -> {
          terminal = true;
          frames.add(RawFrame.of(RawFrame.Kind.TERMINAL, new byte[0]));
        }
        case DATA ->
            frames.add(
                RawFrame.of(
                    RawFrame.Kind.DATA,
                    Arrays.copyOfRange(data, payloadOffset, payloadOffset + payloadLength)));
      }
      pos += total;
    }

    pending = Arrays.copyOfRange(data, pos, len);
    if (pending.length > maxBufferBytes) {
      throw overflow("event-stream buffer overflow");
    }
    return frames;
  }

  /**
   * The transport role of a decoded message (mapped onto the frozen 3-kind {@link RawFrame.Kind}).
   */
  private enum Classification {
    DATA,
    HEARTBEAT,
    TERMINAL
  }

  /**
   * Classifies a message from its transport headers only (Doc 18 §133/§20.1 — never payload
   * semantics). Recognizes {@code heartbeat} (liveness) and {@code end} (terminal) in either {@code
   * :event-type} or {@code :message-type}; everything else (including {@code event}, {@code
   * exception}, {@code error}, and unknown types) is opaque {@code DATA}.
   */
  private static Classification classify(final byte[] data, final int start, final int end)
      throws TransportIntegrityException {
    String messageType = null;
    String eventType = null;
    int off = start;
    while (off < end) {
      final int nameLen = readUByte(data, off, end);
      off += 1;
      require(off + nameLen <= end, "event-stream header name overruns");
      final String name = new String(data, off, nameLen, StandardCharsets.UTF_8);
      off += nameLen;
      final int type = readUByte(data, off, end);
      off += 1;
      switch (type) {
        case T_BOOL_TRUE, T_BOOL_FALSE -> {
          // no value bytes
        }
        case T_BYTE -> off = skip(off, 1, end);
        case T_SHORT -> off = skip(off, 2, end);
        case T_INT -> off = skip(off, 4, end);
        case T_LONG, T_TIMESTAMP -> off = skip(off, 8, end);
        case T_UUID -> off = skip(off, 16, end);
        case T_BYTE_ARRAY -> {
          final int vlen = readUInt16(data, off, end);
          off = skip(off + 2, vlen, end);
        }
        case T_STRING -> {
          final int vlen = readUInt16(data, off, end);
          off += 2;
          require(off + vlen <= end, "event-stream header value overruns");
          final String value = new String(data, off, vlen, StandardCharsets.UTF_8);
          off += vlen;
          if (":message-type".equals(name)) {
            messageType = value;
          } else if (":event-type".equals(name)) {
            eventType = value;
          }
        }
        default -> throw decode("event-stream unknown header wire-type");
      }
    }
    require(off == end, "event-stream headers section misaligned");
    if ("heartbeat".equals(eventType) || "heartbeat".equals(messageType)) {
      return Classification.HEARTBEAT;
    }
    if ("end".equals(eventType) || "end".equals(messageType)) {
      return Classification.TERMINAL;
    }
    return Classification.DATA;
  }

  @Override
  public boolean terminalObserved() {
    return terminal;
  }

  @Override
  public int bufferedBytes() {
    return pending.length;
  }

  // --- pure byte helpers (big-endian, bounds-checked) ---

  private static byte[] concat(final byte[] a, final byte[] b) {
    if (a.length == 0) {
      return b.clone();
    }
    if (b.length == 0) {
      return a;
    }
    final byte[] out = new byte[a.length + b.length];
    System.arraycopy(a, 0, out, 0, a.length);
    System.arraycopy(b, 0, out, a.length, b.length);
    return out;
  }

  private static long readUInt32(final byte[] b, final int off) {
    return (((long) (b[off] & 0xFF)) << 24)
        | ((long) (b[off + 1] & 0xFF) << 16)
        | ((long) (b[off + 2] & 0xFF) << 8)
        | ((long) (b[off + 3] & 0xFF));
  }

  private static int readUInt16(final byte[] b, final int off, final int end)
      throws TransportIntegrityException {
    require(off + 2 <= end, "event-stream header truncated (uint16)");
    return ((b[off] & 0xFF) << 8) | (b[off + 1] & 0xFF);
  }

  private static int readUByte(final byte[] b, final int off, final int end)
      throws TransportIntegrityException {
    require(off + 1 <= end, "event-stream header truncated (uint8)");
    return b[off] & 0xFF;
  }

  private static int skip(final int off, final int n, final int end)
      throws TransportIntegrityException {
    require(off + n <= end, "event-stream header value truncated");
    return off + n;
  }

  private static void require(final boolean condition, final String reason)
      throws TransportIntegrityException {
    if (!condition) {
      throw decode(reason);
    }
  }

  private static TransportIntegrityException decode(final String reason) {
    return new TransportIntegrityException(TransportFailureClass.DECODE, reason);
  }

  private static TransportIntegrityException corruption(final String reason) {
    return new TransportIntegrityException(TransportFailureClass.CORRUPTION, reason);
  }

  private static TransportIntegrityException overflow(final String reason) {
    return new TransportIntegrityException(TransportFailureClass.OVERFLOW, reason);
  }
}

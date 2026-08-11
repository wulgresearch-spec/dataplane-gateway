package io.reliabilityai.gateway.dataplane.streamguard.domain;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.zip.CRC32;

/**
 * Test-only builder for {@code vnd.amazon.eventstream} binary messages (Doc 18 §22). Assembles the
 * 12-byte prelude (total length, headers length, prelude CRC32), typed string headers, an opaque
 * payload, and the trailing message CRC32 — the exact wire shape {@link
 * AwsEventStreamFramingDecoder} decodes. Big-endian throughout. Kept deterministic (no
 * clocks/randomness) so decode tests are exact.
 */
public final class AwsEventStreamMessages {

  private static final int TYPE_STRING = 7;

  private AwsEventStreamMessages() {}

  /** A content ({@code :message-type = event}) message carrying the given ASCII payload. */
  public static byte[] event(final String payload) {
    return message(headers(":message-type", "event"), payload.getBytes(StandardCharsets.UTF_8));
  }

  /** A liveness ({@code :event-type = heartbeat}) message with an empty payload. */
  public static byte[] heartbeat() {
    return message(headers(":event-type", "heartbeat"), new byte[0]);
  }

  /** An explicit terminal ({@code :event-type = end}) message with an empty payload. */
  public static byte[] end() {
    return message(headers(":event-type", "end"), new byte[0]);
  }

  /** A provider {@code exception} message (opaque payload; StreamGuard stays blind → DATA). */
  public static byte[] exception(final String exceptionType, final String payload) {
    final LinkedHashMap<String, String> h = new LinkedHashMap<>();
    h.put(":message-type", "exception");
    h.put(":exception-type", exceptionType);
    return message(h, payload.getBytes(StandardCharsets.UTF_8));
  }

  private static LinkedHashMap<String, String> headers(final String name, final String value) {
    final LinkedHashMap<String, String> h = new LinkedHashMap<>();
    h.put(name, value);
    return h;
  }

  /** Assembles a valid message from typed string headers and a payload, computing both CRC32s. */
  public static byte[] message(final Map<String, String> stringHeaders, final byte[] payload) {
    return messageRaw(encodeStringHeaders(stringHeaders), payload);
  }

  /**
   * Assembles a valid message (correct CRCs) from raw header bytes — used to inject malformed
   * headers.
   */
  public static byte[] messageRaw(final byte[] headerBytes, final byte[] payload) {
    final int total = 12 + headerBytes.length + payload.length + 4;
    final ByteBuffer bb = ByteBuffer.allocate(total); // big-endian by default
    bb.putInt(total);
    bb.putInt(headerBytes.length);
    final CRC32 preludeCrc = new CRC32();
    preludeCrc.update(bb.array(), 0, 8);
    bb.putInt((int) preludeCrc.getValue());
    bb.put(headerBytes);
    bb.put(payload);
    final CRC32 messageCrc = new CRC32();
    messageCrc.update(bb.array(), 0, total - 4);
    bb.putInt((int) messageCrc.getValue());
    return bb.array();
  }

  /** Encodes {@code name → value} pairs as typed {@code STRING} headers in wire order. */
  public static byte[] encodeStringHeaders(final Map<String, String> stringHeaders) {
    final ByteArrayOutputStream out = new ByteArrayOutputStream();
    for (final Map.Entry<String, String> e : stringHeaders.entrySet()) {
      final byte[] name = e.getKey().getBytes(StandardCharsets.UTF_8);
      final byte[] value = e.getValue().getBytes(StandardCharsets.UTF_8);
      out.write(name.length); // uint8 name length
      out.write(name, 0, name.length);
      out.write(TYPE_STRING); // value wire-type
      out.write((value.length >> 8) & 0xFF); // uint16 BE value length
      out.write(value.length & 0xFF);
      out.write(value, 0, value.length);
    }
    return out.toByteArray();
  }

  /** A single header with an <b>unknown</b> value wire-type (99) — structurally invalid headers. */
  public static byte[] headerWithUnknownWireType(final String name) {
    final byte[] nameBytes = name.getBytes(StandardCharsets.UTF_8);
    final ByteArrayOutputStream out = new ByteArrayOutputStream();
    out.write(nameBytes.length);
    out.write(nameBytes, 0, nameBytes.length);
    out.write(99); // not a valid AWS event-stream header wire-type
    return out.toByteArray();
  }

  /** Returns a copy with byte {@code index} bit-flipped (to corrupt a CRC-covered region). */
  public static byte[] withByteFlipped(final byte[] message, final int index) {
    final byte[] copy = message.clone();
    copy[index] = (byte) (copy[index] ^ 0xFF);
    return copy;
  }
}

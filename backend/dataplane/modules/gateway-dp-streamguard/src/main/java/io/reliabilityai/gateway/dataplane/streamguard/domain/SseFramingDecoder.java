package io.reliabilityai.gateway.dataplane.streamguard.domain;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Server-Sent-Events framing decoder (Doc 18 §15, {@code FramingType.SSE}). Line-based ({@code
 * field: value}), event boundary on a blank line, {@code data:} accumulation (multi-{@code data}
 * joined with {@code \n} per the SSE spec), {@code :}-comment lines as heartbeats (Doc 18 §23), and
 * a {@code [DONE]}-class {@code data} payload surfaced as an explicit transport <b>terminal</b>
 * (Doc 18 §21) — never interpreted semantically. Robust to CRLF/LF and chunk-split lines. Bounded:
 * line + accumulated data are capped, overflow fails closed ({@code OVERFLOW}, Doc 18 §31/§32). It
 * decodes <b>"SSE," never "OpenAI"</b> (Doc 18 §11, AD-007). Stateful, single-session-owned.
 */
public final class SseFramingDecoder implements FramingDecoder {

  private static final byte[] DONE = "[DONE]".getBytes(StandardCharsets.US_ASCII);

  private final int maxBufferBytes;
  private final ByteArrayOutputStream line = new ByteArrayOutputStream();
  private final ByteArrayOutputStream data = new ByteArrayOutputStream();
  private boolean hasData;
  private boolean terminal;

  /**
   * Creates a bounded SSE decoder.
   *
   * @param maxBufferBytes the hard cap on buffered line + accumulated data bytes (Doc 18 §32)
   */
  public SseFramingDecoder(final int maxBufferBytes) {
    if (maxBufferBytes < 1) {
      throw new IllegalArgumentException("maxBufferBytes must be >= 1");
    }
    this.maxBufferBytes = maxBufferBytes;
  }

  @Override
  public List<RawFrame> push(final byte[] chunk) throws TransportIntegrityException {
    final List<RawFrame> frames = new ArrayList<>();
    for (final byte b : chunk) {
      if (b == '\n') {
        processLine(frames);
        line.reset();
      } else {
        line.write(b);
        checkBounds();
      }
    }
    return frames;
  }

  private void processLine(final List<RawFrame> frames) throws TransportIntegrityException {
    byte[] bytes = line.toByteArray();
    if (bytes.length > 0 && bytes[bytes.length - 1] == '\r') {
      bytes = Arrays.copyOf(bytes, bytes.length - 1); // strip CR of a CRLF pair
    }
    if (bytes.length == 0) {
      flushEvent(frames); // blank line = event boundary
      return;
    }
    if (bytes[0] == ':') {
      frames.add(RawFrame.of(RawFrame.Kind.HEARTBEAT, new byte[0])); // comment / keepalive (§23)
      return;
    }
    final int colon = indexOf(bytes, (byte) ':');
    final String field =
        new String(bytes, 0, colon < 0 ? bytes.length : colon, StandardCharsets.US_ASCII);
    if (!"data".equals(field)) {
      return; // event:/id:/retry: are not transport content — ignored
    }
    int start = colon + 1;
    if (start < bytes.length && bytes[start] == ' ') {
      start++; // strip a single leading space after the colon (SSE spec)
    }
    if (hasData) {
      data.write('\n'); // multiple data fields join with newline
    }
    data.write(bytes, start, bytes.length - start);
    hasData = true;
    checkBounds();
  }

  private void flushEvent(final List<RawFrame> frames) {
    if (!hasData) {
      return; // blank line with no pending data — ignore
    }
    final byte[] payload = data.toByteArray();
    data.reset();
    hasData = false;
    if (Arrays.equals(payload, DONE)) {
      terminal = true;
      frames.add(RawFrame.of(RawFrame.Kind.TERMINAL, new byte[0])); // explicit transport terminal
    } else {
      frames.add(RawFrame.of(RawFrame.Kind.DATA, payload));
    }
  }

  private void checkBounds() throws TransportIntegrityException {
    if (line.size() + data.size() > maxBufferBytes) {
      throw new TransportIntegrityException(TransportFailureClass.OVERFLOW, "sse buffer overflow");
    }
  }

  private static int indexOf(final byte[] bytes, final byte target) {
    for (int i = 0; i < bytes.length; i++) {
      if (bytes[i] == target) {
        return i;
      }
    }
    return -1;
  }

  @Override
  public boolean terminalObserved() {
    return terminal;
  }

  @Override
  public int bufferedBytes() {
    return line.size() + data.size();
  }
}

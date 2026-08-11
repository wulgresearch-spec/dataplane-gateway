package io.reliabilityai.gateway.dataplane.streamguard.domain;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Newline-delimited JSON framing decoder (Doc 18 §15, {@code FramingType.NDJSON}). Each non-empty
 * line is a complete JSON value delimited by {@code \n}; the decoder confirms each emitted line is
 * a <b>structurally well-formed</b> JSON fragment via {@link JsonStructuralScanner} (Doc 18 §20.1)
 * — never semantics/schema — and surfaces a {@code [DONE]}-class line as an explicit transport
 * <b>terminal</b> (Doc 18 §21). Robust to CRLF/LF and chunk-split lines. Bounded: the pending line
 * is capped, overflow fails closed ({@code OVERFLOW}, Doc 18 §32). Provider-neutral (Doc 18 §11).
 * Stateful, single-session-owned.
 */
public final class NdjsonFramingDecoder implements FramingDecoder {

  private static final byte[] DONE = "[DONE]".getBytes(StandardCharsets.US_ASCII);

  private final int maxBufferBytes;
  private final ByteArrayOutputStream line = new ByteArrayOutputStream();
  private boolean terminal;

  /**
   * Creates a bounded NDJSON decoder.
   *
   * @param maxBufferBytes the hard cap on the buffered pending line (Doc 18 §32)
   */
  public NdjsonFramingDecoder(final int maxBufferBytes) {
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
        if (line.size() > maxBufferBytes) {
          throw new TransportIntegrityException(
              TransportFailureClass.OVERFLOW, "ndjson buffer overflow");
        }
      }
    }
    return frames;
  }

  private void processLine(final List<RawFrame> frames) throws TransportIntegrityException {
    byte[] bytes = line.toByteArray();
    if (bytes.length > 0 && bytes[bytes.length - 1] == '\r') {
      bytes = Arrays.copyOf(bytes, bytes.length - 1);
    }
    if (bytes.length == 0) {
      return; // blank line — ignore
    }
    if (Arrays.equals(bytes, DONE)) {
      terminal = true;
      frames.add(RawFrame.of(RawFrame.Kind.TERMINAL, new byte[0]));
      return;
    }
    if (!JsonStructuralScanner.isCompleteWellFormed(bytes)) {
      throw new TransportIntegrityException(
          TransportFailureClass.DECODE, "ndjson line not well-formed json");
    }
    frames.add(RawFrame.of(RawFrame.Kind.DATA, bytes));
  }

  @Override
  public boolean terminalObserved() {
    return terminal;
  }

  @Override
  public int bufferedBytes() {
    return line.size();
  }
}

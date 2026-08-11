package io.reliabilityai.gateway.dataplane.streamguard.domain;

import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Chunked-JSON-array framing decoder (Doc 18 §15/§21, {@code FramingType.JSON_ARRAY_CHUNKED}). The
 * transport delivers a single top-level JSON array {@code [ e0, e1, ... ]} in arbitrary byte
 * chunks; each top-level array element is one complete JSON value, emitted as a {@code DATA} frame
 * once its top-level comma or the array's closing bracket is reached, and the array's closing
 * {@code ]} is the explicit transport <b>terminal</b> (Doc 18 §21) — there is no {@code [DONE]}
 * sentinel in this framing.
 *
 * <p>Like the SSE/NDJSON decoders it is <b>structurally aware but semantically blind</b> (Doc 18
 * §20.1, RB-4): each emitted element is confirmed structurally well-formed via {@link
 * JsonStructuralScanner} — never interpreted, never schema-checked (that is exclusively
 * SchemaLock's, SG-A18). Element and nested delimiters are disambiguated by tracking string/escape
 * state and brace/bracket depth, so a {@code ,} or {@code ]} inside a string or a nested container
 * is never mistaken for a top-level boundary. Robust to chunk-split elements. Bounded: the pending
 * element buffer is capped and overflow fails closed ({@code OVERFLOW}, Doc 18 §32); malformed
 * structure (not an array, unbalanced close, trailing comma, an element that is not well-formed
 * JSON, or trailing content after the array closes) fails closed ({@code DECODE}). Provider-neutral
 * (Doc 18 §11). Pure/deterministic (Doc 18 §34). Stateful, single-session-owned.
 */
public final class JsonArrayFramingDecoder implements FramingDecoder {

  private enum State {
    /** Before the array's opening {@code [}. */
    BEFORE_ARRAY,
    /** Inside the array, at a position where an element or the closing {@code ]} may begin. */
    EXPECT_ELEMENT_OR_CLOSE,
    /** Accumulating the bytes of the current element. */
    IN_ELEMENT,
    /**
     * Just consumed a top-level {@code ,}; an element must follow (a {@code ]} here is a trailing
     * comma).
     */
    EXPECT_ELEMENT,
    /** The array's closing {@code ]} has been observed; only trailing whitespace is permitted. */
    DONE
  }

  private final int maxBufferBytes;
  private final ByteArrayOutputStream element = new ByteArrayOutputStream();
  private State state = State.BEFORE_ARRAY;
  private int depth; // nesting within the current element (0 == the array's element level)
  private boolean inString;
  private boolean escaped;
  private boolean terminal;

  /**
   * Creates a bounded chunked-JSON-array decoder.
   *
   * @param maxBufferBytes the hard cap on the buffered pending element (Doc 18 §32)
   */
  public JsonArrayFramingDecoder(final int maxBufferBytes) {
    if (maxBufferBytes < 1) {
      throw new IllegalArgumentException("maxBufferBytes must be >= 1");
    }
    this.maxBufferBytes = maxBufferBytes;
  }

  @Override
  public List<RawFrame> push(final byte[] chunk) throws TransportIntegrityException {
    final List<RawFrame> frames = new ArrayList<>();
    for (final byte b : chunk) {
      consume(b, frames);
    }
    return frames;
  }

  private void consume(final byte b, final List<RawFrame> frames)
      throws TransportIntegrityException {
    final char c = (char) (b & 0xFF);

    // Inside a string literal every byte is opaque element content — delimiters do not apply.
    if (inString) {
      append(b);
      if (escaped) {
        escaped = false;
      } else if (c == '\\') {
        escaped = true;
      } else if (c == '"') {
        inString = false;
      }
      return;
    }

    final boolean whitespace = c == ' ' || c == '\t' || c == '\r' || c == '\n';

    switch (state) {
      case DONE -> {
        if (!whitespace) {
          throw decode("trailing content after json array close");
        }
      }
      case BEFORE_ARRAY -> {
        if (whitespace) {
          return; // leading whitespace before the array
        }
        if (c != '[') {
          throw decode("json_array_chunked stream did not open with '['");
        }
        state = State.EXPECT_ELEMENT_OR_CLOSE;
        depth = 0;
      }
      default -> consumeInArray(b, c, whitespace, frames);
    }
  }

  private void consumeInArray(
      final byte b, final char c, final boolean whitespace, final List<RawFrame> frames)
      throws TransportIntegrityException {
    // Top-level structural tokens are only meaningful outside any nested element container.
    if (depth == 0) {
      if (c == ',') {
        if (state != State.IN_ELEMENT) {
          throw decode("empty json array element"); // leading/double comma, e.g. "[," or "[1,,2]"
        }
        flushElement(frames);
        state = State.EXPECT_ELEMENT;
        return;
      }
      if (c == ']') {
        if (state == State.IN_ELEMENT) {
          flushElement(frames);
        } else if (state == State.EXPECT_ELEMENT) {
          throw decode("trailing comma before json array close"); // e.g. "[1,]"
        }
        // EXPECT_ELEMENT_OR_CLOSE with no pending element == an empty array "[]": no DATA frame.
        terminal = true;
        frames.add(RawFrame.of(RawFrame.Kind.TERMINAL, new byte[0]));
        state = State.DONE;
        return;
      }
      if (c == '}') {
        throw decode("unbalanced '}' at json array element level");
      }
      if (whitespace && state != State.IN_ELEMENT) {
        return; // insignificant whitespace before an element begins
      }
    }

    // Otherwise this byte is element content (starting or continuing the current element).
    if (state != State.IN_ELEMENT) {
      state = State.IN_ELEMENT;
    }
    switch (c) {
      case '"' -> inString = true;
      case '{', '[' -> depth++;
      case '}', ']' -> {
        depth--;
        if (depth < 0) {
          throw decode("unbalanced close within json array element");
        }
      }
      default -> {
        // number/true/false/null bytes, whitespace within an element, or a ',' nested at depth > 0
      }
    }
    append(b);
  }

  private void append(final byte b) throws TransportIntegrityException {
    element.write(b);
    if (element.size() > maxBufferBytes) {
      throw new TransportIntegrityException(
          TransportFailureClass.OVERFLOW, "json array element buffer overflow");
    }
  }

  private void flushElement(final List<RawFrame> frames) throws TransportIntegrityException {
    final byte[] trimmed = trim(element.toByteArray());
    // A flush only occurs from IN_ELEMENT, so content is present; the well-formed check is the real
    // gate.
    if (trimmed.length == 0 || !JsonStructuralScanner.isCompleteWellFormed(trimmed)) {
      throw decode("json array element is not a complete well-formed json value");
    }
    frames.add(RawFrame.of(RawFrame.Kind.DATA, trimmed));
    element.reset();
    depth = 0;
    inString = false;
    escaped = false;
  }

  private static byte[] trim(final byte[] bytes) {
    int start = 0;
    int end = bytes.length;
    while (start < end && isWhitespace(bytes[start])) {
      start++;
    }
    while (end > start && isWhitespace(bytes[end - 1])) {
      end--;
    }
    return Arrays.copyOfRange(bytes, start, end);
  }

  private static boolean isWhitespace(final byte b) {
    return b == ' ' || b == '\t' || b == '\r' || b == '\n';
  }

  private static TransportIntegrityException decode(final String reason) {
    return new TransportIntegrityException(TransportFailureClass.DECODE, reason);
  }

  @Override
  public boolean terminalObserved() {
    return terminal;
  }

  @Override
  public int bufferedBytes() {
    return element.size();
  }
}

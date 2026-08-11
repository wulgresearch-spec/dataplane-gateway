package io.reliabilityai.gateway.dataplane.streamguard.domain;

import java.util.List;

/**
 * A hardened, bounded, incremental transport-framing decoder (Doc 18 §11/§14, SG-D2). It converts
 * raw provider bytes into ordered {@link RawFrame}s, buffering an incomplete unit across chunk
 * boundaries until complete (Doc 18 §14) and failing closed on any bound breach or malformed
 * framing. It is keyed on a neutral {@code FramingType}, <b>never</b> on provider identity (Doc 18
 * §11, AD-007), and never interprets payload semantics (Doc 18 §20.1). Stateful and
 * single-session-owned (Doc 18 §36.1); deterministic (Doc 18 §34).
 */
public interface FramingDecoder {

  /**
   * Feeds a raw transport chunk, returning any newly-completed frames (Doc 18 §14). Incomplete
   * trailing bytes are buffered within bounds.
   *
   * @param chunk the raw provider bytes (arrival order)
   * @return the newly-completed frames, in order (possibly empty)
   * @throws TransportIntegrityException on a bound breach ({@code OVERFLOW}) or malformed framing
   *     ({@code DECODE}/{@code CORRUPTION})
   */
  List<RawFrame> push(byte[] chunk) throws TransportIntegrityException;

  /**
   * Whether an explicit transport terminal has been observed in-band (Doc 18 §21). Completion is
   * marked only on an explicit terminal — never inferred from a silent close.
   *
   * @return {@code true} if an explicit terminal frame was decoded
   */
  boolean terminalObserved();

  /**
   * The bytes currently buffered for an incomplete unit (for bounded-buffer accounting; no content
   * exposure).
   *
   * @return the buffered byte count
   */
  int bufferedBytes();
}

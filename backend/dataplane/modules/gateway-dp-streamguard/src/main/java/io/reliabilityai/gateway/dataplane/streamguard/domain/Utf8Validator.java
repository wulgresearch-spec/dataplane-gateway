package io.reliabilityai.gateway.dataplane.streamguard.domain;

import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;

/**
 * Strict UTF-8 validation (Doc 18 §13, SG-D5) — <b>reject, never substitute</b>. Delegates to the
 * vetted JDK UTF-8 {@link CharsetDecoder} in {@code REPORT} mode, which rejects overlong encodings,
 * illegal surrogates, and invalid/incomplete sequences (Doc 18 §40 UTF-8 attacks) — it never emits
 * U+FFFD and never drops bytes (substitution = silent corruption, the cardinal sin). Because
 * framing (Doc 18 §11) delimits on ASCII boundaries and buffers partial units across chunks, a
 * complete frame payload is a complete byte sequence; an incomplete trailing multi-byte sequence at
 * a true frame boundary is therefore a genuine malformed/{@code TRUNCATED} input and is rejected.
 */
public final class Utf8Validator {

  private Utf8Validator() {}

  /**
   * Validates that the payload is strictly well-formed UTF-8 (Doc 18 §13); rejects otherwise.
   *
   * @param payload the opaque frame payload bytes
   * @throws TransportIntegrityException {@code DECODE} if the payload is not strict UTF-8
   */
  public static void validateStrict(final byte[] payload) throws TransportIntegrityException {
    final CharsetDecoder decoder =
        StandardCharsets.UTF_8
            .newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT);
    try {
      decoder.decode(ByteBuffer.wrap(payload));
    } catch (final CharacterCodingException e) {
      // Content-free: never surface the payload bytes (Doc 18 §40/§44).
      throw new TransportIntegrityException(TransportFailureClass.DECODE, "invalid utf-8");
    }
  }
}

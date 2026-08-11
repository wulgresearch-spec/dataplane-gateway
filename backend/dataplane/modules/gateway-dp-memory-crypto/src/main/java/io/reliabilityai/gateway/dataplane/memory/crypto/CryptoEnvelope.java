package io.reliabilityai.gateway.dataplane.memory.crypto;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Base64;

/**
 * The on-disk shape of a sealed body (AD-028 §4).
 *
 * <p>Layout, in order, before Base64URL encoding:
 *
 * <pre>
 *   magic        3   'M' 'K' '1'
 *   format       1   currently 1
 *   versionLen   1   1..64
 *   version      n   ASCII key version label
 *   wrapNonce   12   GCM nonce for the data-key wrap
 *   wrapLen      2   big-endian length of the wrapped data key
 *   wrappedDek   m   data key sealed under the master key, tag included
 *   dataNonce   12   GCM nonce for the body
 *   ciphertext   *   body sealed under the data key, tag included
 * </pre>
 *
 * <p>Everything here is public by design. None of these fields is secret: nonces are not secret,
 * the key version is a label, and the wrapped key is useless without the master key. What matters
 * is that every one of them is <em>authenticated</em> — the version is bound into the additional
 * data of both GCM operations, so an attacker who edits the version byte to point at a weaker key
 * gets a tag failure rather than a downgrade. That is the whole reason the version travels inside
 * the envelope as well as in the key reference stored beside it.
 *
 * <p>Parsing is strict and total. Every length is checked against what remains before it is used,
 * so a truncated or padded envelope is rejected structurally rather than reaching the cipher.
 *
 * @param version the key version label this was sealed under
 * @param wrapNonce the nonce used to wrap the data key
 * @param wrappedDek the data key sealed under the master key
 * @param dataNonce the nonce used to seal the body
 * @param ciphertext the sealed body
 */
record CryptoEnvelope(
    String version, byte[] wrapNonce, byte[] wrappedDek, byte[] dataNonce, byte[] ciphertext) {

  private static final byte[] MAGIC = {'M', 'K', '1'};

  static final byte FORMAT = 1;

  private static final int MAX_VERSION_BYTES = 64;

  /** GCM standard nonce length. Anything else forces a slower, worse-specified derivation. */
  static final int NONCE_BYTES = 12;

  /** GCM tag length in bytes, so a ciphertext shorter than this cannot be authentic. */
  static final int TAG_BYTES = 16;

  /**
   * Serialises the envelope.
   *
   * @return the Base64URL form handed to the memory runtime as a ciphertext string
   */
  String encode() {
    final byte[] versionBytes = version.getBytes(StandardCharsets.US_ASCII);
    final byte[] out =
        new byte
            [MAGIC.length
                + 1
                + 1
                + versionBytes.length
                + wrapNonce.length
                + 2
                + wrappedDek.length
                + dataNonce.length
                + ciphertext.length];
    int at = 0;
    System.arraycopy(MAGIC, 0, out, at, MAGIC.length);
    at += MAGIC.length;
    out[at++] = FORMAT;
    out[at++] = (byte) versionBytes.length;
    System.arraycopy(versionBytes, 0, out, at, versionBytes.length);
    at += versionBytes.length;
    System.arraycopy(wrapNonce, 0, out, at, wrapNonce.length);
    at += wrapNonce.length;
    out[at++] = (byte) (wrappedDek.length >>> 8);
    out[at++] = (byte) wrappedDek.length;
    System.arraycopy(wrappedDek, 0, out, at, wrappedDek.length);
    at += wrappedDek.length;
    System.arraycopy(dataNonce, 0, out, at, dataNonce.length);
    at += dataNonce.length;
    System.arraycopy(ciphertext, 0, out, at, ciphertext.length);
    return Base64.getUrlEncoder().withoutPadding().encodeToString(out);
  }

  /**
   * Parses an envelope, refusing anything that is not exactly one.
   *
   * @param encoded the Base64URL form
   * @return the parsed envelope
   * @throws MalformedEnvelopeException when the input is not a well-formed envelope
   */
  static CryptoEnvelope decode(final String encoded) {
    final byte[] raw;
    try {
      raw = Base64.getUrlDecoder().decode(encoded);
    } catch (final IllegalArgumentException notBase64) {
      throw new MalformedEnvelopeException("sealed body is not valid base64url");
    }
    int at = 0;
    if (raw.length < MAGIC.length + 2) {
      throw new MalformedEnvelopeException("sealed body is shorter than a header");
    }
    for (final byte expected : MAGIC) {
      if (raw[at++] != expected) {
        throw new MalformedEnvelopeException("sealed body does not carry the envelope magic");
      }
    }
    final byte format = raw[at++];
    if (format != FORMAT) {
      throw new MalformedEnvelopeException("unsupported envelope format " + format);
    }
    final int versionLength = raw[at++] & 0xff;
    if (versionLength == 0 || versionLength > MAX_VERSION_BYTES) {
      throw new MalformedEnvelopeException("envelope key version length is out of range");
    }
    final String version = new String(read(raw, at, versionLength), StandardCharsets.US_ASCII);
    at += versionLength;
    final byte[] wrapNonce = read(raw, at, NONCE_BYTES);
    at += NONCE_BYTES;
    if (at + 2 > raw.length) {
      throw new MalformedEnvelopeException("envelope ends before the wrapped-key length");
    }
    final int wrapLength = ((raw[at] & 0xff) << 8) | (raw[at + 1] & 0xff);
    at += 2;
    if (wrapLength <= TAG_BYTES) {
      throw new MalformedEnvelopeException("wrapped key is too short to carry a tag");
    }
    final byte[] wrappedDek = read(raw, at, wrapLength);
    at += wrapLength;
    final byte[] dataNonce = read(raw, at, NONCE_BYTES);
    at += NONCE_BYTES;
    final int remaining = raw.length - at;
    if (remaining < TAG_BYTES) {
      throw new MalformedEnvelopeException("sealed body is too short to carry a tag");
    }
    return new CryptoEnvelope(version, wrapNonce, wrappedDek, dataNonce, read(raw, at, remaining));
  }

  /**
   * Copies a run of bytes, refusing to read past the end.
   *
   * @param source the buffer
   * @param from the first index
   * @param length how many bytes
   * @return the copied bytes
   * @throws MalformedEnvelopeException when the buffer is shorter than the declared length
   */
  private static byte[] read(final byte[] source, final int from, final int length) {
    if (from + length > source.length) {
      throw new MalformedEnvelopeException("envelope declares more bytes than it carries");
    }
    return Arrays.copyOfRange(source, from, from + length);
  }
}

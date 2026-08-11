package io.reliabilityai.gateway.dataplane.memory.domain;

import io.reliabilityai.gateway.common.Preconditions;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/**
 * Stable content digests (MEM-24).
 *
 * <p>Used for three distinct jobs, all of which need the same property — that the same bytes always
 * give the same hex, on any node, in any JVM:
 *
 * <ul>
 *   <li><b>Integrity.</b> A record whose digest does not match its content has been tampered with
 *       or corrupted, and is refused on read rather than served.
 *   <li><b>Audit.</b> An event names content by digest, so the trail can identify a specific body
 *       without containing it (MEM-26).
 *   <li><b>Delete proof.</b> The proof binds a digest, so it confirms that one specific known thing
 *       was destroyed while remaining useless for reconstructing it.
 * </ul>
 *
 * <p>Not a security boundary on its own. A digest proves that content is unchanged; it does not
 * prove who wrote it, and the runtime never treats a matching digest as authorization.
 */
public final class MemoryDigest {

  private static final String ALGORITHM = "SHA-256";
  private static final char[] HEX = "0123456789abcdef".toCharArray();

  /**
   * How many hex characters are kept. 128 bits is far past collision relevance for this purpose.
   */
  private static final int HEX_LENGTH = 32;

  private MemoryDigest() {
    throw new AssertionError("no instances");
  }

  /**
   * Digests a value.
   *
   * @param value the content to digest
   * @return a lowercase hex prefix of the SHA-256 digest
   */
  public static String of(final String value) {
    Preconditions.requireNonNull(value, "value");
    final byte[] bytes = digestBytes(value.getBytes(StandardCharsets.UTF_8));
    final StringBuilder hex = new StringBuilder(HEX_LENGTH);
    for (int i = 0; i < HEX_LENGTH / 2; i++) {
      final int unsigned = bytes[i] & 0xFF;
      hex.append(HEX[unsigned >>> 4]).append(HEX[unsigned & 0x0F]);
    }
    return hex.toString();
  }

  /**
   * Reports whether content still matches a digest.
   *
   * <p>Compared in <b>constant time</b>. A digest comparison that short-circuits on the first
   * differing character leaks, through timing, how much of a guess was right — which turns forging
   * a delete proof from a search over the whole space into a search one character at a time.
   *
   * @param value the content
   * @param expected the digest recorded with it
   * @return true when they match
   */
  public static boolean matches(final String value, final String expected) {
    Preconditions.requireNonNull(value, "value");
    Preconditions.requireNonNull(expected, "expected");
    return constantTimeEquals(of(value), expected);
  }

  /**
   * Compares two digests without leaking where they first differ.
   *
   * @param left the first digest
   * @param right the second digest
   * @return true when equal
   */
  public static boolean constantTimeEquals(final String left, final String right) {
    Preconditions.requireNonNull(left, "left");
    Preconditions.requireNonNull(right, "right");
    if (left.length() != right.length()) {
      return false;
    }
    int difference = 0;
    for (int i = 0; i < left.length(); i++) {
      difference |= left.charAt(i) ^ right.charAt(i);
    }
    return difference == 0;
  }

  private static byte[] digestBytes(final byte[] input) {
    try {
      // A fresh instance per call: MessageDigest is stateful and not thread-safe, and this runtime
      // digests concurrently from every request thread.
      return MessageDigest.getInstance(ALGORITHM).digest(input);
    } catch (final NoSuchAlgorithmException impossible) {
      throw new IllegalStateException(
          ALGORITHM + " is required of every Java platform", impossible);
    }
  }
}

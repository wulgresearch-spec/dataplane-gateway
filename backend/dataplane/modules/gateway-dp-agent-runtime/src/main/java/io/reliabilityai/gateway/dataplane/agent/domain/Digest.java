package io.reliabilityai.gateway.dataplane.agent.domain;

import io.reliabilityai.gateway.common.Preconditions;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/**
 * Stable content digests for recorded values.
 *
 * <p>The history records digests rather than payloads wherever it can (AD-025 §34.3). Two reasons,
 * and the second is the important one: a control-plane structure that accumulated every model
 * output and tool payload a run touched would become a shadow copy of the tenant's data, with its
 * own retention obligations and its own cross-tenant concentration risk.
 *
 * <p>Digests are also what {@code REPLAY_DIVERGENCE} is detected with: comparing a recomputed
 * value's digest against the recorded one is cheap and does not require holding both payloads.
 */
public final class Digest {

  private static final String ALGORITHM = "SHA-256";
  private static final char[] HEX = "0123456789abcdef".toCharArray();

  /**
   * How many hex characters of the digest are kept. 128 bits is far past collision relevance here.
   */
  private static final int HEX_LENGTH = 32;

  private Digest() {
    throw new AssertionError("no instances");
  }

  /**
   * Digests a value.
   *
   * @param value the content to digest
   * @return a lowercase hex prefix of the SHA-256 digest, stable across nodes and JVM versions
   */
  public static String of(final String value) {
    Preconditions.requireNonNull(value, "value");
    final byte[] bytes = digestBytes(value.getBytes(StandardCharsets.UTF_8));
    final StringBuilder hex = new StringBuilder(HEX_LENGTH);
    for (int i = 0; i < HEX_LENGTH / 2; i++) {
      final int b = bytes[i] & 0xFF;
      hex.append(HEX[b >>> 4]).append(HEX[b & 0x0F]);
    }
    return hex.toString();
  }

  /**
   * Digests several values as one, unambiguously.
   *
   * <p>Each part is length-prefixed before hashing. Concatenating with a separator would make
   * {@code ["ab", "c"]} and {@code ["a", "bc"]} hash alike for any separator that can appear in the
   * data, and a plan's inputs are arbitrary strings.
   *
   * @param parts the values to combine
   * @return the combined digest
   */
  public static String ofAll(final java.util.List<String> parts) {
    Preconditions.requireNonNull(parts, "parts");
    final StringBuilder framed = new StringBuilder();
    for (final String part : parts) {
      final String safe = part == null ? "" : part;
      framed.append(safe.length()).append(':').append(safe);
    }
    return of(framed.toString());
  }

  private static byte[] digestBytes(final byte[] input) {
    try {
      // A fresh instance per call: MessageDigest is stateful and not thread-safe, and this runtime
      // digests concurrently from every executor thread in the fleet.
      return MessageDigest.getInstance(ALGORITHM).digest(input);
    } catch (final NoSuchAlgorithmException impossible) {
      throw new IllegalStateException(
          ALGORITHM + " is required by every Java platform", impossible);
    }
  }
}

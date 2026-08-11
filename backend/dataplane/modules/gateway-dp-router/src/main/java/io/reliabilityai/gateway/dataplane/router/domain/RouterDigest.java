package io.reliabilityai.gateway.dataplane.router.domain;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/**
 * Deterministic, RNG-free ordering keys for tie-breaking (Doc 19 §13.1). A stable SHA-256 hash of
 * {@code correlationId + candidateId} yields a per-request ordering of equally-scored candidates
 * that is <b>reproducible</b> (same request ⇒ same order, PR-D5) and <b>load-distributing</b>
 * (different correlation ids spread the primary across the tied set) — with no {@code Math.random}
 * and no shared mutable state (Doc 11 R-063, AD-021).
 */
public final class RouterDigest {

  private static final char[] HEX = "0123456789abcdef".toCharArray();
  private static final String SEP = "\0"; // real NUL delimiter (never appears in a validated id)

  private RouterDigest() {}

  /**
   * Returns a stable 32-hex-character ordering key for a (correlationId, candidateId) pair.
   *
   * @param correlationId the request's stable correlation id
   * @param candidateId the candidate route id
   * @return a stable lexicographically-orderable key
   */
  public static String orderKey(final String correlationId, final String candidateId) {
    final byte[] digest = sha256(correlationId + SEP + candidateId);
    final char[] out = new char[32];
    for (int i = 0; i < 16; i++) {
      out[i * 2] = HEX[(digest[i] >> 4) & 0xF];
      out[i * 2 + 1] = HEX[digest[i] & 0xF];
    }
    return new String(out);
  }

  private static byte[] sha256(final String input) {
    try {
      return MessageDigest.getInstance("SHA-256").digest(input.getBytes(StandardCharsets.UTF_8));
    } catch (final NoSuchAlgorithmException e) {
      throw new IllegalStateException("SHA-256 unavailable", e);
    }
  }
}

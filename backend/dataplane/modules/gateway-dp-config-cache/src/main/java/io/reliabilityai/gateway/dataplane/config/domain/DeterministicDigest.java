package io.reliabilityai.gateway.dataplane.config.domain;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/**
 * Deterministic, RNG-free, clock-free hashing used for feature-flag resolution and
 * resolved-flag-set identity (Doc 36 FFC-3). Uses SHA-256, whose output is a pure function of its
 * input, so identical inputs always yield identical results — the property replay relies on (Doc 32
 * §CRS). Contains no {@code Math.random}, {@code Random}, or wall-clock read (Doc 11 R-063).
 */
final class DeterministicDigest {

  private static final char[] HEX = "0123456789abcdef".toCharArray();
  // A NUL separator that cannot appear in config keys / tenant ids, so joined parts are unambiguous
  // (prevents "a"+"b c" colliding with "a b"+"c"); written as an explicit escape (CC-2).
  private static final String SEP = "\0"; // real NUL delimiter (never appears in a validated id)

  private DeterministicDigest() {}

  /**
   * Computes a deterministic bucket in {@code [0, 10000)} for the joined parts (Doc 36 FFC-3). Used
   * to compare against a rollout ratio in basis points.
   *
   * @param parts the ordered key parts (e.g. flagId, org, tenant)
   * @return a stable bucket in {@code [0, 10000)}
   */
  static int bucket(final String... parts) {
    final byte[] digest = sha256(String.join(SEP, parts));
    // First 8 bytes as a 64-bit value, then floorMod into [0, 10000) (floorMod handles sign).
    long value = 0L;
    for (int i = 0; i < 8; i++) {
      value = (value << 8) | (digest[i] & 0xFFL);
    }
    return (int) Math.floorMod(value, 10_000L);
  }

  /**
   * Computes a short, stable hex identity for a canonical string (Doc 36 FFC-5) — recorded
   * alongside the pinned version so replay can correlate the resolved flag set.
   *
   * @param canonical the canonical, order-stable string form of the resolved set
   * @return a 16-hex-character stable identity
   */
  static String hexId(final String canonical) {
    final byte[] digest = sha256(canonical);
    final char[] out = new char[16];
    for (int i = 0; i < 8; i++) {
      out[i * 2] = HEX[(digest[i] >> 4) & 0xF];
      out[i * 2 + 1] = HEX[digest[i] & 0xF];
    }
    return new String(out);
  }

  private static byte[] sha256(final String input) {
    try {
      final MessageDigest md = MessageDigest.getInstance("SHA-256");
      return md.digest(input.getBytes(StandardCharsets.UTF_8));
    } catch (final NoSuchAlgorithmException e) {
      // SHA-256 is a mandated JDK algorithm; its absence is an unrecoverable environment fault.
      throw new IllegalStateException("SHA-256 unavailable", e);
    }
  }
}

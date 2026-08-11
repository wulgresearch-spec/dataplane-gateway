package io.reliabilityai.gateway.dataplane.observability.domain;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/**
 * Deterministic, RNG-free, clock-free hashing for correlation-seeded sampling (Doc 27 §19.1 RSD-2).
 * Uses SHA-256, whose output is a pure function of its input, so a keep/drop decision is exactly
 * reproducible on replay (Doc 27 RSD-1/RSD-5) — no {@code Math.random}, no wall-clock (Doc 11
 * R-063).
 */
final class ObservabilityDigest {

  private ObservabilityDigest() {}

  /**
   * Computes a deterministic bucket in {@code [0, 10000)} for the given key (Doc 27 RSD-2).
   *
   * @param key the seed (e.g. a correlation id)
   * @return a stable bucket in {@code [0, 10000)}
   */
  static int bucket(final String key) {
    final byte[] digest = sha256(key);
    long value = 0L;
    for (int i = 0; i < 8; i++) {
      value = (value << 8) | (digest[i] & 0xFFL);
    }
    return (int) Math.floorMod(value, 10_000L);
  }

  private static byte[] sha256(final String input) {
    try {
      final MessageDigest md = MessageDigest.getInstance("SHA-256");
      return md.digest(input.getBytes(StandardCharsets.UTF_8));
    } catch (final NoSuchAlgorithmException e) {
      throw new IllegalStateException("SHA-256 unavailable", e);
    }
  }
}

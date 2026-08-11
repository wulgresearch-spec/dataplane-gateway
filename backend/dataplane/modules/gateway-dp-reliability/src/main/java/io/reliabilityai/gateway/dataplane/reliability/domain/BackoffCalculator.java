package io.reliabilityai.gateway.dataplane.reliability.domain;

import io.reliabilityai.gateway.common.Preconditions;
import io.reliabilityai.gateway.dataplane.reliability.api.ReliabilityPolicy;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;

/**
 * Deterministic exponential backoff with <b>full jitter</b> (Doc 20 §15, RE-D2/RE-D9). The
 * exponential base ({@code base * 2^(attempt-1)}, capped) is jittered into {@code [0, cap)} using a
 * fraction <b>deterministically seeded</b> from {@code correlationId + attempt} — so it is
 * <b>replayable</b> (same inputs ⇒ same delay) and <b>anti-herd</b> (different requests
 * desynchronize). Jitter is the only permitted randomness (RE-D9) and it is not random at all: it
 * is a pure SHA-256 function, with no {@code Math.random} and no wall-clock (Doc 11 R-063).
 */
public final class BackoffCalculator {

  private static final int MAX_SHIFT = 30;

  private BackoffCalculator() {}

  /**
   * Computes the backoff delay before the given attempt (Doc 20 §15).
   *
   * @param correlationId the stable request correlation id (jitter seed)
   * @param attemptNumber the 1-based attempt number (jitter seed)
   * @param policy the reliability policy (base/cap)
   * @return the deterministic backoff duration in {@code [0, cap)}
   */
  public static Duration backoff(
      final String correlationId, final int attemptNumber, final ReliabilityPolicy policy) {
    Preconditions.requireNonBlank(correlationId, "correlationId");
    Preconditions.requireNonNull(policy, "policy");
    if (attemptNumber < 1) {
      throw new IllegalArgumentException("attemptNumber must be >= 1");
    }
    final int shift = Math.min(attemptNumber - 1, MAX_SHIFT);
    final long cap = policy.backoffCapMillis();
    final long base = policy.backoffBaseMillis();
    // Guard the left shift against overflow: if base > cap>>shift then base<<shift would exceed cap
    // (and could overflow to a negative), so clamp to cap directly (never a negative exponential).
    final long exponential =
        shift >= MAX_SHIFT || base > (cap >> shift) ? cap : Math.min(cap, base << shift);
    final double fraction = seededFraction(correlationId + "|" + attemptNumber);
    final long jittered = (long) (fraction * exponential);
    return Duration.ofMillis(jittered);
  }

  private static double seededFraction(final String seed) {
    final byte[] digest = sha256(seed);
    long value = 0L;
    for (int i = 0; i < 8; i++) {
      value = (value << 8) | (digest[i] & 0xFFL);
    }
    // Map to [0.0, 1.0): mask off the sign bit and divide by 2^63.
    return (value & Long.MAX_VALUE) / (double) Long.MAX_VALUE;
  }

  private static byte[] sha256(final String input) {
    try {
      return MessageDigest.getInstance("SHA-256").digest(input.getBytes(StandardCharsets.UTF_8));
    } catch (final NoSuchAlgorithmException e) {
      throw new IllegalStateException("SHA-256 unavailable", e);
    }
  }
}

package io.reliabilityai.gateway.dataplane.memory.crypto;

import java.util.concurrent.atomic.AtomicLongArray;

/**
 * A degraded-random-source detector (AD-028 §5.4).
 *
 * <p><b>Read this before trusting it.</b> This is a canary, not a guarantee. It remembers a
 * bounded, hashed, process-local sample of recently issued nonces. It will catch a generator that
 * has stopped producing fresh output — a cloned VM, a stuck entropy pool, a test double wired in by
 * mistake — usually on the very next call. It will <em>not</em> detect a collision between two
 * nonces issued far apart, on two different processes, or before the last restart. Anyone who reads
 * a clean canary as proof that no nonce has ever repeated has misread it.
 *
 * <p>The reason a bounded sample is acceptable is that a real collision under a healthy 96-bit
 * random generator is not the threat being defended against here — at the wrap budget this cipher
 * enforces, that probability is negligible. The realistic threat is a generator that is not random
 * at all, and that failure mode is loud, immediate and repetitive, which is exactly what a small
 * recent-window check is good at seeing.
 *
 * <p>Fixed memory, no allocation on the hot path, no locks. Slots are single 64-bit fingerprints
 * written with plain atomic stores; a lost update under contention costs a missed observation,
 * never a false alarm cascade, because the caller's response to a hit is to draw again rather than
 * to fail.
 */
final class NonceCanary {

  /** Power of two so the index is a mask rather than a modulo. 64Ki slots is 512 KiB. */
  private static final int SLOTS = 1 << 16;

  private static final int MASK = SLOTS - 1;

  /** Reserved to mean "never written", so a genuine zero fingerprint is nudged off it. */
  private static final long EMPTY = 0L;

  private final AtomicLongArray slots = new AtomicLongArray(SLOTS);

  /**
   * Records a nonce and reports whether it was seen recently.
   *
   * @param nonce the nonce about to be used
   * @return true when this nonce matches a recently issued one, meaning it must not be used
   */
  boolean seenBefore(final byte[] nonce) {
    final long fingerprint = fingerprintOf(nonce);
    final int slot = (int) (fingerprint >>> 48) & MASK;
    final long occupant = slots.get(slot);
    slots.set(slot, fingerprint);
    return occupant == fingerprint;
  }

  /**
   * Folds a nonce into a 64-bit fingerprint.
   *
   * <p>Not a cryptographic hash and not required to be: nothing here defends a secret, and the
   * fingerprint never leaves the process. It only has to spread well enough that distinct nonces
   * rarely share a value. A 64-bit space makes a false alarm rare enough to be irrelevant, and the
   * caller treats one as a re-draw regardless.
   *
   * @param nonce the nonce bytes
   * @return a fingerprint that is never {@link #EMPTY}
   */
  private static long fingerprintOf(final byte[] nonce) {
    long hash = 0xcbf29ce484222325L;
    for (final byte b : nonce) {
      hash ^= b & 0xffL;
      hash *= 0x100000001b3L;
    }
    return hash == EMPTY ? 1L : hash;
  }
}

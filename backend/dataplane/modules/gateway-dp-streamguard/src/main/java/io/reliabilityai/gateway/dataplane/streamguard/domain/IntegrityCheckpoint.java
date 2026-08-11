package io.reliabilityai.gateway.dataplane.streamguard.domain;

/**
 * A monotonic sequence position + a deterministic rolling hash of the payload bytes (Doc 18
 * §6/§17/§33) used for duplicate/replay detection within the bounded suppression window. <b>Hash
 * only — never stored/exported content</b> (Doc 18 §40). Deterministic (Doc 18 §34): the same bytes
 * always hash the same value.
 *
 * @param seqPosition the synthesized monotonic sequence position
 * @param payloadHash the 64-bit deterministic hash of the payload bytes
 */
public record IntegrityCheckpoint(long seqPosition, long payloadHash) {

  private static final long FNV_OFFSET = 0xcbf29ce484222325L;
  private static final long FNV_PRIME = 0x100000001b3L;

  /**
   * Computes the deterministic FNV-1a 64-bit hash of the payload bytes (Doc 18 §17 rolling hash).
   *
   * @param payload the payload bytes
   * @return the 64-bit hash
   */
  public static long hash(final byte[] payload) {
    long h = FNV_OFFSET;
    for (final byte b : payload) {
      h ^= (b & 0xFFL);
      h *= FNV_PRIME;
    }
    return h;
  }
}

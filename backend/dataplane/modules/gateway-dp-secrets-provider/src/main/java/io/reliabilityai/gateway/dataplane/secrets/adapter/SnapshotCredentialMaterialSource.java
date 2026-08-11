package io.reliabilityai.gateway.dataplane.secrets.adapter;

import io.reliabilityai.gateway.canonical.snapshot.CredentialSnapshotRef;
import io.reliabilityai.gateway.common.Preconditions;
import io.reliabilityai.gateway.dataplane.secrets.api.CredentialMaterialSource;
import java.util.Arrays;

/**
 * The in-memory master material of one entry of the cached C14 credential snapshot (Doc 26 §12(a),
 * the DP Secret-cache node). Holds the material in a mutable {@code char[]} (never a {@code
 * String}, MSC-1), <b>cloned on write</b> at construction and <b>cloned on read</b> into the
 * caller's buffer via {@link #copyInto(char[])} — the internal array is never shared. On
 * rotation/invalidation the master is proactively {@link #zeroize() wiped} (MSC-6); a copy
 * attempted after eviction fails closed (the materialization then surfaces {@code
 * materialization-error}, never a partial secret).
 *
 * <p><b>Honest guarantee (MSC-5/MSC-9):</b> the {@code char[]} is overwritten deterministically on
 * {@code zeroize()}; on a relocating collector transient heap copies may persist until collection.
 * This is runtime memory hygiene, not cryptographic destruction (C14/KMS-owned).
 */
public final class SnapshotCredentialMaterialSource implements CredentialMaterialSource {

  private final CredentialSnapshotRef ref;
  private final char[] material;
  private boolean evicted;

  /**
   * Creates a material source, defensively cloning the given material (clone on write).
   *
   * @param ref the content-free snapshot reference (version, tenant binding, expiry)
   * @param material the master credential material (cloned; the caller's array is not retained)
   */
  public SnapshotCredentialMaterialSource(final CredentialSnapshotRef ref, final char[] material) {
    this.ref = Preconditions.requireNonNull(ref, "ref");
    Preconditions.requireNonNull(material, "material");
    this.material = material.clone();
  }

  @Override
  public CredentialSnapshotRef ref() {
    return ref;
  }

  @Override
  public synchronized int length() {
    return material.length;
  }

  @Override
  public synchronized void copyInto(final char[] destination) {
    Preconditions.requireNonNull(destination, "destination");
    if (evicted) {
      // Rotation/invalidation raced this materialization: fail closed rather than copy wiped bytes.
      throw new IllegalStateException("credential snapshot entry evicted");
    }
    if (destination.length < material.length) {
      throw new IllegalArgumentException("destination buffer too small");
    }
    System.arraycopy(material, 0, destination, 0, material.length);
  }

  /**
   * Proactively wipes the master material and marks the entry evicted (Doc 26 MSC-6). Idempotent.
   * Called on rotation/invalidation of the snapshot; subsequent {@link #copyInto} fails closed.
   */
  public synchronized void zeroize() {
    Arrays.fill(material, (char) 0);
    evicted = true;
  }

  /**
   * Whether this entry has been evicted/wiped.
   *
   * @return {@code true} once zeroized
   */
  public synchronized boolean isEvicted() {
    return evicted;
  }
}

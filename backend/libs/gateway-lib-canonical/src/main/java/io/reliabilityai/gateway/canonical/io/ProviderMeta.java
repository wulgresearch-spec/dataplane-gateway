package io.reliabilityai.gateway.canonical.io;

import java.util.Arrays;

/**
 * Opaque, adapter-internal provider provenance carried on a {@link CanonicalResponse} (Doc 33
 * §10.2, Doc 25 §7.1). It is a sealed byte blob: no downstream module may type, parse, branch on,
 * or otherwise consume its contents (PA-A19). It exists only so an adapter can round-trip provider
 * provenance; it is never persisted and never influences a decision.
 *
 * <p>Modeled as an immutable final class (not a record) on purpose: a {@code byte[]} record
 * component would give reference-based {@code equals}/{@code hashCode} and would expose the
 * internal array via the generated accessor. This type instead clones on construction and on read,
 * and implements value semantics over the bytes.
 */
public final class ProviderMeta {

  private static final ProviderMeta EMPTY = new ProviderMeta(new byte[0]);

  private final byte[] bytes;

  private ProviderMeta(final byte[] copied) {
    this.bytes = copied;
  }

  /**
   * Creates opaque provider metadata from the given bytes, defensively cloned.
   *
   * @param bytes the opaque provenance bytes (must not be null; use {@link #empty()} for none)
   * @return an immutable opaque holder
   */
  public static ProviderMeta of(final byte[] bytes) {
    if (bytes == null) {
      throw new NullPointerException("bytes must not be null (use ProviderMeta.empty())");
    }
    return bytes.length == 0 ? EMPTY : new ProviderMeta(bytes.clone());
  }

  /**
   * Returns the canonical empty provider metadata.
   *
   * @return an empty opaque holder
   */
  public static ProviderMeta empty() {
    return EMPTY;
  }

  /**
   * Returns whether this holder carries no provenance bytes.
   *
   * @return {@code true} when empty
   */
  public boolean isEmpty() {
    return bytes.length == 0;
  }

  /**
   * Returns a defensive copy of the opaque bytes. Callers MUST NOT interpret the contents (PA-A19);
   * this accessor exists only for adapter round-tripping.
   *
   * @return a clone of the opaque bytes
   */
  public byte[] bytes() {
    return bytes.clone();
  }

  @Override
  public boolean equals(final Object other) {
    if (this == other) {
      return true;
    }
    return other instanceof ProviderMeta meta && Arrays.equals(bytes, meta.bytes);
  }

  @Override
  public int hashCode() {
    return Arrays.hashCode(bytes);
  }

  @Override
  public String toString() {
    // Never render opaque provider bytes; only their length is safe to surface.
    return "ProviderMeta[" + bytes.length + " bytes]";
  }
}

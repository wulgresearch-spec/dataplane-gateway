package io.reliabilityai.gateway.dataplane.plugin.api;

import io.reliabilityai.gateway.common.Preconditions;

/**
 * A C12-vetted, signed, digest-pinned plugin artifact (Doc 28 §6, AD-022).
 *
 * <p>This is the runtime's read-only input. The control plane authors it; the runtime verifies it
 * and either loads it or refuses (Doc 28 §ROC). The runtime has no constructor path that produces a
 * snapshot from anything other than material it was handed.
 *
 * <p>The byte arrays are defensively copied on the way in and on the way out. A shared array would
 * let whoever handed the snapshot over mutate the digest after verification passed — which is
 * precisely the substitution Doc 28 STC-3 exists to prevent.
 *
 * @param manifest the declared manifest
 * @param artifact the plugin bytes, or an empty array for a plugin whose code is already resident
 * @param digest the content hash the signature covers
 * @param signature the control plane's signature over the digest
 * @param provenance the content-free provenance statement (who vetted and built it)
 */
public record VettedPluginSnapshot(
    PluginManifest manifest, byte[] artifact, byte[] digest, byte[] signature, String provenance) {

  /** Compact constructor validating presence and copying every mutable field. */
  public VettedPluginSnapshot {
    Preconditions.requireNonNull(manifest, "manifest");
    Preconditions.requireNonNull(artifact, "artifact");
    Preconditions.requireNonNull(digest, "digest");
    Preconditions.requireNonNull(signature, "signature");
    Preconditions.requireNonBlank(provenance, "provenance");
    if (digest.length == 0) {
      throw new IllegalArgumentException("digest must not be empty");
    }
    if (signature.length == 0) {
      throw new IllegalArgumentException("signature must not be empty");
    }
    artifact = artifact.clone();
    digest = digest.clone();
    signature = signature.clone();
  }

  @Override
  public byte[] artifact() {
    return artifact.clone();
  }

  @Override
  public byte[] digest() {
    return digest.clone();
  }

  @Override
  public byte[] signature() {
    return signature.clone();
  }

  /**
   * The digest rendered as lowercase hex, for audit records and the descriptor.
   *
   * @return the hex digest
   */
  public String digestHex() {
    final StringBuilder hex = new StringBuilder(digest.length * 2);
    for (final byte value : digest) {
      hex.append(Character.forDigit((value >> 4) & 0xF, 16));
      hex.append(Character.forDigit(value & 0xF, 16));
    }
    return hex.toString();
  }
}

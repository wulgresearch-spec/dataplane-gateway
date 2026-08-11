package io.reliabilityai.gateway.dataplane.secrets.api;

import io.reliabilityai.gateway.canonical.snapshot.CredentialSnapshotRef;

/**
 * A read-only handle to one resolved credential's transient material, yielded by {@link
 * SecretSnapshotPort} from the cached C14 snapshot (Doc 26 §7, AD-022). The credential value is
 * never returned as an object the Secrets Provider retains; instead it is copied, on demand, into a
 * caller-owned mutable buffer that becomes the sanitizable lease (Doc 26 §17.1 MSC-1) and is
 * zeroized on release. The real off-heap/KMS-backed source lives behind this seam (Doc 26 MSC-9,
 * Doc 38 §IR-7) — the data-plane module makes no KMS call and performs no cryptographic
 * destruction.
 */
public interface CredentialMaterialSource {

  /**
   * The content-free reference/metadata for this credential snapshot (Doc 26 §6).
   *
   * @return the snapshot reference (version, tenant binding, expiry) — never the value
   */
  CredentialSnapshotRef ref();

  /**
   * The length, in characters, of the credential material (Doc 26 §17.1).
   *
   * @return the material length (non-negative)
   */
  int length();

  /**
   * Copies the credential material into the caller-owned destination buffer (Doc 26 §17.1 MSC-1).
   * The Secrets Provider owns and zeroizes the destination; the material is never exposed as an
   * immutable object (Doc 26 MSC-1, no {@code String}).
   *
   * @param destination the caller-owned buffer, of length at least {@link #length()}
   */
  void copyInto(char[] destination);
}

package io.reliabilityai.gateway.dataplane.memory.crypto;

import java.util.Optional;
import java.util.Set;
import javax.crypto.SecretKey;

/**
 * Custody of the master keys that wrap per-record data keys (AD-028).
 *
 * <p>This is a port, not an implementation, for the same reason {@code MemoryStorePort} is: the
 * place keys actually live differs per deployment — a file on a VPS today, C14 Secrets or a KMS
 * later — and the cipher has no business knowing which. Every implementation loads key material
 * from outside the program. There is no constructor anywhere in this module that accepts a literal
 * key, because a hardcoded key is a key that lives in version control forever.
 *
 * <p>Implementations must treat the returned {@link SecretKey} as the only copy the caller gets.
 * They must never log it, never place it in an exception message, never expose it through a metric
 * and never return it through {@link #toString()}.
 */
public interface MasterKeyProvider {

  /**
   * The version new writes should be sealed under.
   *
   * <p>Rotation is exactly the act of changing what this returns. Older versions must remain
   * readable through {@link #keyFor(String)} until every record sealed under them has been
   * re-wrapped.
   *
   * @return the primary key version identifier
   * @throws KeyUnavailableException when no primary key can be loaded
   */
  String primaryVersion();

  /**
   * Every version this provider can still open.
   *
   * @return the readable key version identifiers, never empty in a healthy provider
   */
  Set<String> versions();

  /**
   * Resolves one version to key material.
   *
   * <p>Returns empty rather than throwing for an unknown version: a record sealed under a key that
   * has been retired is an expected condition that must degrade to "undecryptable", not to an
   * outage.
   *
   * @param version the key version identifier
   * @return the key, or empty when this provider cannot open that version
   */
  Optional<SecretKey> keyFor(String version);
}

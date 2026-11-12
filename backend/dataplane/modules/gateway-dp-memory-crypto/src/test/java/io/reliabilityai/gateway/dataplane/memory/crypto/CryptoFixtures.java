package io.reliabilityai.gateway.dataplane.memory.crypto;

import io.reliabilityai.gateway.canonical.identity.PrincipalId;
import io.reliabilityai.gateway.canonical.identity.TenantScope;
import io.reliabilityai.gateway.dataplane.memory.api.MemoryScope;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;

/** Shared material for the cryptography tests. */
final class CryptoFixtures {

  static final TenantScope ACME = TenantScope.of("acme", "core");
  static final TenantScope GLOBEX = TenantScope.of("globex", "core");
  static final PrincipalId ALICE = new PrincipalId("alice");
  static final PrincipalId BOB = new PrincipalId("bob");

  static final MemoryScope ACME_TENANT = MemoryScope.ofTenant(ACME);
  static final MemoryScope GLOBEX_TENANT = MemoryScope.ofTenant(GLOBEX);
  static final MemoryScope ACME_ALICE = MemoryScope.ofUser(ACME, ALICE);
  static final MemoryScope ACME_BOB = MemoryScope.ofUser(ACME, BOB);
  static final MemoryScope ACME_ALICE_NOTES = MemoryScope.ofPartition(ACME, ALICE, "notes");
  static final MemoryScope ACME_ALICE_DRAFTS = MemoryScope.ofPartition(ACME, ALICE, "drafts");

  private CryptoFixtures() {}

  /**
   * Writes test keyrings with owner-only permissions wherever POSIX permissions are supported.
   *
   * <p>Create with restrictive mode before writing any key material. Rewriting an existing keyring
   * during rotation preserves the original file mode. Non-POSIX platforms use their native mode.
   *
   * @param keyring where the test keyring lives
   * @param content the keyring contents
   * @return the keyring path
   * @throws IOException if the keyring cannot be created or written
   */
  static Path writePrivateKeyring(final Path keyring, final String content) throws IOException {
    if (Files.notExists(keyring)) {
      try {
        Files.createFile(
            keyring,
            PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")));
      } catch (final UnsupportedOperationException notPosix) {
        Files.createFile(keyring);
      }
    }
    Files.writeString(keyring, content, StandardCharsets.UTF_8);
    return keyring;
  }

  /**
   * A key provider held in memory, so tests need no filesystem.
   *
   * <p>Keys are generated, never literal, so that no test in this suite can be copied into
   * production code and still work.
   */
  static final class TestKeys implements MasterKeyProvider {

    private final Map<String, SecretKey> keys = new LinkedHashMap<>();
    private String primary;

    TestKeys(final String... versions) {
      final SecureRandom seedSource = new SecureRandom();
      for (final String version : versions) {
        final byte[] material = new byte[32];
        seedSource.nextBytes(material);
        keys.put(version, new SecretKeySpec(material, "AES"));
        Arrays.fill(material, (byte) 0);
      }
      primary = versions[0];
    }

    /**
     * Adds a freshly generated key version.
     *
     * @param version the label to add
     */
    void add(final String version) {
      final byte[] material = new byte[32];
      new SecureRandom().nextBytes(material);
      keys.put(version, new SecretKeySpec(material, "AES"));
      Arrays.fill(material, (byte) 0);
    }

    /**
     * Removes a version, modelling a key that has been retired or destroyed.
     *
     * @param version the label to drop
     */
    void retire(final String version) {
      keys.remove(version);
    }

    /**
     * Changes what new writes are sealed under, without going through the cipher.
     *
     * @param version the new primary
     */
    void setPrimary(final String version) {
      primary = version;
    }

    @Override
    public String primaryVersion() {
      return primary;
    }

    @Override
    public Set<String> versions() {
      return Set.copyOf(keys.keySet());
    }

    @Override
    public Optional<SecretKey> keyFor(final String version) {
      return Optional.ofNullable(keys.get(version));
    }
  }

  /**
   * A generator that has stopped generating.
   *
   * <p>Models the realistic catastrophic failure — a cloned VM image, a stubbed provider, an
   * entropy source that returns a constant — rather than a subtle statistical weakness, which no
   * unit test can detect anyway.
   *
   * @param fill the byte every request is answered with
   * @return a random source that always returns the same bytes
   */
  static SecureRandom stuckRandom(final byte fill) {
    return new SecureRandom() {
      private static final long serialVersionUID = 1L;

      @Override
      public void nextBytes(final byte[] bytes) {
        Arrays.fill(bytes, fill);
      }
    };
  }

  /**
   * Builds a cipher over freshly generated keys.
   *
   * @param keys the provider
   * @param metrics the sink
   * @return the cipher
   */
  static AeadMemoryCrypto crypto(final TestKeys keys, final InProcessCryptoMetrics metrics) {
    return new AeadMemoryCrypto(keys, metrics);
  }

  /**
   * Decodes a sealed body to its raw envelope bytes so tests can corrupt individual fields.
   *
   * @param sealed the Base64URL envelope
   * @return the raw bytes
   */
  static byte[] rawOf(final String sealed) {
    return Base64.getUrlDecoder().decode(sealed);
  }

  /**
   * Re-encodes raw envelope bytes after a test has modified them.
   *
   * @param raw the modified bytes
   * @return the Base64URL form
   */
  static String encodeRaw(final byte[] raw) {
    return Base64.getUrlEncoder().withoutPadding().encodeToString(raw);
  }

  /**
   * Flips the lowest bit of one byte.
   *
   * @param sealed the sealed body
   * @param index which byte of the decoded envelope to flip
   * @return the corrupted sealed body
   */
  static String flipBit(final String sealed, final int index) {
    final byte[] raw = rawOf(sealed);
    raw[index] ^= 0x01;
    return encodeRaw(raw);
  }
}

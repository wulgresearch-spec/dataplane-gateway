package io.reliabilityai.gateway.dataplane.memory.internal;

import io.reliabilityai.gateway.common.Preconditions;
import io.reliabilityai.gateway.dataplane.memory.api.MemoryCryptoPort;
import io.reliabilityai.gateway.dataplane.memory.api.MemoryScope;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Optional;

/**
 * A stand-in for the sealing port that performs <b>no cryptography whatsoever</b>.
 *
 * <p>It base64-encodes. That is not encryption, it is not obfuscation, and anyone who can read the
 * ciphertext can read the plaintext with one shell command. It exists so the write and read
 * pipelines can be exercised end to end against a port whose real implementation belongs to C14
 * Secrets and does not exist yet (blocker B24).
 *
 * <p><b>The name is the safety mechanism.</b> A class called {@code DefaultMemoryCrypto} or {@code
 * SimpleAesSealer} would eventually be wired into a production composition root by someone who read
 * the interface and assumed the implementation was adequate. This one cannot be deployed by
 * accident, only on purpose, and a code review that sees {@code NotRealCryptoSealer} in a
 * production configuration has been told exactly what is wrong.
 *
 * <p>{@link #PRODUCTION_SAFE} is false, so a composition root can refuse to start with it.
 */
public final class NotRealCryptoSealer implements MemoryCryptoPort {

  /**
   * Whether this implementation may be used in production.
   *
   * <p>Always false. A startup validator should read this and refuse to boot rather than trust an
   * operator to notice the class name in a configuration file.
   */
  public static final boolean PRODUCTION_SAFE = false;

  /**
   * The key reference this stand-in reports. Deliberately self-describing in any log it appears in.
   */
  private static final String KEY_REF = "not-a-real-key";

  @Override
  public Sealed seal(final MemoryScope scope, final String plaintext) {
    Preconditions.requireNonNull(scope, "scope");
    Preconditions.requireNonNull(plaintext, "plaintext");
    return new Sealed(
        Base64.getEncoder().encodeToString(plaintext.getBytes(StandardCharsets.UTF_8)), KEY_REF);
  }

  @Override
  public Optional<String> unseal(
      final MemoryScope scope, final String ciphertext, final String keyRef) {
    Preconditions.requireNonNull(scope, "scope");
    Preconditions.requireNonNull(ciphertext, "ciphertext");
    Preconditions.requireNonNull(keyRef, "keyRef");

    if (!KEY_REF.equals(keyRef)) {
      // A key reference this stand-in did not mint. Returning empty rather than guessing is the
      // same
      // behaviour a real adapter must have for a key it cannot resolve (AD-026 §11).
      return Optional.empty();
    }
    try {
      return Optional.of(
          new String(Base64.getDecoder().decode(ciphertext), StandardCharsets.UTF_8));
    } catch (final IllegalArgumentException malformed) {
      return Optional.empty();
    }
  }
}

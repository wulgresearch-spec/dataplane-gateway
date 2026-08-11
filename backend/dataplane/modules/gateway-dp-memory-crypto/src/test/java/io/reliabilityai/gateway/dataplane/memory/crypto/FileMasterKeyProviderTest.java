package io.reliabilityai.gateway.dataplane.memory.crypto;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Loading key material from outside the program, and refusing to load it badly. */
@DisplayName("keyring loading")
final class FileMasterKeyProviderTest {

  @Test
  @DisplayName("a well-formed keyring loads every version")
  void aWellFormedKeyringLoadsEveryVersion(@TempDir final Path directory) throws Exception {
    final Path keyring =
        write(directory, "primary = 2026-07\n2026-01 = " + key() + "\n2026-07 = " + key() + "\n");

    final FileMasterKeyProvider provider = new FileMasterKeyProvider(keyring);

    assertThat(provider.primaryVersion()).isEqualTo("2026-07");
    assertThat(provider.versions()).isEqualTo(Set.of("2026-01", "2026-07"));
    assertThat(provider.keyFor("2026-01")).isPresent();
    assertThat(provider.keyFor("2026-07")).isPresent();
  }

  @Test
  @DisplayName("comments and blank lines are ignored")
  void commentsAndBlankLinesAreIgnored(@TempDir final Path directory) throws Exception {
    final Path keyring =
        write(
            directory,
            "# rotated 2026-07-01\n\nprimary = v1\n\n  # trailing note\nv1 = " + key() + "\n\n");

    assertThat(new FileMasterKeyProvider(keyring).versions()).isEqualTo(Set.of("v1"));
  }

  @Test
  @DisplayName("an unknown version resolves to empty rather than throwing")
  void anUnknownVersionResolvesToEmptyRatherThanThrowing(@TempDir final Path directory)
      throws Exception {
    final Path keyring = write(directory, "primary = v1\nv1 = " + key() + "\n");

    // A retired key is an ordinary condition on the read path; it must degrade to "undecryptable"
    // and not to an exception that fails a whole search.
    assertThat(new FileMasterKeyProvider(keyring).keyFor("v0")).isEmpty();
  }

  @Test
  @DisplayName("a missing keyring is refused")
  void aMissingKeyringIsRefused(@TempDir final Path directory) {
    assertThatThrownBy(() -> new FileMasterKeyProvider(directory.resolve("absent")))
        .isInstanceOf(KeyUnavailableException.class);
  }

  @Test
  @DisplayName("a keyring with no primary directive is refused")
  void aKeyringWithNoPrimaryDirectiveIsRefused(@TempDir final Path directory) throws Exception {
    final Path keyring = write(directory, "v1 = " + key() + "\n");

    assertThatThrownBy(() -> new FileMasterKeyProvider(keyring))
        .isInstanceOf(KeyUnavailableException.class)
        .hasMessageContaining("no primary");
  }

  @Test
  @DisplayName("a primary naming a version with no key is refused")
  void aPrimaryNamingAVersionWithNoKeyIsRefused(@TempDir final Path directory) throws Exception {
    final Path keyring = write(directory, "primary = v2\nv1 = " + key() + "\n");

    // Loading this would produce a provider that throws on the first write instead of at startup,
    // which moves a configuration error from deploy time to the worst possible moment.
    assertThatThrownBy(() -> new FileMasterKeyProvider(keyring))
        .isInstanceOf(KeyUnavailableException.class)
        .hasMessageContaining("has no key");
  }

  @Test
  @DisplayName("a key of the wrong length is refused, and the message quotes no material")
  void aKeyOfTheWrongLengthIsRefusedAndTheMessageQuotesNoMaterial(@TempDir final Path directory)
      throws Exception {
    final byte[] tooShort = new byte[16];
    new SecureRandom().nextBytes(tooShort);
    final String encoded = Base64.getEncoder().encodeToString(tooShort);
    final Path keyring = write(directory, "primary = v1\nv1 = " + encoded + "\n");

    assertThatThrownBy(() -> new FileMasterKeyProvider(keyring))
        .isInstanceOf(KeyUnavailableException.class)
        .hasMessageContaining("AES-256 needs 32")
        .satisfies(thrown -> assertThat(thrown.getMessage()).doesNotContain(encoded));
  }

  @Test
  @DisplayName("a value that is not base64 is refused, and the message quotes no material")
  void aValueThatIsNotBase64IsRefusedAndTheMessageQuotesNoMaterial(@TempDir final Path directory)
      throws Exception {
    final Path keyring = write(directory, "primary = v1\nv1 = not base64 at all\n");

    assertThatThrownBy(() -> new FileMasterKeyProvider(keyring))
        .isInstanceOf(KeyUnavailableException.class)
        .hasMessageContaining("line 2")
        .satisfies(thrown -> assertThat(thrown.getMessage()).doesNotContain("not base64 at all"));
  }

  @Test
  @DisplayName("a line that is not name = value is refused with its line number")
  void aLineThatIsNotNameEqualsValueIsRefusedWithItsLineNumber(@TempDir final Path directory)
      throws Exception {
    final Path keyring = write(directory, "primary = v1\nv1 = " + key() + "\ngarbage\n");

    assertThatThrownBy(() -> new FileMasterKeyProvider(keyring))
        .isInstanceOf(KeyUnavailableException.class)
        .hasMessageContaining("line 3");
  }

  @Test
  @DisplayName("an empty keyring is refused")
  void anEmptyKeyringIsRefused(@TempDir final Path directory) throws Exception {
    assertThatThrownBy(() -> new FileMasterKeyProvider(write(directory, "")))
        .isInstanceOf(KeyUnavailableException.class);
  }

  @Test
  @DisplayName("keys are read once, so editing the file afterwards changes nothing")
  void keysAreReadOnceSoEditingTheFileAfterwardsChangesNothing(@TempDir final Path directory)
      throws Exception {
    final Path keyring = write(directory, "primary = v1\nv1 = " + key() + "\n");
    final FileMasterKeyProvider provider = new FileMasterKeyProvider(keyring);
    Files.writeString(keyring, "primary = v9\nv9 = " + key() + "\n");

    // Re-reading per operation would let anyone who can write that file change which key seals the
    // next record, at a moment of their choosing rather than an operator's.
    assertThat(provider.primaryVersion()).isEqualTo("v1");
    assertThat(provider.keyFor("v9")).isEmpty();
  }

  /**
   * Writes a keyring file.
   *
   * @param directory where
   * @param content the file body
   * @return the path
   * @throws Exception when it cannot be written
   */
  private static Path write(final Path directory, final String content) throws Exception {
    final Path keyring = directory.resolve("keys");
    Files.writeString(keyring, content);
    return keyring;
  }

  /**
   * Generates a base64 AES-256 key.
   *
   * @return the encoded key
   */
  private static String key() {
    final byte[] material = new byte[32];
    new SecureRandom().nextBytes(material);
    return Base64.getEncoder().encodeToString(material);
  }
}

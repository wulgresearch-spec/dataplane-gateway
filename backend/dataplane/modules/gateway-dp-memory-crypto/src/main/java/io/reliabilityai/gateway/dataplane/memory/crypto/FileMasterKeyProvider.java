package io.reliabilityai.gateway.dataplane.memory.crypto;

import io.reliabilityai.gateway.common.Preconditions;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.util.Arrays;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;

/**
 * Loads master keys from a keyring file outside the program (AD-028 §6).
 *
 * <p>This is the provider the single-VPS deployment target actually uses. It exists because the
 * alternative people reach for — a key in a constant, a key in a config class, a key in an
 * environment variable committed to a compose file — is the failure this module was written to
 * prevent. A file can be given restrictive permissions, kept off the image, mounted from a secret
 * store and rotated without a rebuild.
 *
 * <p>File format, one directive per line, {@code #} for comments:
 *
 * <pre>
 *   primary = 2026-07
 *   2026-07 = &lt;base64 of 32 random bytes&gt;
 *   2026-04 = &lt;base64 of 32 random bytes&gt;
 * </pre>
 *
 * <p>Everything is read once at construction and held in memory. Re-reading per operation would
 * make every seal a file read, and would make the cipher's behaviour depend on the filesystem at a
 * moment chosen by an attacker rather than by an operator.
 *
 * <p><b>Permission checking is best-effort and platform-dependent.</b> On a POSIX filesystem a
 * keyring readable by group or other is refused outright. On Windows, where POSIX permissions are
 * not exposed, that check cannot run and is skipped — the file may be world-readable and this class
 * will load it anyway. That is a real hole on that platform and is recorded as B38 rather than
 * papered over.
 */
public final class FileMasterKeyProvider implements MasterKeyProvider {

  /** AES-256. A shorter key here would silently weaken every record in the system. */
  private static final int REQUIRED_KEY_BYTES = 32;

  private static final String PRIMARY_DIRECTIVE = "primary";

  private static final Set<PosixFilePermission> FORBIDDEN =
      Set.of(
          PosixFilePermission.GROUP_READ,
          PosixFilePermission.GROUP_WRITE,
          PosixFilePermission.OTHERS_READ,
          PosixFilePermission.OTHERS_WRITE);

  private final Map<String, SecretKey> byVersion;

  private final String primaryVersion;

  /**
   * Loads a keyring.
   *
   * @param keyring the path to the keyring file
   * @throws KeyUnavailableException when the file is missing, unreadable, malformed, insecurely
   *     permissioned, or does not define a usable primary key
   */
  public FileMasterKeyProvider(final Path keyring) {
    Preconditions.requireNonNull(keyring, "keyring");
    requireNotWorldReadable(keyring);
    final List<String> lines;
    try {
      lines = Files.readAllLines(keyring, StandardCharsets.UTF_8);
    } catch (final IOException unreadable) {
      throw new KeyUnavailableException("keyring " + keyring + " could not be read", unreadable);
    }

    final Map<String, SecretKey> loaded = new LinkedHashMap<>();
    String primary = null;
    int lineNumber = 0;
    for (final String line : lines) {
      lineNumber++;
      final String trimmed = line.trim();
      if (trimmed.isEmpty() || trimmed.charAt(0) == '#') {
        continue;
      }
      final int split = trimmed.indexOf('=');
      if (split <= 0) {
        throw new KeyUnavailableException("keyring line " + lineNumber + " is not name = value");
      }
      final String name = trimmed.substring(0, split).trim();
      final String value = trimmed.substring(split + 1).trim();
      if (PRIMARY_DIRECTIVE.equals(name)) {
        primary = value;
        continue;
      }
      // The exception below names the line number and never the value, because the value is a key
      // and this message is destined for a log file.
      loaded.put(name, decodeKey(value, lineNumber));
    }

    if (primary == null) {
      throw new KeyUnavailableException("keyring declares no primary key version");
    }
    if (!loaded.containsKey(primary)) {
      throw new KeyUnavailableException("keyring primary version " + primary + " has no key");
    }
    this.byVersion = Map.copyOf(loaded);
    this.primaryVersion = primary;
  }

  @Override
  public String primaryVersion() {
    return primaryVersion;
  }

  @Override
  public Set<String> versions() {
    return byVersion.keySet();
  }

  @Override
  public Optional<SecretKey> keyFor(final String version) {
    Preconditions.requireNonNull(version, "version");
    return Optional.ofNullable(byVersion.get(version));
  }

  /**
   * Renders the provider without rendering its contents.
   *
   * <p>Overridden rather than inherited because the default would be harmless today and would stop
   * being harmless the moment someone adds a field. Version labels are safe to print; the map
   * values are not, and are never reachable from here.
   *
   * @return a description carrying only version labels
   */
  @Override
  public String toString() {
    return "FileMasterKeyProvider[primary="
        + primaryVersion
        + ", versions="
        + byVersion.size()
        + "]";
  }

  /**
   * Decodes one key, insisting on the right length.
   *
   * @param value the base64 key material
   * @param lineNumber where it came from, for a message that does not quote it
   * @return the key
   * @throws KeyUnavailableException when the value is not base64 or is not 32 bytes
   */
  private static SecretKey decodeKey(final String value, final int lineNumber) {
    final byte[] raw;
    try {
      raw = Base64.getDecoder().decode(value);
    } catch (final IllegalArgumentException notBase64) {
      throw new KeyUnavailableException("keyring line " + lineNumber + " is not valid base64");
    }
    if (raw.length != REQUIRED_KEY_BYTES) {
      // Length is disclosed because a wrong length is the whole diagnosis and reveals nothing about
      // the material itself.
      final int actual = raw.length;
      Arrays.fill(raw, (byte) 0);
      throw new KeyUnavailableException(
          "keyring line "
              + lineNumber
              + " holds "
              + actual
              + " bytes; AES-256 needs "
              + REQUIRED_KEY_BYTES);
    }
    final SecretKey key = new SecretKeySpec(raw, "AES");
    // SecretKeySpec copies, so the local buffer is redundant the moment the key exists.
    Arrays.fill(raw, (byte) 0);
    return key;
  }

  /**
   * Refuses a keyring that anyone but its owner can read, where the platform can tell.
   *
   * @param keyring the path to check
   * @throws KeyUnavailableException when the file is readable or writable beyond its owner
   */
  private static void requireNotWorldReadable(final Path keyring) {
    final Set<PosixFilePermission> permissions;
    try {
      permissions = Files.getPosixFilePermissions(keyring);
    } catch (final UnsupportedOperationException notPosix) {
      // Windows and some network filesystems. Nothing to check; see B38.
      return;
    } catch (final IOException unreadable) {
      throw new KeyUnavailableException("keyring " + keyring + " could not be stat'd", unreadable);
    }
    for (final PosixFilePermission permission : permissions) {
      if (FORBIDDEN.contains(permission)) {
        throw new KeyUnavailableException(
            "keyring " + keyring + " is accessible beyond its owner; expected 0600");
      }
    }
  }
}

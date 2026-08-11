package io.reliabilityai.gateway.dataplane.plugin.api;

import io.reliabilityai.gateway.common.Preconditions;

/**
 * An immutable, totally-ordered plugin version (Doc 28 §11).
 *
 * <p>Versions are always explicit — there is no {@code latest} and no floating range, because a
 * floating version would mean the bytes the runtime verified are not necessarily the bytes it runs
 * (Doc 28 PRT-D12/§HRC). Ordering is major, then minor, then patch.
 *
 * @param major the major version (incompatible changes)
 * @param minor the minor version (additive changes)
 * @param patch the patch version (fixes)
 */
public record PluginVersion(int major, int minor, int patch) implements Comparable<PluginVersion> {

  /** Compact constructor rejecting negative components. */
  public PluginVersion {
    if (major < 0 || minor < 0 || patch < 0) {
      throw new IllegalArgumentException("version components must be non-negative");
    }
  }

  /**
   * Parses a {@code major.minor.patch} version.
   *
   * @param text the version text
   * @return the parsed version
   * @throws IllegalArgumentException if the text is not exactly three numeric components
   */
  public static PluginVersion parse(final String text) {
    Preconditions.requireNonBlank(text, "version");
    final String[] parts = text.split("\\.", -1);
    if (parts.length != 3) {
      throw new IllegalArgumentException("version must be major.minor.patch");
    }
    try {
      return new PluginVersion(
          Integer.parseInt(parts[0]), Integer.parseInt(parts[1]), Integer.parseInt(parts[2]));
    } catch (final NumberFormatException notNumeric) {
      throw new IllegalArgumentException("version components must be numeric");
    }
  }

  /**
   * Whether this version satisfies a dependency on the required version under semantic-version
   * rules: the same major, and at least the required minor/patch.
   *
   * <p>A major-version difference is never compatible in either direction. Treating a newer major
   * as satisfying an older requirement is how a dependent plugin ends up calling a method that was
   * removed.
   *
   * @param required the version a dependent plugin declared it needs
   * @return true if this version can satisfy that requirement
   */
  public boolean satisfies(final PluginVersion required) {
    Preconditions.requireNonNull(required, "required");
    return major == required.major && compareTo(required) >= 0;
  }

  @Override
  public int compareTo(final PluginVersion other) {
    if (major != other.major) {
      return Integer.compare(major, other.major);
    }
    if (minor != other.minor) {
      return Integer.compare(minor, other.minor);
    }
    return Integer.compare(patch, other.patch);
  }

  @Override
  public String toString() {
    return major + "." + minor + "." + patch;
  }
}

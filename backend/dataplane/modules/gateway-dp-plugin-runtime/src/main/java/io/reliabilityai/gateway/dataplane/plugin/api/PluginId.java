package io.reliabilityai.gateway.dataplane.plugin.api;

import io.reliabilityai.gateway.common.Preconditions;

/**
 * The stable identity of a plugin, independent of its version (Doc 28 §6).
 *
 * <p>The id is authored by the C12 control plane and is opaque to the runtime, which never mints
 * one (Doc 28 ROC-8). It appears in audit records, metric labels and telemetry, so it is
 * constrained to a bounded character set: an unconstrained id would let a plugin author inject
 * separators into a metric label and blow up label cardinality (Doc 14 §5).
 *
 * @param value the vetted plugin id
 */
public record PluginId(String value) implements Comparable<PluginId> {

  /** The longest id the runtime will accept, keeping metric labels bounded. */
  public static final int MAX_LENGTH = 128;

  /** Compact constructor validating the id shape. */
  public PluginId {
    Preconditions.requireNonBlank(value, "pluginId");
    if (value.length() > MAX_LENGTH) {
      throw new IllegalArgumentException("pluginId exceeds " + MAX_LENGTH + " characters");
    }
    for (int index = 0; index < value.length(); index++) {
      final char character = value.charAt(index);
      final boolean permitted =
          (character >= 'a' && character <= 'z')
              || (character >= 'A' && character <= 'Z')
              || (character >= '0' && character <= '9')
              || character == '.'
              || character == '-'
              || character == '_';
      if (!permitted) {
        throw new IllegalArgumentException("pluginId contains an unsupported character");
      }
    }
  }

  /**
   * Creates a plugin id.
   *
   * @param value the vetted plugin id
   * @return the id
   */
  public static PluginId of(final String value) {
    return new PluginId(value);
  }

  @Override
  public int compareTo(final PluginId other) {
    return value.compareTo(other.value);
  }

  @Override
  public String toString() {
    return value;
  }
}

package io.reliabilityai.gateway.dataplane.config.domain;

/**
 * Validates that a pinned config snapshot's schema version lies within the range this code build
 * supports (Doc 36 §SCA, Doc 29 §SCC). An out-of-range snapshot must fail closed at pin (Doc 36
 * CFG-A3/§SCA) — the runtime never consumes config it cannot interpret.
 *
 * <p>The supported {@code [min, max]} range is a <b>code-level contract</b> of this build (what
 * schema versions the code understands), not an operational baseline (Doc 36 §OBB): it is a
 * compile-time property of the code, so it is a constructor input, not a hard-coded runtime
 * numeric.
 */
public final class SchemaCompatibility {

  private final int minSupported;
  private final int maxSupported;

  /**
   * Creates a compatibility validator for the supported schema range.
   *
   * @param minSupported the minimum schema version this build understands (inclusive)
   * @param maxSupported the maximum schema version this build understands (inclusive)
   */
  public SchemaCompatibility(final int minSupported, final int maxSupported) {
    if (minSupported < 1 || maxSupported < minSupported) {
      throw new IllegalArgumentException(
          "invalid supported schema range [" + minSupported + ", " + maxSupported + "]");
    }
    this.minSupported = minSupported;
    this.maxSupported = maxSupported;
  }

  /**
   * Returns whether the snapshot schema version is within the supported range (Doc 29 §SCC).
   *
   * @param snapshotSchemaVersion the pinned snapshot's schema version
   * @return {@code true} iff the schema version is supported by this build
   */
  public boolean isCompatible(final int snapshotSchemaVersion) {
    return snapshotSchemaVersion >= minSupported && snapshotSchemaVersion <= maxSupported;
  }
}

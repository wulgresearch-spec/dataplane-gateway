package io.reliabilityai.gateway.canonical.identity;

import io.reliabilityai.gateway.common.Preconditions;

/**
 * Code version — the immutable deployable version (AD-020, Doc 29 §SCC). Combined with pinned
 * snapshot versions it defines the recorded identity for replay (Doc 29 §CVR).
 *
 * @param value the non-blank code version
 */
public record CodeVersion(String value) {

  /** Compact constructor validating the version. */
  public CodeVersion {
    Preconditions.requireNonBlank(value, "codeVersion");
  }

  @Override
  public String toString() {
    return value;
  }
}

package io.reliabilityai.gateway.dataplane.provider.api;

import io.reliabilityai.gateway.common.Preconditions;

/**
 * A provider API version <em>pinned from the Provider Registry snapshot</em> (Doc 25 PA-D10,
 * AD-022). The adapter uses the pinned version and stamps it on telemetry for replay (Doc 25 §25);
 * it never floats to a provider's implicit "latest". Immutable.
 *
 * @param value the non-blank pinned provider API version
 */
public record PinnedVersion(String value) {

  /** Compact constructor validating the version. */
  public PinnedVersion {
    Preconditions.requireNonBlank(value, "value");
  }
}

package io.reliabilityai.gateway.canonical.io;

import io.reliabilityai.gateway.common.Preconditions;
import java.util.Optional;

/**
 * A provider-neutral, normalized error (Doc 25 §6/§16.1). The adapter normalizes the error shape
 * and may expose an advisory {@code retryableHint} / {@code transientError}; the retry
 * <em>decision</em> is Reliability's (Doc 20, Doc 25 §17.1). Provider-native codes are opaque (Doc
 * 25 §37).
 *
 * @param category the canonical error category (Doc 25 §16.1)
 * @param retryableHint an advisory retryability hint (nullable; never a decision)
 * @param providerCodeOpaque the opaque, internal-only provider code
 * @param transientError advisory transient flag (Reliability decides)
 */
public record CanonicalError(
    ErrorCategory category,
    Boolean retryableHint,
    String providerCodeOpaque,
    boolean transientError) {

  /** Compact constructor validating the category and opaque code. */
  public CanonicalError {
    Preconditions.requireNonNull(category, "category");
    Preconditions.requireNonNull(providerCodeOpaque, "providerCodeOpaque");
  }

  /**
   * Returns the advisory retryability hint, if any.
   *
   * @return the optional hint
   */
  public Optional<Boolean> retryableHintOpt() {
    return Optional.ofNullable(retryableHint);
  }
}

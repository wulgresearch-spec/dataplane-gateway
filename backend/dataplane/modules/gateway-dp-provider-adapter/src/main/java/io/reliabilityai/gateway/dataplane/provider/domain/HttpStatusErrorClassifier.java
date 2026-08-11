package io.reliabilityai.gateway.dataplane.provider.domain;

import io.reliabilityai.gateway.canonical.io.CanonicalError;
import io.reliabilityai.gateway.canonical.io.ErrorCategory;
import io.reliabilityai.gateway.common.Preconditions;

/**
 * Maps an HTTP status code to a canonical error <em>category</em> (Doc 25 §16.1) — a reusable, pure
 * default for HTTP-speaking provider translators (HTTP is a transport protocol, not a provider, so
 * this stays provider-neutral, AD-007). Directly realizes the frozen §16.1 mapping table:
 * throttling → {@code rate_limited}, 5xx → {@code provider_unavailable}, 401/403 → {@code
 * auth_failed}, 408/504 → {@code timeout}, other 4xx → {@code provider_rejected}. The {@code
 * transient} hint is <b>advisory</b> (Doc 25 §17.1 TO-3 — Reliability decides retry/failover).
 */
public final class HttpStatusErrorClassifier {

  private HttpStatusErrorClassifier() {}

  /**
   * Classifies an error HTTP status into a canonical category (Doc 25 §16.1).
   *
   * @param status the HTTP status code (must be an error status, {@code >= 400})
   * @return the canonical error category
   * @throws IllegalArgumentException if {@code status} is not an error status ({@code < 400})
   */
  public static ErrorCategory classify(final int status) {
    if (status < 400) {
      throw new IllegalArgumentException("status is not an error status: " + status);
    }
    if (status == 408 || status == 504) {
      return ErrorCategory.TIMEOUT;
    }
    if (status == 429) {
      return ErrorCategory.RATE_LIMITED;
    }
    if (status == 401 || status == 403) {
      return ErrorCategory.AUTH_FAILED;
    }
    if (status >= 500) {
      return ErrorCategory.PROVIDER_UNAVAILABLE;
    }
    return ErrorCategory.PROVIDER_REJECTED; // remaining 4xx
  }

  /**
   * Whether the given error status is advisorily transient (Doc 25 §16.1; hint only, §17.1 TO-3).
   *
   * @param status the HTTP status code ({@code >= 400})
   * @return {@code true} for timeout / rate-limited / provider-unavailable statuses
   */
  public static boolean isTransient(final int status) {
    return switch (classify(status)) {
      case TIMEOUT, RATE_LIMITED, PROVIDER_UNAVAILABLE -> true;
      default -> false;
    };
  }

  /**
   * Builds a canonical error from an HTTP error status (Doc 25 §16.1/§37).
   *
   * @param status the HTTP status code ({@code >= 400})
   * @param providerCodeOpaque the opaque, internal-only provider code
   * @return a canonical error with an advisory transient hint
   */
  public static CanonicalError toCanonicalError(final int status, final String providerCodeOpaque) {
    Preconditions.requireNonNull(providerCodeOpaque, "providerCodeOpaque");
    final boolean transient_ = isTransient(status);
    return new CanonicalError(classify(status), transient_, providerCodeOpaque, transient_);
  }
}

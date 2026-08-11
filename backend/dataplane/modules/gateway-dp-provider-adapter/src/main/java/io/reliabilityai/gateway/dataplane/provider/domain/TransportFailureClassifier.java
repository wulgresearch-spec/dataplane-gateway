package io.reliabilityai.gateway.dataplane.provider.domain;

import io.reliabilityai.gateway.canonical.io.CanonicalError;
import io.reliabilityai.gateway.canonical.io.ErrorCategory;
import io.reliabilityai.gateway.common.Preconditions;

/**
 * Maps a neutral {@link TransportFailureKind} to a {@link CanonicalError} <em>shape</em> (Doc 25
 * §16.1, PA-D7). A reusable default a provider translator's {@code classifyTransportFailure} may
 * delegate to. Pure and total — every kind maps; the {@code transient}/{@code retryableHint} it
 * stamps is <b>advisory only</b> and never a policy decision (Doc 25 §17.1 TO-3 — Reliability
 * decides). No provider detail is ever carried; the provider code is passed opaquely (Doc 25 §37).
 */
public final class TransportFailureClassifier {

  private TransportFailureClassifier() {}

  /**
   * Classifies a transport-layer failure into a canonical error shape (Doc 25 §16.1).
   *
   * @param kind the neutral transport failure kind
   * @param providerCodeOpaque the opaque, internal-only provider/transport code
   * @return a canonical error with an advisory transient hint (Reliability decides retry)
   */
  public static CanonicalError classify(
      final TransportFailureKind kind, final String providerCodeOpaque) {
    Preconditions.requireNonNull(kind, "kind");
    Preconditions.requireNonNull(providerCodeOpaque, "providerCodeOpaque");
    final ErrorCategory category =
        switch (kind) {
          case TIMEOUT, CANCELLED -> ErrorCategory.TIMEOUT;
          case CONNECT, TLS, IO -> ErrorCategory.TRANSPORT;
        };
    // Transport failures are transient by nature — advisory only (Doc 25 §17.1 TO-3).
    return new CanonicalError(category, Boolean.TRUE, providerCodeOpaque, true);
  }
}

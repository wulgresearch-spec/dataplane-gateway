package io.reliabilityai.gateway.dataplane.provider.api;

import io.reliabilityai.gateway.common.Preconditions;
import io.reliabilityai.gateway.dataplane.provider.domain.TransportFailureKind;

/**
 * A transport-layer failure raised by {@link ProviderTransportPort} <em>before any provider
 * verdict</em> (Doc 25 §16.1/§26). It carries a neutral {@link TransportFailureKind} and an opaque,
 * internal-only code — <b>never</b> provider-native error detail (Doc 25 §37). The coordinator maps
 * it to a canonical error via the translator (which may reuse {@code TransportFailureClassifier});
 * the retry <em>decision</em> is Reliability's (Doc 25 §17.1 TO-3). Checked so the transport seam
 * must declare it.
 */
public final class TransportException extends Exception {

  private static final long serialVersionUID = 1L;

  private final TransportFailureKind kind;
  private final String codeOpaque;

  /**
   * Creates a transport exception.
   *
   * @param kind the neutral transport failure kind
   * @param codeOpaque the opaque, internal-only transport/provider code (no provider-native detail)
   */
  public TransportException(final TransportFailureKind kind, final String codeOpaque) {
    super(Preconditions.requireNonNull(kind, "kind").name());
    this.kind = kind;
    this.codeOpaque = Preconditions.requireNonNull(codeOpaque, "codeOpaque");
  }

  /**
   * Creates a transport exception with an underlying cause.
   *
   * @param kind the neutral transport failure kind
   * @param codeOpaque the opaque, internal-only transport/provider code
   * @param cause the underlying transport cause (never surfaced across the boundary)
   */
  public TransportException(
      final TransportFailureKind kind, final String codeOpaque, final Throwable cause) {
    super(Preconditions.requireNonNull(kind, "kind").name(), cause);
    this.kind = kind;
    this.codeOpaque = Preconditions.requireNonNull(codeOpaque, "codeOpaque");
  }

  /**
   * The neutral transport failure kind.
   *
   * @return the failure kind
   */
  public TransportFailureKind kind() {
    return kind;
  }

  /**
   * The opaque, internal-only transport/provider code.
   *
   * @return the opaque code
   */
  public String codeOpaque() {
    return codeOpaque;
  }
}

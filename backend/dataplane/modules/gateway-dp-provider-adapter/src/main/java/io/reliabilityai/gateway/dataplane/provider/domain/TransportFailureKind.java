package io.reliabilityai.gateway.dataplane.provider.domain;

/**
 * A provider-neutral classification of a <em>transport-layer</em> failure (before any provider
 * verdict) (Doc 25 §16.1). This is transport reality — connection, TLS, timeout, I/O, cancellation
 * — never a provider-semantic error (those arrive as a response with a status). Pure value; carries
 * no provider detail. Consumed by {@link TransportFailureClassifier} to produce a canonical error
 * shape; the retry <em>decision</em> is always Reliability's (Doc 20, Doc 25 §17.1 TO-3).
 */
public enum TransportFailureKind {
  /** TCP/DNS connect failure before any bytes exchanged. */
  CONNECT,
  /** TLS/mTLS handshake or certificate failure. */
  TLS,
  /** Per-attempt transport budget / deadline exhausted (Doc 25 §17.1 TO-4). */
  TIMEOUT,
  /** Read/write I/O failure mid-exchange. */
  IO,
  /** The attempt was cancelled (cancellation propagation, Doc 20 §12). */
  CANCELLED
}

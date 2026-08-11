package io.reliabilityai.gateway.dataplane.streamguard.api;

/**
 * The ingestion seam (Doc 18 §7/§10/§47, RB-T1). The Provider Router / adapter (C1) owns provider
 * <b>connection, auth, TLS, and the wire handshake</b> and yields a <b>raw byte/chunk stream</b>
 * tagged with a neutral {@link FramingType}; StreamGuard owns transport <b>integrity/parse</b> over
 * it and imports <b>no provider SDK</b> (Doc 18 §10, AU-06). Pull-based, enabling consumer-paced
 * backpressure (Doc 18 §31). Cooperative cancellation closes the provider connection (Doc 18 §27).
 *
 * <p>The concrete implementation (wrapping the C1 provider connection) is an impure adapter built
 * elsewhere; the StreamGuard core is exercised deterministically against a recorded/in-memory
 * source.
 */
public interface TransportSourcePort {

  /**
   * The neutral framing of this source's byte stream (Doc 18 §11) — never a provider identity.
   *
   * @return the framing type
   */
  FramingType framing();

  /**
   * Pulls the next raw transport chunk in arrival order (Doc 18 §9/§31), blocking until available.
   *
   * @return the next source chunk (data or an end-of-stream signal)
   * @throws InterruptedException if the pull is interrupted (cancellation, Doc 18 §27)
   */
  SourceChunk read() throws InterruptedException;

  /**
   * Cooperatively cancels the in-flight provider stream and closes the connection (Doc 18 §27).
   * Stops provider token/cost accrual; idempotent.
   */
  void cancel();

  /**
   * A raw transport chunk pulled from the provider (Doc 18 §9). Sealed: opaque data bytes, or an
   * end-of-stream signal. Whether an explicit in-band terminal preceded {@code Closed} determines a
   * proven completion vs {@code TRUNCATED} (Doc 18 §21).
   */
  sealed interface SourceChunk permits SourceChunk.Data, SourceChunk.Closed {

    /**
     * Opaque provider bytes in arrival order.
     *
     * @param bytes the raw bytes
     */
    record Data(byte[] bytes) implements SourceChunk {
      /** Compact constructor validating and defensively cloning the bytes. */
      public Data {
        if (bytes == null) {
          throw new NullPointerException("bytes must not be null");
        }
        bytes = bytes.clone();
      }

      @Override
      public byte[] bytes() {
        return bytes.clone();
      }
    }

    /** The transport ended (graceful close or disconnect); completeness is judged by §21. */
    record Closed() implements SourceChunk {}
  }
}

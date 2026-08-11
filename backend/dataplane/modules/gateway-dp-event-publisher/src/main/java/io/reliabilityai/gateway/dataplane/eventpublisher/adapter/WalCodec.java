package io.reliabilityai.gateway.dataplane.eventpublisher.adapter;

import io.reliabilityai.gateway.common.ContentFree;

/**
 * The payload byte-codec for {@link LocalWal} — the adapter-internal seam that turns an opaque,
 * content-free event payload into replayable bytes and back. It is <b>not</b> a data-plane port and
 * no caller of {@code WalPort} sees it; it exists only because a durable, replayable log must
 * persist bytes, whereas {@link ContentFree} is a marker with no wire form of its own.
 *
 * <p>The canonical wire encoding of an event is Avro (Doc 07 §9) and is ultimately the broker
 * adapter's responsibility; on a single VPS the same codec instance is shared with the WAL so a
 * replayed record is byte-identical to the originally-appended one. The codec MUST be deterministic
 * and content-free (it encodes ids/counts/categories/versions only, never prompt/PII, Doc 14 §7.1)
 * and MUST round-trip: {@code decode(encode(p))} reproduces an equal payload. A codec that cannot
 * encode a given payload fails closed by throwing — the WAL never silently drops a zero-loss
 * record.
 */
public interface WalCodec {

  /**
   * Encodes a content-free payload to deterministic, replayable bytes.
   *
   * @param payload the content-free event payload
   * @return the encoded bytes
   */
  byte[] encode(ContentFree payload);

  /**
   * Decodes bytes produced by {@link #encode} back into an equal payload (for crash replay).
   *
   * @param topic the record's topic (a codec may key its schema on the topic, Doc 06 §23)
   * @param bytes the encoded payload bytes
   * @return the reconstructed content-free payload
   */
  ContentFree decode(String topic, byte[] bytes);
}

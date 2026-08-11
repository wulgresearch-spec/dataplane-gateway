package io.reliabilityai.gateway.ports;

import io.reliabilityai.gateway.canonical.stream.StreamChunk;
import java.util.Optional;

/**
 * Turns one <em>already-guarded</em> transport payload into a canonical stream chunk (Doc 25
 * §23.1).
 *
 * <p>The split matters. StreamGuard deframes and proves the transport was intact; this decoder then
 * interprets what a single verified payload <em>means</em> in the provider's own dialect. Keeping
 * the two apart is what lets the pipeline stay provider-neutral while StreamGuard's integrity proof
 * stays genuine — the pipeline never parses a provider format, and the provider never gets to vouch
 * for its own transport.
 *
 * <p>Supplied by the provider adapter alongside its transport stream, because only the adapter
 * knows the dialect. Returns empty for payloads that carry nothing canonical, such as keep-alives
 * or role-only openers, so the pipeline never has to invent a chunk for them.
 */
@FunctionalInterface
public interface ProviderStreamDecoder {

  /**
   * Decodes one guarded payload.
   *
   * @param guardedPayload the payload StreamGuard extracted and verified
   * @return the canonical chunk, or empty when the payload carries nothing canonical
   */
  Optional<StreamChunk> decode(byte[] guardedPayload);
}

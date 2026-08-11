package io.reliabilityai.gateway.dataplane.memory.api;

/**
 * Turns text into an opaque vector (AD-026 §12).
 *
 * <p>The whole of the runtime's relationship with embedding technology. There is no model name
 * here, no dimension, no provider and no tokenizer — MEM-2 forbids an embedding model inside this
 * module, and MEM-1 forbids naming a provider anywhere in it.
 *
 * <p>The runtime treats the returned array as opaque: it does not inspect it, normalise it, cache
 * it by content or compare two of them. Comparison is the index's job, and an embedding compared by
 * the runtime would be vector arithmetic in a module that is forbidden to contain any.
 */
@FunctionalInterface
public interface EmbeddingPort {

  /**
   * Embeds text.
   *
   * @param text the text to embed
   * @return the opaque vector
   * @throws MemoryStoreUnavailableException when the provider cannot be reached, so the caller can
   *     distinguish "unavailable" from "embedded to nothing" — a distinction that decides whether a
   *     semantic read is refused or answered wrongly
   */
  float[] embed(String text);
}

package io.reliabilityai.gateway.dataplane.memory.application;

import io.reliabilityai.gateway.common.Preconditions;
import io.reliabilityai.gateway.dataplane.memory.api.EmbeddingPort;
import java.util.Optional;

/**
 * A thin guard over {@link EmbeddingPort}.
 *
 * <p>Exists for one reason: the read path must be able to tell "the embedding provider is down"
 * from "this text embeds to nothing", because the two lead to different answers. A down provider
 * degrades a hybrid query to keyword and refuses a semantic one (AD-026 §11); an empty embedding
 * would silently return no semantic matches and look like a correct empty result.
 *
 * <p>The runtime still never inspects a vector (MEM-3). This class checks whether it got one, not
 * what is in it.
 */
public final class MemoryEmbedder {

  private final EmbeddingPort port;

  /**
   * Creates the guard.
   *
   * @param port the embedding provider seam
   */
  public MemoryEmbedder(final EmbeddingPort port) {
    this.port = Preconditions.requireNonNull(port, "port");
  }

  /**
   * Embeds text, propagating failure to the caller.
   *
   * <p>Used on the write path, where a semantic memory that cannot be embedded is a memory that
   * will never be found semantically — so the caller needs to know rather than discover it much
   * later.
   *
   * @param text the text to embed
   * @return the opaque vector
   */
  public float[] embed(final String text) {
    Preconditions.requireNonNull(text, "text");
    return port.embed(text);
  }

  /**
   * Embeds text, reporting unavailability as an absent result.
   *
   * <p>Used on the read path, where the pipeline decides between degrading and refusing and needs
   * the failure as a value rather than as an exception unwinding the stack.
   *
   * @param text the text to embed
   * @return the vector, or empty when the provider could not be reached or returned nothing usable
   */
  public Optional<float[]> tryEmbed(final String text) {
    Preconditions.requireNonNull(text, "text");
    try {
      final float[] vector = port.embed(text);
      // A null or zero-length vector is not an embedding. Treated as unavailable rather than passed
      // to
      // an index that would either reject it or, worse, match everything.
      return vector == null || vector.length == 0 ? Optional.empty() : Optional.of(vector);
    } catch (final RuntimeException unavailable) {
      return Optional.empty();
    }
  }
}

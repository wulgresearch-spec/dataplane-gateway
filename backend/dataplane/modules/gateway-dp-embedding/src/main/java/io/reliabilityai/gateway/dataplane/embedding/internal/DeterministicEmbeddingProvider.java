package io.reliabilityai.gateway.dataplane.embedding.internal;

import io.reliabilityai.gateway.common.Preconditions;
import io.reliabilityai.gateway.dataplane.embedding.api.CanonicalEmbedding;
import io.reliabilityai.gateway.dataplane.embedding.api.EmbeddingCapability;
import io.reliabilityai.gateway.dataplane.embedding.api.EmbeddingHealth;
import io.reliabilityai.gateway.dataplane.embedding.api.EmbeddingModel;
import io.reliabilityai.gateway.dataplane.embedding.api.EmbeddingProviderPort;
import io.reliabilityai.gateway.dataplane.embedding.api.EmbeddingRequest;
import io.reliabilityai.gateway.dataplane.embedding.api.EmbeddingResponse;
import io.reliabilityai.gateway.dataplane.embedding.api.EmbeddingUsage;
import io.reliabilityai.gateway.dataplane.provider.api.ProviderId;
import io.reliabilityai.gateway.ports.ClockPort;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * A provider that computes vectors locally from a hash. <b>These are not embeddings.</b>
 *
 * <p>The name says what it is, for the same reason {@code NotRealCryptoSealer} did in AD-026: a
 * class called {@code DefaultEmbeddingProvider} gets deployed by someone in a hurry. Vectors from
 * here are deterministic and well-distributed, and carry <em>no semantic information
 * whatsoever</em>. Two paraphrases of the same sentence are as far apart as two unrelated ones.
 *
 * <p>It exists for two honest purposes. It lets the whole pipeline — batching, caching, retry,
 * cost, governance, normalization — be exercised end to end without a network, which matters
 * because no provider is reachable from this environment. And it gives a deployment something to
 * run against while credentials are being arranged, with a name nobody can mistake for production.
 *
 * <p>Semantic retrieval over these vectors will return arbitrary results. That is not a bug here;
 * it is the reason this class is not a substitute for a provider.
 */
public final class DeterministicEmbeddingProvider implements EmbeddingProviderPort {

  private static final ProviderId ID = ProviderId.of("deterministic-local");

  private final EmbeddingCapability capability;

  private final ClockPort clock;

  /**
   * Creates the provider.
   *
   * @param clock the time source
   * @param dimension the vector width to produce
   */
  public DeterministicEmbeddingProvider(final ClockPort clock, final int dimension) {
    this.clock = Preconditions.requireNonNull(clock, "clock");
    this.capability =
        new EmbeddingCapability(
            Set.of(new EmbeddingModel("text-default-1536", dimension, 8191, 0L)),
            256,
            1_000_000,
            true,
            false);
  }

  @Override
  public ProviderId id() {
    return ID;
  }

  @Override
  public EmbeddingCapability capability() {
    return capability;
  }

  @Override
  public EmbeddingHealth health() {
    return EmbeddingHealth.healthy(clock.now());
  }

  @Override
  public EmbeddingResponse embed(final EmbeddingRequest request) {
    final EmbeddingModel model = capability.model(request.modelId()).orElseThrow();
    final List<EmbeddingResponse.Outcome> outcomes = new ArrayList<>(request.size());
    for (final String text : request.texts()) {
      outcomes.add(
          new EmbeddingResponse.Outcome.Embedded(
              new CanonicalEmbedding(
                  ID,
                  request.modelId(),
                  model.dimension(),
                  clock.now(),
                  EmbeddingUsage.FREE,
                  true,
                  Map.of(),
                  vectorFor(text, model.dimension()))));
    }
    return new EmbeddingResponse(outcomes, new EmbeddingUsage(0L, 1, 0L));
  }

  /**
   * Expands a digest of the text into a unit vector.
   *
   * <p>SHA-256 rehashed with a counter, so the same text always produces the same vector and two
   * different texts produce uncorrelated ones. Deterministic across restarts and across machines,
   * which is what makes cache and idempotency tests meaningful.
   *
   * @param text the input
   * @param dimension the width to produce
   * @return a unit-length vector
   */
  private static float[] vectorFor(final String text, final int dimension) {
    final MessageDigest digest;
    try {
      digest = MessageDigest.getInstance("SHA-256");
    } catch (final NoSuchAlgorithmException impossible) {
      throw new IllegalStateException("SHA-256 is unavailable on this JVM", impossible);
    }
    final float[] vector = new float[dimension];
    final byte[] seed = text.getBytes(StandardCharsets.UTF_8);
    int filled = 0;
    int counter = 0;
    while (filled < dimension) {
      digest.reset();
      digest.update(seed);
      digest.update(
          new byte[] {
            (byte) (counter >>> 24), (byte) (counter >>> 16), (byte) (counter >>> 8), (byte) counter
          });
      final byte[] block = digest.digest();
      for (int i = 0; i + 1 < block.length && filled < dimension; i += 2) {
        final int paired = ((block[i] & 0xff) << 8) | (block[i + 1] & 0xff);
        vector[filled++] = (paired - 32768) / 32768.0f;
      }
      counter++;
    }
    double sumOfSquares = 0.0;
    for (final float component : vector) {
      sumOfSquares += (double) component * component;
    }
    final double norm = Math.sqrt(sumOfSquares);
    for (int i = 0; i < dimension; i++) {
      vector[i] = (float) (vector[i] / norm);
    }
    return vector;
  }
}

package io.reliabilityai.gateway.dataplane.observability.domain;

import io.reliabilityai.gateway.canonical.identity.CorrelationId;
import io.reliabilityai.gateway.common.Preconditions;

/**
 * Deterministic trace sampler (Doc 27 §19.1 RSD-2/RSD-5). A span is kept iff a
 * correlation-id-seeded bucket is strictly less than the configured rate in basis points — a
 * <b>pure function</b> of the correlation id and the Doc-14-owned rate, with no {@code Math.random}
 * and no wall-clock. This makes the keep/drop decision exactly reproducible on replay (Doc 27
 * RSD-1/RSD-6) while timing is not reproduced (RSD-3). The rate is an injected operational baseline
 * (Doc 27 OT-A14, Doc 16 §I.1).
 */
public final class DeterministicSampler {

  /** Full rate (100% keep). */
  public static final int ALWAYS = 10_000;

  private final int sampleRateBasisPoints;

  /**
   * Creates a sampler at the given rate.
   *
   * @param sampleRateBasisPoints the keep rate in basis points, {@code 0..10000} (operational
   *     baseline)
   */
  public DeterministicSampler(final int sampleRateBasisPoints) {
    if (sampleRateBasisPoints < 0 || sampleRateBasisPoints > ALWAYS) {
      throw new IllegalArgumentException(
          "sampleRateBasisPoints must be within [0, " + ALWAYS + "]: " + sampleRateBasisPoints);
    }
    this.sampleRateBasisPoints = sampleRateBasisPoints;
  }

  /**
   * Returns whether a span with the given correlation id should be kept (Doc 27 RSD-2).
   *
   * @param correlationId the correlation id seed
   * @return {@code true} to keep the span, deterministically
   */
  public boolean shouldSample(final CorrelationId correlationId) {
    Preconditions.requireNonNull(correlationId, "correlationId");
    if (sampleRateBasisPoints <= 0) {
      return false;
    }
    if (sampleRateBasisPoints >= ALWAYS) {
      return true;
    }
    return ObservabilityDigest.bucket(correlationId.value()) < sampleRateBasisPoints;
  }
}

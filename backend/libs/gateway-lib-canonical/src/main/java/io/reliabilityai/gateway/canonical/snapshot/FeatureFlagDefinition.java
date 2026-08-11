package io.reliabilityai.gateway.canonical.snapshot;

import io.reliabilityai.gateway.common.Preconditions;

/**
 * A single feature-flag definition carried in a {@link ConfigSnapshot} (Doc 36 §FFC, §5). Authored
 * and validated by C15 (Doc 36 §CAB); the runtime consumes it read-only and resolves it once, at
 * pin time, deterministically (Doc 36 FFC-2/FFC-3) — never live, never with RNG or wall-clock.
 *
 * <p>The rollout is expressed in integer <b>basis points</b> {@code [0, 10000]} (not a float) so
 * the deterministic resolution is exactly reproducible for replay (Doc 32 §CRS): a flag resolves
 * {@code true} for a tenant iff a deterministic bucket of {@code (flagId, tenantScope)} is strictly
 * less than {@code rolloutBasisPoints}. {@code 0} ⇒ off for all; {@code 10000} ⇒ on for all.
 *
 * @param flagId the flag identifier (C15-owned; never authored by the consumer)
 * @param rolloutBasisPoints the deterministic rollout ratio in basis points, {@code 0..10000}
 */
public record FeatureFlagDefinition(String flagId, int rolloutBasisPoints) {

  /** The maximum rollout value (100%). */
  public static final int FULLY_ON = 10_000;

  /** Compact constructor validating the flag id and rollout range. */
  public FeatureFlagDefinition {
    Preconditions.requireNonBlank(flagId, "flagId");
    if (rolloutBasisPoints < 0 || rolloutBasisPoints > FULLY_ON) {
      throw new IllegalArgumentException(
          "rolloutBasisPoints must be within [0, " + FULLY_ON + "]: " + rolloutBasisPoints);
    }
  }
}

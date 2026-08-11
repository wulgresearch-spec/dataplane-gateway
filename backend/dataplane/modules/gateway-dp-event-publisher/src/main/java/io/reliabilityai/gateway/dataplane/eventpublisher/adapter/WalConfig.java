package io.reliabilityai.gateway.dataplane.eventpublisher.adapter;

import io.reliabilityai.gateway.common.Preconditions;

/**
 * Bounded, deterministic configuration for {@link LocalWal} (Doc 07 EV-D3). Values come from the
 * operational baseline at the composition root; none is derived from wall-clock time or randomness
 * (R-063).
 *
 * @param maxSegmentBytes the size at which the active segment rolls to a new one (rotation); {@code
 *     >= 64}
 * @param syncPolicy when buffered writes are forced to stable storage
 */
public record WalConfig(long maxSegmentBytes, WalSyncPolicy syncPolicy) {

  /** Validates the bounds. */
  public WalConfig {
    if (maxSegmentBytes < 64) {
      throw new IllegalArgumentException("maxSegmentBytes must be >= 64");
    }
    Preconditions.requireNonNull(syncPolicy, "syncPolicy");
  }

  /** A durable default: 64 MiB segments, {@code fsync} on every write. */
  public static WalConfig durableDefault() {
    return new WalConfig(64L * 1024 * 1024, WalSyncPolicy.ALWAYS);
  }
}

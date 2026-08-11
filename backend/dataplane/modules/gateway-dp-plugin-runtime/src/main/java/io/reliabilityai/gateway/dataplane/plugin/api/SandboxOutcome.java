package io.reliabilityai.gateway.dataplane.plugin.api;

import io.reliabilityai.gateway.common.Preconditions;
import java.time.Duration;

/**
 * What the substrate observed while running one unit of work (Doc 28 §REC).
 *
 * <p>Sealed, so a caller cannot forget the failure cases. Every variant carries {@link
 * ResourceUsage}, including the failures — a plugin that burned its whole budget and then timed out
 * consumed real resources, and dropping that measurement is how a misbehaving plugin becomes
 * invisible in the cost record (Doc 28 §47).
 *
 * @param <T> the result type of the contained work
 */
public sealed interface SandboxOutcome<T>
    permits SandboxOutcome.Completed,
        SandboxOutcome.Breached,
        SandboxOutcome.Threw,
        SandboxOutcome.Cancelled {

  /**
   * What the invocation consumed.
   *
   * @return the measured usage
   */
  ResourceUsage usage();

  /**
   * The work finished within its budget.
   *
   * @param value the returned value, which may be null if the work returns nothing
   * @param usage the measured usage
   * @param <T> the result type
   */
  record Completed<T>(T value, ResourceUsage usage) implements SandboxOutcome<T> {
    /** Compact constructor validating the outcome. */
    public Completed {
      Preconditions.requireNonNull(usage, "usage");
    }
  }

  /**
   * The work exceeded a quota and was cancelled (Doc 28 REC-2/REC-4).
   *
   * @param kind which quota was breached
   * @param usage the measured usage at the point of cancellation
   * @param <T> the result type
   */
  record Breached<T>(PluginFailureKind kind, ResourceUsage usage) implements SandboxOutcome<T> {
    /** Compact constructor validating the outcome. */
    public Breached {
      Preconditions.requireNonNull(kind, "kind");
      Preconditions.requireNonNull(usage, "usage");
    }
  }

  /**
   * The work threw. The throwable is <b>not</b> carried: its message is arbitrary text from
   * third-party code and could contain anything it was processing, so only a content-free
   * classification crosses this boundary (Doc 14 §7.1).
   *
   * @param kind the classified failure
   * @param usage the measured usage
   * @param <T> the result type
   */
  record Threw<T>(PluginFailureKind kind, ResourceUsage usage) implements SandboxOutcome<T> {
    /** Compact constructor validating the outcome. */
    public Threw {
      Preconditions.requireNonNull(kind, "kind");
      Preconditions.requireNonNull(usage, "usage");
    }
  }

  /**
   * The work was cancelled by the caller before it finished.
   *
   * @param usage the measured usage at the point of cancellation
   * @param <T> the result type
   */
  record Cancelled<T>(ResourceUsage usage) implements SandboxOutcome<T> {
    /** Compact constructor validating the outcome. */
    public Cancelled {
      Preconditions.requireNonNull(usage, "usage");
    }
  }

  /**
   * What one contained invocation consumed.
   *
   * <p>CPU and memory are <b>estimates</b> and named as such (Doc 28 ISO-9). Wall-clock is measured
   * and exact.
   *
   * @param wallClock the measured elapsed time
   * @param cpuMillisEstimate the estimated CPU time
   * @param memoryBytesEstimate the estimated peak memory
   */
  record ResourceUsage(Duration wallClock, long cpuMillisEstimate, long memoryBytesEstimate) {

    /** Compact constructor validating the measurement. */
    public ResourceUsage {
      Preconditions.requireNonNull(wallClock, "wallClock");
      Preconditions.requireNonNegative(cpuMillisEstimate, "cpuMillisEstimate");
      Preconditions.requireNonNegative(memoryBytesEstimate, "memoryBytesEstimate");
    }

    /**
     * A usage record for an invocation that never ran.
     *
     * @return zeroed usage
     */
    public static ResourceUsage none() {
      return new ResourceUsage(Duration.ZERO, 0L, 0L);
    }
  }
}

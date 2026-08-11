package io.reliabilityai.gateway.dataplane.app.binding;

import io.reliabilityai.gateway.dataplane.reliability.api.ReliabilityMetricsPort;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Content-free reliability counters held in process (Doc 20, Doc 27 OT-INV). Observability is
 * passive: these counters are readable by a future exporter but influence no decision, so removing
 * them changes no behaviour. Counting is the honest VPS default until a metrics exporter is wired.
 */
public final class InProcessReliabilityMetrics implements ReliabilityMetricsPort {

  private final AtomicLong succeeded = new AtomicLong();
  private final AtomicLong retries = new AtomicLong();
  private final AtomicLong failovers = new AtomicLong();
  private final AtomicLong circuitOpens = new AtomicLong();
  private final Map<String, AtomicLong> surfacedByReason = new ConcurrentHashMap<>();

  @Override
  public void succeeded() {
    succeeded.incrementAndGet();
  }

  @Override
  public void retry() {
    retries.incrementAndGet();
  }

  @Override
  public void failover() {
    failovers.incrementAndGet();
  }

  @Override
  public void circuitOpen() {
    circuitOpens.incrementAndGet();
  }

  @Override
  public void surfaced(final String reason) {
    surfacedByReason
        .computeIfAbsent(reason == null ? "unknown" : reason, k -> new AtomicLong())
        .incrementAndGet();
  }

  /**
   * The number of invocations that ultimately succeeded.
   *
   * @return the success count
   */
  public long successCount() {
    return succeeded.get();
  }

  /**
   * The number of retry attempts made.
   *
   * @return the retry count
   */
  public long retryCount() {
    return retries.get();
  }

  /**
   * The number of failovers to another candidate.
   *
   * @return the failover count
   */
  public long failoverCount() {
    return failovers.get();
  }

  /**
   * The number of times an open circuit was observed.
   *
   * @return the circuit-open count
   */
  public long circuitOpenCount() {
    return circuitOpens.get();
  }

  /**
   * The number of errors surfaced for a given reason.
   *
   * @param reason the reliability failure reason name
   * @return the surfaced count for that reason
   */
  public long surfacedCount(final String reason) {
    final AtomicLong counter = surfacedByReason.get(reason);
    return counter == null ? 0L : counter.get();
  }
}

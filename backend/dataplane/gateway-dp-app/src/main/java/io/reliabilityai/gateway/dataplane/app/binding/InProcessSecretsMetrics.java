package io.reliabilityai.gateway.dataplane.app.binding;

import io.reliabilityai.gateway.dataplane.secrets.api.SecretsMetricsPort;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Content-free secrets counters held in process (Doc 26, Doc 27 OT-INV). Two of these are health
 * signals worth alerting on once an exporter exists: an incomplete sanitization and any detected
 * lease leak both mean key material outlived its lease.
 */
public final class InProcessSecretsMetrics implements SecretsMetricsPort {

  private final AtomicLong materialized = new AtomicLong();
  private final AtomicLong sanitizedComplete = new AtomicLong();
  private final AtomicLong sanitizedIncomplete = new AtomicLong();
  private final AtomicLong leaseLeaks = new AtomicLong();
  private final Map<String, AtomicLong> unavailableByReason = new ConcurrentHashMap<>();

  @Override
  public void materialized() {
    materialized.incrementAndGet();
  }

  @Override
  public void credentialUnavailable(final String reason) {
    unavailableByReason
        .computeIfAbsent(reason == null ? "unknown" : reason, k -> new AtomicLong())
        .incrementAndGet();
  }

  @Override
  public void sanitization(final boolean complete) {
    if (complete) {
      sanitizedComplete.incrementAndGet();
    } else {
      sanitizedIncomplete.incrementAndGet();
    }
  }

  @Override
  public void leaseLeakDetected() {
    leaseLeaks.incrementAndGet();
  }

  /**
   * The number of credentials successfully materialized.
   *
   * @return the materialized count
   */
  public long materializedCount() {
    return materialized.get();
  }

  /**
   * The number of leases whose material was fully wiped.
   *
   * @return the complete-sanitization count
   */
  public long sanitizedCompleteCount() {
    return sanitizedComplete.get();
  }

  /**
   * The number of leases whose material could not be fully wiped — a security-relevant condition.
   *
   * @return the incomplete-sanitization count
   */
  public long sanitizedIncompleteCount() {
    return sanitizedIncomplete.get();
  }

  /**
   * The number of leases detected as leaked (unclosed at collection).
   *
   * @return the lease-leak count
   */
  public long leaseLeakCount() {
    return leaseLeaks.get();
  }

  /**
   * The number of materialization refusals for a given reason.
   *
   * @param reason the unavailability reason
   * @return the refusal count for that reason
   */
  public long unavailableCount(final String reason) {
    final AtomicLong counter = unavailableByReason.get(reason);
    return counter == null ? 0L : counter.get();
  }
}

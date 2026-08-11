package io.reliabilityai.gateway.dataplane.app.binding;

import io.reliabilityai.gateway.dataplane.authn.api.AuthMetricsPort;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Content-free authentication counters held in process (Doc 37, Doc 27 OT-INV). The per-reason
 * rejection counts are the signal an operator watches to tell a broken key rotation apart from an
 * attack; they name the reason only, never the principal or the token.
 */
public final class InProcessAuthMetrics implements AuthMetricsPort {

  private final AtomicLong authenticated = new AtomicLong();
  private final AtomicLong tenantsResolved = new AtomicLong();
  private final Map<String, AtomicLong> rejectionsByReason = new ConcurrentHashMap<>();

  @Override
  public void authenticated() {
    authenticated.incrementAndGet();
  }

  @Override
  public void unauthenticated(final String reason) {
    rejectionsByReason
        .computeIfAbsent(reason == null ? "unknown" : reason, k -> new AtomicLong())
        .incrementAndGet();
  }

  @Override
  public void tenantResolved() {
    tenantsResolved.incrementAndGet();
  }

  /**
   * The number of successful authentications.
   *
   * @return the authenticated count
   */
  public long authenticatedCount() {
    return authenticated.get();
  }

  /**
   * The number of principals successfully mapped to a tenant scope.
   *
   * @return the tenant-resolved count
   */
  public long tenantResolvedCount() {
    return tenantsResolved.get();
  }

  /**
   * The number of rejections for a given reason.
   *
   * @param reason the rejection reason
   * @return the rejection count for that reason
   */
  public long rejectionCount(final String reason) {
    final AtomicLong counter = rejectionsByReason.get(reason);
    return counter == null ? 0L : counter.get();
  }
}

package io.reliabilityai.gateway.dataplane.memory.crypto;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Counters held in this process, for tests and for deployments with nowhere else to send them.
 *
 * <p>Not a substitute for a real metrics backend: nothing here is exported, aggregated or retained
 * across a restart. It exists so that the cipher's failure paths are observable at all, and so
 * tests can assert that a refusal was recorded rather than swallowed — the difference between a
 * cipher that fails closed and one that fails silently is entirely visible here and nowhere else.
 */
public final class InProcessCryptoMetrics implements CryptoMetricsPort {

  private final Map<String, AtomicLong> counters = new ConcurrentHashMap<>();

  @Override
  public void sealed(final String keyVersion, final int plaintextBytes) {
    bump("sealed");
    bump("sealed." + keyVersion);
    counters.computeIfAbsent("sealed.bytes", ignored -> new AtomicLong()).addAndGet(plaintextBytes);
  }

  @Override
  public void opened(final String keyVersion) {
    bump("opened");
    bump("opened." + keyVersion);
  }

  @Override
  public void openFailed(final String keyVersion, final OpenFailure reason) {
    bump("openFailed");
    bump("openFailed." + reason.name());
  }

  @Override
  public void nonceCollisionSuspected(final String keyVersion) {
    bump("nonceCollisionSuspected");
  }

  @Override
  public void wrapBudgetExceeded(final String keyVersion) {
    bump("wrapBudgetExceeded");
  }

  @Override
  public void rotated(final String fromVersion, final String toVersion) {
    bump("rotated");
  }

  @Override
  public void rewrapped(final String fromVersion, final String toVersion) {
    bump("rewrapped");
  }

  /**
   * Reads one counter.
   *
   * @param name the counter name
   * @return its value, zero when never incremented
   */
  public long count(final String name) {
    final AtomicLong counter = counters.get(name);
    return counter == null ? 0L : counter.get();
  }

  /**
   * Increments a counter.
   *
   * @param name the counter name
   */
  private void bump(final String name) {
    counters.computeIfAbsent(name, ignored -> new AtomicLong()).incrementAndGet();
  }
}

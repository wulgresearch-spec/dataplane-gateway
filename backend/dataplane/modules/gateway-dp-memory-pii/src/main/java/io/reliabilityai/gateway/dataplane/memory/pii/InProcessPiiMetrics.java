package io.reliabilityai.gateway.dataplane.memory.pii;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Counters held in this process, for tests and for deployments with nowhere else to send them.
 *
 * <p>Nothing here is exported, aggregated or retained across a restart. It exists so the engine's
 * discard paths are observable at all: the difference between "found nothing" and "found things and
 * threw them away below the confidence floor" is invisible in the result and entirely visible here.
 */
public final class InProcessPiiMetrics implements PiiMetricsPort {

  private final Map<String, AtomicLong> counters = new ConcurrentHashMap<>();

  @Override
  public void scanned(final int characters, final long nanos) {
    bump("scanned");
    counters
        .computeIfAbsent("scanned.characters", ignored -> new AtomicLong())
        .addAndGet(characters);
    counters.computeIfAbsent("scanned.nanos", ignored -> new AtomicLong()).addAndGet(nanos);
  }

  @Override
  public void detected(final PiiType type) {
    bump("detected");
    bump("detected." + type.name());
  }

  @Override
  public void validatorRejected(final PiiType type) {
    bump("validatorRejected");
    bump("validatorRejected." + type.name());
  }

  @Override
  public void droppedBelowConfidence(final PiiType type) {
    bump("droppedBelowConfidence");
    bump("droppedBelowConfidence." + type.name());
  }

  @Override
  public void suppressedByAllowRule(final String ruleId) {
    bump("suppressedByAllowRule");
  }

  @Override
  public void budgetExhausted(final String ruleId) {
    bump("budgetExhausted");
  }

  @Override
  public void truncated() {
    bump("truncated");
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

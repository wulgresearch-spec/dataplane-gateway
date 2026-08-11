package io.reliabilityai.gateway.dataplane.memory.internal;

import io.reliabilityai.gateway.common.Preconditions;
import io.reliabilityai.gateway.dataplane.memory.api.DataClassification;
import io.reliabilityai.gateway.dataplane.memory.api.MemoryMetricsPort;
import io.reliabilityai.gateway.dataplane.memory.api.MemoryStage;
import io.reliabilityai.gateway.dataplane.memory.api.MemoryType;
import io.reliabilityai.gateway.dataplane.memory.api.RetrievalMode;
import java.time.Duration;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.LongAdder;

/**
 * Counters held in this process.
 *
 * <p>Enough to make the memory plane observable in a single-node deployment, and enough for the
 * tests to assert on signals rather than on log output. A fleet feeds the real telemetry
 * infrastructure through the same port; nothing here is a new sink.
 *
 * <p>{@link LongAdder} rather than {@code AtomicLong}: written from every request thread and read
 * rarely, which is exactly the access pattern the adder exists for.
 */
public final class InProcessMemoryMetrics implements MemoryMetricsPort {

  private final Map<String, LongAdder> counters = new ConcurrentHashMap<>();

  @Override
  public void written(
      final MemoryType type,
      final DataClassification classification,
      final boolean sealed,
      final Duration took) {
    bump("memory.written");
    bump("memory.written." + type.name());
    bump("memory.written.classification." + classification.name());
    if (sealed) {
      bump("memory.written.sealed");
    }
    add("memory.write.micros", took.toNanos() / 1_000L);
  }

  @Override
  public void read(
      final RetrievalMode mode, final int examined, final int returned, final Duration took) {
    bump("memory.read");
    bump("memory.read." + mode.name());
    add("memory.read.examined", examined);
    add("memory.read.returned", returned);
    add("memory.read.micros", took.toNanos() / 1_000L);
    if (returned == 0) {
      // Counted separately: a rising empty-read rate is the earliest signal of an enumeration probe
      // or
      // of a scope that is narrower than a caller expects.
      bump("memory.read.empty");
    }
  }

  @Override
  public void refused(final MemoryStage stage, final String code) {
    bump("memory.refused");
    bump("memory.refused." + stage.name());
    bump("memory.refused.code." + code);
  }

  @Override
  public void unenforceable(final MemoryStage stage) {
    bump("memory.unenforceable");
    bump("memory.unenforceable." + stage.name());
  }

  @Override
  public void degraded(final RetrievalMode mode) {
    bump("memory.degraded");
  }

  @Override
  public void isolationViolation(final String adapter) {
    // Must always read zero. Any other value means an adapter returned a record from outside the
    // narrowed scope and the runtime's re-verification caught it — defence in depth working, and an
    // incident regardless.
    bump("memory.isolation_violation");
  }

  @Override
  public void integrityFailure(final MemoryType type) {
    bump("memory.integrity_failure");
  }

  @Override
  public void deleted(final MemoryType type) {
    bump("memory.deleted");
    bump("memory.deleted." + type.name());
  }

  @Override
  public void swept(final int expired, final int archived, final int held) {
    add("memory.swept.expired", expired);
    add("memory.swept.archived", archived);
    add("memory.swept.held", held);
  }

  @Override
  public void storeUnavailable(final String operation) {
    bump("memory.store_unavailable");
    bump("memory.store_unavailable." + operation);
  }

  @Override
  public void snapshotInstalled(final int scopes) {
    bump("memory.snapshot_installed");
  }

  /**
   * Reads one counter.
   *
   * @param name the counter name
   * @return its value, zero when never touched
   */
  public long counter(final String name) {
    Preconditions.requireNonNull(name, "name");
    final LongAdder adder = counters.get(name);
    return adder == null ? 0L : adder.sum();
  }

  /**
   * Returns a snapshot of every counter.
   *
   * @return name to value, at the moment of the call
   */
  public Map<String, Long> snapshot() {
    final Map<String, Long> copy = new TreeMap<>();
    counters.forEach((name, adder) -> copy.put(name, adder.sum()));
    return java.util.Collections.unmodifiableMap(copy);
  }

  /** Resets every counter. For tests that assert on deltas. */
  public void reset() {
    counters.clear();
  }

  private void bump(final String name) {
    add(name, 1L);
  }

  private void add(final String name, final long delta) {
    counters.computeIfAbsent(name, key -> new LongAdder()).add(delta);
  }
}

package io.reliabilityai.gateway.dataplane.governance.internal;

import io.reliabilityai.gateway.dataplane.governance.api.PolicyDomain;
import io.reliabilityai.gateway.dataplane.governance.api.PolicyMetrics;
import io.reliabilityai.gateway.dataplane.governance.api.PolicyScope;
import io.reliabilityai.gateway.dataplane.governance.api.Verdict;
import java.util.EnumMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;

/**
 * Node-local governance counters (Doc 21 §31), for a deployment with no metrics backend wired.
 *
 * <p>Built on {@link LongAdder} rather than {@link AtomicLong} for the counters, because these are
 * incremented by every request on every thread and read approximately never — precisely the
 * write-contended, read-rare pattern {@code LongAdder} exists for. Under load a striped adder
 * avoids the cache-line ping-pong that would otherwise make a metric more expensive than the
 * decision it is measuring.
 *
 * <p>Every dimension is a closed enum, so the counter set is fixed at compile time and cannot grow
 * with the customer base (Doc 21 §31 bounded cardinality). There is no tenant label here and there
 * is not meant to be one; per-tenant decision detail belongs in the audit stream.
 *
 * <p>Latency is kept as a running total and a maximum rather than a histogram. That is an honest
 * limitation: it yields a mean and a worst case, not a p99. A real deployment should bridge {@link
 * PolicyMetrics} onto the platform's histogram support; this implementation exists so that a node
 * without one still reports something true rather than nothing.
 */
public final class InProcessPolicyMetrics implements PolicyMetrics {

  private final Map<Verdict, LongAdder> decisions = new EnumMap<>(Verdict.class);
  private final Map<PolicyDomain, LongAdder> bindings = new EnumMap<>(PolicyDomain.class);
  private final LongAdder latencyTotalNanos = new LongAdder();
  private final LongAdder evaluations = new LongAdder();
  private final AtomicLong latencyMaxNanos = new AtomicLong();
  private final LongAdder policyUnavailable = new LongAdder();
  private final LongAdder staleUsage = new LongAdder();
  private final LongAdder cacheHits = new LongAdder();
  private final LongAdder cacheMisses = new LongAdder();
  private final LongAdder installs = new LongAdder();
  private final LongAdder rollbacks = new LongAdder();
  private final LongAdder rejections = new LongAdder();
  private final Map<PolicyScope, LongAdder> unenforceableScopes = new EnumMap<>(PolicyScope.class);

  /** Creates the counter set with every dimension pre-registered. */
  public InProcessPolicyMetrics() {
    for (final Verdict verdict : Verdict.values()) {
      decisions.put(verdict, new LongAdder());
    }
    for (final PolicyDomain domain : PolicyDomain.values()) {
      bindings.put(domain, new LongAdder());
    }
    for (final PolicyScope scope : PolicyScope.values()) {
      unenforceableScopes.put(scope, new LongAdder());
    }
  }

  @Override
  public void decision(final Verdict verdict, final long latencyNanos) {
    decisions.get(verdict).increment();
    evaluations.increment();
    latencyTotalNanos.add(latencyNanos);
    latencyMaxNanos.accumulateAndGet(latencyNanos, Math::max);
  }

  @Override
  public void binding(final PolicyDomain domain, final Verdict verdict) {
    bindings.get(domain).increment();
  }

  @Override
  public void policyUnavailable() {
    policyUnavailable.increment();
  }

  @Override
  public void staleUsage() {
    staleUsage.increment();
  }

  @Override
  public void cacheLookup(final boolean hit) {
    if (hit) {
      cacheHits.increment();
    } else {
      cacheMisses.increment();
    }
  }

  @Override
  public void snapshotInstalled(final boolean rollback) {
    if (rollback) {
      rollbacks.increment();
    } else {
      installs.increment();
    }
  }

  @Override
  public void snapshotRejected() {
    rejections.increment();
  }

  @Override
  public void unenforceableScope(final PolicyScope scope) {
    unenforceableScopes.get(scope).increment();
  }

  /**
   * How many decisions ended in a given verdict.
   *
   * @param verdict the verdict
   * @return the count
   */
  public long decisions(final Verdict verdict) {
    return decisions.get(verdict).sum();
  }

  /**
   * How many decisions were bound by a given precedence tier.
   *
   * @param domain the tier
   * @return the count
   */
  public long bindings(final PolicyDomain domain) {
    return bindings.get(domain).sum();
  }

  /**
   * Total evaluations recorded.
   *
   * @return the count
   */
  public long evaluations() {
    return evaluations.sum();
  }

  /**
   * Mean evaluation cost.
   *
   * @return the mean in nanoseconds, or zero before the first evaluation
   */
  public long meanLatencyNanos() {
    final long count = evaluations.sum();
    return count == 0L ? 0L : latencyTotalNanos.sum() / count;
  }

  /**
   * Worst evaluation cost seen.
   *
   * @return the maximum in nanoseconds
   */
  public long maxLatencyNanos() {
    return latencyMaxNanos.get();
  }

  /**
   * Refusals caused by policy that could not be resolved — a snapshot-pipeline health signal.
   *
   * @return the count
   */
  public long policyUnavailableCount() {
    return policyUnavailable.sum();
  }

  /**
   * Refusals caused by a consumption reading too old to enforce against.
   *
   * @return the count
   */
  public long staleUsageCount() {
    return staleUsage.sum();
  }

  /**
   * Effective-policy cache hits.
   *
   * @return the count
   */
  public long cacheHits() {
    return cacheHits.sum();
  }

  /**
   * Effective-policy cache misses.
   *
   * @return the count
   */
  public long cacheMisses() {
    return cacheMisses.sum();
  }

  /**
   * Forward installations of a new generation.
   *
   * @return the count
   */
  public long installs() {
    return installs.sum();
  }

  /**
   * Deliberate rollbacks to a previous generation.
   *
   * @return the count
   */
  public long rollbacks() {
    return rollbacks.sum();
  }

  /**
   * Generations offered but refused as stale, incoherent or unavailable.
   *
   * @return the count
   */
  public long rejections() {
    return rejections.sum();
  }

  /**
   * Generations put in force carrying a document attached to a scope this build never constructs.
   *
   * <p>A non-zero reading means an operator has authored a restriction that is stored and reported
   * as accepted but folds into nothing. It is a configuration signal, not a health signal: the node
   * is enforcing exactly what it always was, and the surprise is on the authoring side.
   *
   * @param scope the declared scope
   * @return the count
   */
  public long unenforceableScopes(final PolicyScope scope) {
    return unenforceableScopes.get(scope).sum();
  }
}

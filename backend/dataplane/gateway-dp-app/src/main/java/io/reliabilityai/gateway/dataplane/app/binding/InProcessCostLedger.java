package io.reliabilityai.gateway.dataplane.app.binding;

import io.reliabilityai.gateway.common.Preconditions;
import io.reliabilityai.gateway.dataplane.cost.api.CostLedgerSinkPort;
import io.reliabilityai.gateway.dataplane.cost.api.CostResult;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

/**
 * A bounded in-process tail of priced results, for operational inspection only (Doc 22).
 *
 * <p><b>This is not the billing record.</b> {@link CostResult} carries no tenant or model identity
 * and is not {@code ContentFree}, so it cannot be published as a canonical {@code CostFact} on its
 * own. The durable, zero-loss billing emission is done by {@link CostEngineUsageBridge}, which
 * holds the originating {@code CostRequest} and therefore the full accounting identity. This sink
 * exists so the cost engine's own emission point is bound rather than silently dropped, and it is
 * bounded so a long-running node cannot grow without limit.
 */
public final class InProcessCostLedger implements CostLedgerSinkPort {

  private final Deque<CostResult> recent = new ArrayDeque<>();
  private final AtomicLong emitted = new AtomicLong();
  private final int capacity;

  /**
   * Creates the bounded ledger tail.
   *
   * @param capacity the maximum number of recent results retained (must be >= 1)
   */
  public InProcessCostLedger(final int capacity) {
    if (capacity < 1) {
      throw new IllegalArgumentException("capacity must be >= 1");
    }
    this.capacity = capacity;
  }

  @Override
  public void emitCostFact(final CostResult result) {
    Preconditions.requireNonNull(result, "result");
    emitted.incrementAndGet();
    synchronized (recent) {
      recent.addLast(result);
      while (recent.size() > capacity) {
        recent.removeFirst();
      }
    }
  }

  /**
   * The total number of priced results emitted since startup.
   *
   * @return the emitted count
   */
  public long emittedCount() {
    return emitted.get();
  }

  /**
   * A snapshot of the retained recent results, oldest first.
   *
   * @return the bounded recent tail
   */
  public List<CostResult> recent() {
    synchronized (recent) {
      return List.copyOf(recent);
    }
  }
}

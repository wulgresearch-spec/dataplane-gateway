package io.reliabilityai.gateway.dataplane.agent.application;

import io.reliabilityai.gateway.canonical.identity.TenantScope;
import io.reliabilityai.gateway.common.Preconditions;
import io.reliabilityai.gateway.dataplane.agent.api.RunId;
import io.reliabilityai.gateway.dataplane.agent.api.RunRepository;
import io.reliabilityai.gateway.ports.ClockPort;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * Claims runnable runs and advances them one step each (AD-025 §55).
 *
 * <p><b>Pull, not push.</b> Executors claim work; no dispatcher assigns it. Pull needs no
 * membership view — a push dispatcher must know which nodes are alive, which is a
 * distributed-systems problem this runtime should not acquire — it load-balances naturally, because
 * a busy node simply claims less, and node loss is a non-event, because an unclaimed run is claimed
 * by somebody else.
 *
 * <p><b>The claim is an optimisation, not the safety mechanism.</b> Two nodes that both believe
 * they hold a lease — clock skew, a partition, a lease that expired mid-step — cannot both proceed,
 * because only one of them can win the conditional append at the history's current offset. Safety
 * therefore does not depend on the lease being correct, which matters because in a distributed
 * system it sometimes will not be.
 *
 * <p>Fairness is round-robin across tenants, so one tenant's fleet of runs cannot starve another's.
 */
public final class RunScheduler {

  /**
   * What one scheduling pass did.
   *
   * @param claimed runs this pass took ownership of
   * @param advanced runs moved forward by at least one step
   * @param terminated runs that reached a final state
   * @param contended runs another scheduler already held, so this pass skipped them
   * @param stalled runs that could not progress and were left for a later pass
   */
  public record Pass(int claimed, int advanced, int terminated, int contended, int stalled) {

    /**
     * Reports whether the pass did any work.
     *
     * @return true when at least one run was advanced or ended
     */
    public boolean productive() {
      return advanced > 0 || terminated > 0;
    }
  }

  private final RunRepository runs;
  private final RunExecutor executor;
  private final ClockPort clock;
  private final String nodeId;
  private final Duration leaseDuration;
  private final int batchSize;

  /**
   * Creates a scheduler.
   *
   * @param runs the durable run store
   * @param executor the stateless step executor
   * @param clock the injected clock
   * @param nodeId an opaque identifier for this node, recorded on claims
   * @param leaseDuration how long a claim is held before another node may take the run
   * @param batchSize the most runs one pass claims
   */
  public RunScheduler(
      final RunRepository runs,
      final RunExecutor executor,
      final ClockPort clock,
      final String nodeId,
      final Duration leaseDuration,
      final int batchSize) {
    this.runs = Preconditions.requireNonNull(runs, "runs");
    this.executor = Preconditions.requireNonNull(executor, "executor");
    this.clock = Preconditions.requireNonNull(clock, "clock");
    this.nodeId = Preconditions.requireNonBlank(nodeId, "nodeId");
    this.leaseDuration = Preconditions.requireNonNull(leaseDuration, "leaseDuration");
    if (leaseDuration.isZero() || leaseDuration.isNegative()) {
      throw new IllegalArgumentException("leaseDuration must be positive, was " + leaseDuration);
    }
    if (batchSize < 1) {
      throw new IllegalArgumentException("batchSize must be >= 1, was " + batchSize);
    }
    this.batchSize = batchSize;
  }

  /**
   * Claims and advances up to {@code batchSize} runs of one tenant, one step each.
   *
   * <p>One step per run per pass, deliberately. Draining a run to completion inside a pass would
   * let a long run monopolise the node and would defeat the fairness the round-robin buys.
   *
   * @param tenant the tenant to serve
   * @return what the pass did
   */
  public Pass pass(final TenantScope tenant) {
    Preconditions.requireNonNull(tenant, "tenant");
    final Instant now = clock.now();
    final List<RunId> candidates = runs.claimable(tenant, now, batchSize);

    int claimed = 0;
    int advanced = 0;
    int terminated = 0;
    int contended = 0;
    int stalled = 0;

    for (final RunId runId : candidates) {
      if (!runs.claim(runId, nodeId, now, now.plus(leaseDuration))) {
        contended++;
        continue;
      }
      claimed++;
      try {
        final RunExecutor.Advance outcome = executor.advance(runId);
        switch (outcome) {
          case RunExecutor.Advance.Advanced ignored -> advanced++;
          case RunExecutor.Advance.Parked ignored -> advanced++;
          case RunExecutor.Advance.Terminated ignored -> terminated++;
          case RunExecutor.Advance.Contended ignored -> contended++;
          case RunExecutor.Advance.Stalled ignored -> stalled++;
          case RunExecutor.Advance.Idle ignored -> {
            // Claimable when listed, not claimable now — another node finished it in between. Not
            // an
            // error, and counting it as work would make the pass statistics lie.
          }
        }
      } finally {
        // Released on every path, including an exception escaping the executor. A leaked lease is
        // survivable — it expires — but it delays recovery for exactly as long as the lease lasts.
        runs.release(runId, nodeId);
      }
    }
    return new Pass(claimed, advanced, terminated, contended, stalled);
  }

  /**
   * Runs one pass for each tenant in turn.
   *
   * <p>Round-robin rather than a global queue: a global queue sorted by age hands the whole node to
   * whichever tenant submitted the most work, and no per-run bound prevents that.
   *
   * @param tenants the tenants to serve, in order
   * @return the passes, one per tenant, in the same order
   */
  public List<Pass> roundRobin(final List<TenantScope> tenants) {
    Preconditions.requireNonNull(tenants, "tenants");
    final List<Pass> passes = new ArrayList<>(tenants.size());
    for (final TenantScope tenant : tenants) {
      passes.add(pass(tenant));
    }
    return List.copyOf(passes);
  }

  /**
   * Advances one run repeatedly until it stops making progress.
   *
   * <p>For tests and for a single-tenant embedded deployment. Not what the fleet does — a scheduler
   * that drained runs would hold a node for a whole run and lose the interchangeability that makes
   * crash recovery cheap.
   *
   * @param runId the run to drive
   * @param maxSteps a hard cap on iterations, so a bug here cannot spin forever
   * @return the last outcome observed
   */
  public RunExecutor.Advance drain(final RunId runId, final int maxSteps) {
    Preconditions.requireNonNull(runId, "runId");
    if (maxSteps < 1) {
      throw new IllegalArgumentException("maxSteps must be >= 1, was " + maxSteps);
    }
    RunExecutor.Advance last = new RunExecutor.Advance.Idle();
    for (int i = 0; i < maxSteps; i++) {
      last = executor.advance(runId);
      if (last instanceof RunExecutor.Advance.Terminated
          || last instanceof RunExecutor.Advance.Idle
          || last instanceof RunExecutor.Advance.Stalled
          || last instanceof RunExecutor.Advance.Parked) {
        return last;
      }
    }
    return last;
  }

  /**
   * Returns this node's identifier.
   *
   * @return the opaque node id recorded on claims
   */
  public String nodeId() {
    return nodeId;
  }
}

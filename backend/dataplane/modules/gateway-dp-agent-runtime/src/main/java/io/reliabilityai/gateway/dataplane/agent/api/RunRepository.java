package io.reliabilityai.gateway.dataplane.agent.api;

import io.reliabilityai.gateway.canonical.identity.TenantScope;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * The durable Run Store (AD-025 §18.1, §83).
 *
 * <p>Owns history and nothing else. It never executes a step, never calls a provider, never calls a
 * plugin and never orchestrates. Every method here either reads events or appends one.
 *
 * <p><b>{@link #append} is the correctness primitive of the whole runtime.</b> It is a conditional
 * write: an append at offset N succeeds only if the run's history is exactly N long. That is what
 * makes two executors advancing one run impossible — even if both believe they hold the claim,
 * because of clock skew or a partition, only one can win the append and the other must abandon its
 * step. Safety therefore does not depend on the lease being correct; the lease only avoids wasted
 * work.
 */
public interface RunRepository {

  /**
   * The outcome of a conditional append.
   *
   * <p>A sealed result rather than a boolean or an exception: the two failure modes need different
   * responses — a conflict means "someone else won, stop", while unavailability means "stop and try
   * later" — and collapsing them would make one of the two handled wrongly.
   */
  sealed interface AppendResult
      permits AppendResult.Appended, AppendResult.Conflict, AppendResult.Unavailable {

    /**
     * The event is durably recorded.
     *
     * @param newOffset the history length after the append
     */
    record Appended(long newOffset) implements AppendResult {}

    /**
     * Another writer got there first. The caller must reload and re-decide, never overwrite.
     *
     * @param actualOffset the history length the store actually holds
     */
    record Conflict(long actualOffset) implements AppendResult {}

    /**
     * The store could not be reached.
     *
     * <p>Distinct from an empty history, always. AD-025 HSC-8: an empty history is
     * indistinguishable from a new run, so a store that reported unavailability as emptiness would
     * cause a live run to be restarted from scratch — the worst available failure mode.
     *
     * @param reason a short operator-facing explanation
     */
    record Unavailable(String reason) implements AppendResult {}
  }

  /**
   * Creates a run by recording its first event.
   *
   * @param runId the run to create
   * @param created the creation event
   * @return {@link AppendResult.Appended} on success, {@link AppendResult.Conflict} when the run
   *     already exists
   */
  AppendResult create(RunId runId, RunEvent.RunCreated created);

  /**
   * Appends one event if and only if the history is exactly {@code expectedOffset} long.
   *
   * @param runId the run to append to
   * @param expectedOffset the offset the caller believes the history is at
   * @param event the event to record
   * @return the outcome
   */
  AppendResult append(RunId runId, long expectedOffset, RunEvent event);

  /**
   * Loads a run's whole history.
   *
   * @param runId the run to load
   * @return the history, or empty when no such run exists
   * @throws RunStoreUnavailableException when the store cannot be reached; never an empty history
   */
  Optional<RunHistory> load(RunId runId);

  /**
   * Returns runs that an executor may claim right now, oldest first.
   *
   * @param tenant the tenant to scope to; the store is tenant-partitioned (AD-025 AGT-26)
   * @param now the instant to evaluate parked runs against
   * @param limit the most runs to return
   * @return the claimable run ids
   */
  List<RunId> claimable(TenantScope tenant, Instant now, int limit);

  /**
   * Returns runs that are not terminal, whatever their state.
   *
   * <p>The recovery sweep's input. Includes runs interrupted mid-step, runs parked past their wake
   * time, and runs whose wall-clock bound has expired while they sat in a waiting state — the last
   * of which is why AD-025 SM-8 lets a bound terminate a run from any non-terminal state.
   *
   * @param tenant the tenant to scope to
   * @param limit the most runs to return
   * @return the unfinished run ids
   */
  List<RunId> unfinished(TenantScope tenant, int limit);

  /**
   * Attempts to take an exclusive lease on advancing a run.
   *
   * <p>An optimisation, not the safety mechanism. A lease avoids two nodes doing the same work; the
   * conditional append in {@link #append} is what makes doing it twice harmless.
   *
   * @param runId the run to claim
   * @param owner an opaque identifier for the claiming node
   * @param now the current instant, supplied from the injected clock rather than read here — the
   *     store must not have a clock of its own, or two nodes with skewed clocks would disagree
   *     about whose lease is live and neither would be wrong
   * @param until when the lease expires
   * @return true when the lease was taken
   */
  boolean claim(RunId runId, String owner, Instant now, Instant until);

  /**
   * Releases a lease early.
   *
   * @param runId the run to release
   * @param owner the node that holds the lease; a non-holder's release is ignored
   */
  void release(RunId runId, String owner);
}

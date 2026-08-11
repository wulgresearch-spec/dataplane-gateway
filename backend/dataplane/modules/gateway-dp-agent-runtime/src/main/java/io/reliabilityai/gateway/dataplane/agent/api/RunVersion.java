package io.reliabilityai.gateway.dataplane.agent.api;

import io.reliabilityai.gateway.common.Preconditions;

/**
 * The version stamp written into every run at creation and never changed (AD-025 AGT-25, §46.4).
 *
 * <p>This is the mechanism that makes replay valid across deployments. A run pins the plan version
 * it started with; publishing plan v4 while a v3 run is in flight cannot change what that run
 * replays, because the run does not resolve "the current plan" — it resolves the exact version
 * recorded here. Azure Durable Functions needed dedicated machinery for this problem; making the
 * plan an immutable value reduces it to a field.
 *
 * <p>{@code journalFormat} is pinned for the same reason one layer down: a run's history must
 * remain readable by the serializer generation that wrote it, even after the serializer changes.
 *
 * @param planId the plan being executed
 * @param planVersion the exact plan version, pinned at run creation
 * @param journalFormat the durable-journal format version this run's history is written in
 */
public record RunVersion(PlanId planId, int planVersion, int journalFormat) {

  /**
   * The journal format this build writes. Bumped only by a format change with a reader for both.
   */
  public static final int CURRENT_JOURNAL_FORMAT = 1;

  /**
   * Validates the version stamp.
   *
   * @param planId the plan being executed
   * @param planVersion the pinned plan version
   * @param journalFormat the journal format version
   */
  public RunVersion {
    Preconditions.requireNonNull(planId, "planId");
    if (planVersion < 1) {
      throw new IllegalArgumentException("planVersion must be >= 1, was " + planVersion);
    }
    if (journalFormat < 1) {
      throw new IllegalArgumentException("journalFormat must be >= 1, was " + journalFormat);
    }
  }

  /**
   * Pins a plan at the current journal format.
   *
   * @param planId the plan being executed
   * @param planVersion the plan version to pin
   * @return the version stamp
   */
  public static RunVersion pin(final PlanId planId, final int planVersion) {
    return new RunVersion(planId, planVersion, CURRENT_JOURNAL_FORMAT);
  }

  /**
   * Reports whether this run was created by a plan version this build can still replay.
   *
   * <p>A run whose journal format this build cannot read must not be resumed — resuming it would
   * mean interpreting bytes under the wrong grammar and silently producing a different execution.
   *
   * @return true when the journal format is one this build understands
   */
  public boolean replayable() {
    return journalFormat <= CURRENT_JOURNAL_FORMAT;
  }
}

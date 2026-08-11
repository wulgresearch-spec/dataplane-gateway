package io.reliabilityai.gateway.dataplane.agent.api;

import java.util.Optional;

/**
 * Resolves pinned plan versions (AD-025 §46.4, PLC-12).
 *
 * <p>Read-only, deliberately. AD-025 AGT-6: an agent does not author, approve or register its own
 * plan. Publication is a control-plane act performed by whoever owns the plan, and the runtime is a
 * consumer — the same discipline Kubernetes applies by keeping registration in the API server
 * rather than the controller.
 *
 * <p><b>{@link #resolve} takes a version and never "the latest".</b> There is no method here that
 * returns a current version, because a resumed run must replay against the version it started with;
 * a lookup that could silently hand back v4 to a v3 run is the exact failure AGT-25 exists to
 * prevent, and the cheapest way to prevent it is not to offer the call.
 */
public interface PlanRepository {

  /**
   * Resolves one exact plan version.
   *
   * @param planId the plan
   * @param version the pinned version
   * @return the plan, or empty when that version is unknown or has been purged
   */
  Optional<Plan> resolve(PlanId planId, int version);

  /**
   * Resolves the plan a run pinned.
   *
   * @param runVersion the run's pinned version stamp
   * @return the plan, or empty when that version is unknown
   */
  default Optional<Plan> resolve(final RunVersion runVersion) {
    return resolve(runVersion.planId(), runVersion.planVersion());
  }

  /**
   * Reports whether a plan version is still retained.
   *
   * <p>Retention is bounded by the maximum run duration: once no live run can reference a version,
   * it may be collected. A recovery sweep uses this to distinguish "this run's plan is gone" —
   * which is {@code PLAN_INVALID} and terminal — from a transient lookup failure.
   *
   * @param planId the plan
   * @param version the version
   * @return true when the version can still be resolved
   */
  boolean retains(PlanId planId, int version);
}

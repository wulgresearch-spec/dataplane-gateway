package io.reliabilityai.gateway.dataplane.agent.internal;

import io.reliabilityai.gateway.common.Preconditions;
import io.reliabilityai.gateway.dataplane.agent.api.Plan;
import io.reliabilityai.gateway.dataplane.agent.api.PlanId;
import io.reliabilityai.gateway.dataplane.agent.api.PlanRepository;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Holds published plan versions.
 *
 * <p>Immutable by construction: {@link #publish} refuses to replace an existing version rather than
 * overwriting it. That refusal is the mechanism behind AD-025 AGT-25 — a run pins a version, and if
 * a deployment could mutate that version in place, the pin would guarantee nothing and a resumed
 * run would replay against a plan it never started with. Publishing a change means publishing a new
 * version number.
 *
 * <p>Version retention is explicit rather than automatic. A plan version must stay resolvable while
 * any run references it (AD-025 PLC-12), and only the deployment that knows the maximum run
 * duration can decide when that stops being true — so collection is a deliberate call, not a cache
 * eviction.
 */
public final class InMemoryPlanRepository implements PlanRepository {

  private record Key(PlanId planId, int version) {}

  private final Map<Key, Plan> plans = new ConcurrentHashMap<>();

  /**
   * Publishes a plan version.
   *
   * @param plan the plan to publish
   * @return true when it was published, false when that version already exists and was left
   *     untouched
   */
  public boolean publish(final Plan plan) {
    Preconditions.requireNonNull(plan, "plan");
    return plans.putIfAbsent(new Key(plan.id(), plan.version()), plan) == null;
  }

  @Override
  public Optional<Plan> resolve(final PlanId planId, final int version) {
    Preconditions.requireNonNull(planId, "planId");
    return Optional.ofNullable(plans.get(new Key(planId, version)));
  }

  @Override
  public boolean retains(final PlanId planId, final int version) {
    Preconditions.requireNonNull(planId, "planId");
    return plans.containsKey(new Key(planId, version));
  }

  /**
   * Forgets one plan version.
   *
   * <p>Deliberately unguarded: this repository does not know which runs are live, so it cannot
   * check. A caller that collects a version still referenced by an in-flight run will find that run
   * terminate with {@code FAILED_PLAN} at its next step — which is loud, recorded, and much better
   * than a run silently replaying against something else.
   *
   * @param planId the plan
   * @param version the version to collect
   * @return true when a version was removed
   */
  public boolean collect(final PlanId planId, final int version) {
    Preconditions.requireNonNull(planId, "planId");
    return plans.remove(new Key(planId, version)) != null;
  }

  /**
   * Returns how many plan versions are retained.
   *
   * @return the version count across all plans
   */
  public int size() {
    return plans.size();
  }
}

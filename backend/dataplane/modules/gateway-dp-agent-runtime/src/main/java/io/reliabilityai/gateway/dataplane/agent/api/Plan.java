package io.reliabilityai.gateway.dataplane.agent.api;

import io.reliabilityai.gateway.common.Preconditions;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * An immutable, versioned plan (AD-025 §22, PLC-1).
 *
 * <p>The plan is <b>data, not code</b>. That single decision earns its place three times:
 * governance can authorize a value before it runs (§40.2), a run can pin the exact version it
 * started with so a deployment cannot invalidate an in-flight history (§46.4), and the audit trail
 * can answer "what was this agent instructed to do" without anyone resolving a commit hash.
 *
 * <p><b>Validation happens here, at construction, not at run time</b> (AD-025 PLC-2). An invalid
 * plan can therefore never start a run, so a run never fails at step 30 for a mistake that could
 * have been reported when the plan was published.
 *
 * @param id the plan's identity
 * @param version the plan's version; a change publishes a new version rather than mutating this one
 * @param steps the plan's steps in declaration order, at least one
 * @param bounds the run bounds a run of this plan starts with
 * @param restartPolicy the supervision policy a run of this plan starts with
 */
public record Plan(
    PlanId id, int version, List<Step> steps, RunBounds bounds, RestartPolicy restartPolicy) {

  /**
   * Validates the plan in full: names, references, branch targets and bounds consistency.
   *
   * @param id the plan identity
   * @param version the plan version
   * @param steps the plan steps
   * @param bounds the run bounds
   * @param restartPolicy the supervision policy
   */
  public Plan {
    Preconditions.requireNonNull(id, "id");
    Preconditions.requireNonNull(bounds, "bounds");
    Preconditions.requireNonNull(restartPolicy, "restartPolicy");
    steps = steps == null ? List.of() : List.copyOf(steps);
    if (version < 1) {
      throw new IllegalArgumentException("version must be >= 1, was " + version);
    }
    if (steps.isEmpty()) {
      throw new IllegalArgumentException("a plan must declare at least one step");
    }
    validate(steps, bounds, restartPolicy);
  }

  /**
   * Returns the step at a plan index.
   *
   * @param index the plan index
   * @return the step
   * @throws IndexOutOfBoundsException when the index is outside the plan
   */
  public Step stepAt(final int index) {
    return steps.get(index);
  }

  /**
   * Looks a step up by name.
   *
   * @param name the plan-local step name
   * @return the step, or empty when the plan declares no such name
   */
  public Optional<Step> stepNamed(final String name) {
    Preconditions.requireNonNull(name, "name");
    for (final Step step : steps) {
      if (step.name().equals(name)) {
        return Optional.of(step);
      }
    }
    return Optional.empty();
  }

  /**
   * Returns a step's plan index by name.
   *
   * @param name the plan-local step name
   * @return the index, or -1 when the plan declares no such name
   */
  public int indexOf(final String name) {
    Preconditions.requireNonNull(name, "name");
    for (int i = 0; i < steps.size(); i++) {
      if (steps.get(i).name().equals(name)) {
        return i;
      }
    }
    return -1;
  }

  /**
   * Returns the number of steps declared.
   *
   * @return the plan's step count, which bounds neither the run's step count nor its cost — a
   *     branch or a revisited step makes the execution graph larger than the plan
   */
  public int size() {
    return steps.size();
  }

  /**
   * Returns every capability any step of this plan may exercise.
   *
   * <p>This is what run admission evaluates against the principal's grants, so the intersection is
   * computed once at admission rather than rediscovered per step.
   *
   * @return the union of every step's declared capabilities
   */
  public Set<String> declaredCapabilities() {
    final java.util.TreeSet<String> all = new java.util.TreeSet<>();
    for (final Step step : steps) {
      all.addAll(step.requiredCapabilities());
    }
    return java.util.Collections.unmodifiableSortedSet(all);
  }

  /**
   * Returns the plan's total declared step budget.
   *
   * @return the sum of every step's cost ceiling, which is an upper bound only for a straight-line
   *     run
   */
  public long declaredBudgetMicros() {
    long total = 0L;
    for (final Step step : steps) {
      total += step.budgetMicros();
    }
    return total;
  }

  /**
   * Reports whether any step of this plan may perform an irreversible action.
   *
   * @return true when at least one step is declared non-idempotent
   */
  public boolean hasIrreversibleSteps() {
    for (final Step step : steps) {
      if (!step.idempotent()) {
        return true;
      }
    }
    return false;
  }

  /**
   * Enforces the plan contract.
   *
   * <p>Three classes of check, each of which prevents a distinct mid-run failure:
   *
   * <ul>
   *   <li>Duplicate names would make {@link #indexOf} ambiguous and a branch target
   *       non-deterministic.
   *   <li>A dangling input or branch reference would surface as a null at the moment the step runs
   *       — possibly hours in, after real money has been spent.
   *   <li>A plan longer than the step bound can never complete, so admitting it would guarantee a
   *       {@code TERMINATED_BOUND} that a publication-time check could have prevented.
   * </ul>
   */
  private static void validate(
      final List<Step> steps, final RunBounds bounds, final RestartPolicy restartPolicy) {

    final Set<String> names = new LinkedHashSet<>();
    for (final Step step : steps) {
      if (!names.add(step.name())) {
        throw new IllegalArgumentException("duplicate step name: " + step.name());
      }
    }

    final Map<String, Step> byName = new HashMap<>();
    for (final Step step : steps) {
      byName.put(step.name(), step);
    }

    for (final Step step : steps) {
      switch (step) {
        case Step.Pipeline pipeline -> requireRefs(pipeline.inputRefs(), byName, step.name());
        case Step.Plugin plugin -> requireRefs(plugin.inputRefs(), byName, step.name());
        case Step.Condition condition -> requireRef(condition.subjectRef(), byName, step.name());
        case Step.Branch branch -> {
          requireRef(branch.subjectRef(), byName, step.name());
          requireTarget(branch.whenTrue(), byName, step.name());
          requireTarget(branch.whenFalse(), byName, step.name());
        }
        case Step.Wait ignored -> {
          // No references to check: a wait depends on nothing and produces nothing.
        }
      }
    }

    if (steps.size() > bounds.maxSteps()) {
      throw new IllegalArgumentException(
          "plan declares " + steps.size() + " steps but the step bound is " + bounds.maxSteps());
    }

    if (restartPolicy.strategy() == SupervisionStrategy.RESTART_FROM
        && !byName.containsKey(restartPolicy.restartFromStep())) {
      throw new IllegalArgumentException(
          "restartFromStep names no step in this plan: " + restartPolicy.restartFromStep());
    }
    if (restartPolicy.strategy() == SupervisionStrategy.COMPENSATE
        && !byName.containsKey(restartPolicy.compensationStep())) {
      throw new IllegalArgumentException(
          "compensationStep names no step in this plan: " + restartPolicy.compensationStep());
    }
  }

  private static void requireRefs(
      final List<String> refs, final Map<String, Step> byName, final String owner) {
    for (final String ref : refs) {
      requireRef(ref, byName, owner);
    }
  }

  private static void requireRef(
      final String ref, final Map<String, Step> byName, final String owner) {
    if (!byName.containsKey(ref)) {
      throw new IllegalArgumentException(
          "step '" + owner + "' references unknown step '" + ref + "'");
    }
  }

  private static void requireTarget(
      final String target, final Map<String, Step> byName, final String owner) {
    if (!byName.containsKey(target)) {
      throw new IllegalArgumentException(
          "branch '" + owner + "' targets unknown step '" + target + "'");
    }
  }
}

package io.reliabilityai.gateway.dataplane.agent.api;

import java.util.List;
import java.util.Optional;

/**
 * A read model over the steps of a run.
 *
 * <p><b>Derived, not a second store.</b> Every answer here is computed from the run's history.
 * There is deliberately no write method: a step repository that could be written to independently
 * of the history would be a second source of truth about what a run did, and the two would
 * eventually disagree in a way no query could detect.
 *
 * <p>It exists because "list this run's steps and their statuses" is the single most common
 * operator question, and folding the whole history at every call site is both wasteful and easy to
 * get subtly wrong — particularly around retries, where the same plan step appears several times.
 */
public interface StepRepository {

  /**
   * One step's place in the execution graph.
   *
   * @param stepId the derived identity
   * @param stepName the plan-local name
   * @param kind the step kind
   * @param attempt the 1-based attempt number
   * @param status the folded status
   * @param costMicros what it cost
   * @param detail a short explanation for a non-successful status, empty otherwise
   */
  record StepView(
      StepId stepId,
      String stepName,
      StepKind kind,
      int attempt,
      StepStatus status,
      long costMicros,
      String detail) {}

  /**
   * Returns every step of a run, in execution order.
   *
   * <p>A retried step appears once per attempt — the history records four entries for a step that
   * succeeded on its fourth try, and flattening them would hide exactly the thing an operator is
   * looking for.
   *
   * @param runId the run
   * @return the step views, oldest first; empty for an unknown run
   */
  List<StepView> stepsOf(RunId runId);

  /**
   * Returns one step's view.
   *
   * @param stepId the step
   * @return the view, or empty when the run or step is unknown
   */
  Optional<StepView> stepView(StepId stepId);

  /**
   * Returns the status of the most recent attempt at a named plan step.
   *
   * @param runId the run
   * @param stepName the plan-local step name
   * @return the latest status, or empty when the step has not been reached
   */
  Optional<StepStatus> latestStatus(RunId runId, String stepName);

  /**
   * Counts the steps of a run that reached a given status.
   *
   * @param runId the run
   * @param status the status to count
   * @return how many step attempts ended in that status
   */
  int countByStatus(RunId runId, StepStatus status);
}

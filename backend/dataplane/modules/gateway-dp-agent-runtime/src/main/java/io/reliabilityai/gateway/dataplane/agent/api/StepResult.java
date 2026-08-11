package io.reliabilityai.gateway.dataplane.agent.api;

import io.reliabilityai.gateway.common.Preconditions;
import java.time.Instant;
import java.util.Optional;

/**
 * What executing one step produced.
 *
 * <p>Every variant carries {@code costMicros}, including the failures. AD-025 §65.3: a failed step,
 * an expired approval, a cancelled run and an abandoned interrupted step all cost money, because
 * the provider charged for them. There is no "we do not bill failures" rule here — surfacing the
 * cost honestly is what lets an operator see that a flaky plan is an expensive plan.
 */
public sealed interface StepResult
    permits StepResult.Succeeded, StepResult.Failed, StepResult.Cancelled, StepResult.Deferred {

  /**
   * Returns the step this is the result of.
   *
   * @return the step identity
   */
  StepId stepId();

  /**
   * Returns what the step cost.
   *
   * @return spend in micros as reported by the layer that measured it, never computed here
   */
  long costMicros();

  /**
   * Returns the status this result puts the step in.
   *
   * @return the step status
   */
  StepStatus status();

  /**
   * Reports whether the run may proceed to another step.
   *
   * @return true when this result does not itself stop the run
   */
  default boolean progresses() {
    return this instanceof Succeeded || this instanceof Deferred;
  }

  /**
   * A step that executed and produced a value.
   *
   * @param stepId the step identity
   * @param value the recorded result value, bounded by the executor
   * @param valueDigest a stable digest of the value, used for replay comparison and audit
   * @param artifact the tool artifact produced, present only for {@link StepKind#PLUGIN} steps
   * @param costMicros the reported spend
   * @param tainted whether this result carries untrusted content into the run's taint state
   */
  record Succeeded(
      StepId stepId,
      String value,
      String valueDigest,
      Optional<ToolResultArtifact> artifact,
      long costMicros,
      boolean tainted)
      implements StepResult {

    /**
     * Validates the result.
     *
     * @param stepId the step identity
     * @param value the recorded value
     * @param valueDigest the value digest
     * @param artifact the tool artifact, if any
     * @param costMicros the reported spend
     * @param tainted the taint flag
     */
    public Succeeded {
      Preconditions.requireNonNull(stepId, "stepId");
      Preconditions.requireNonNull(value, "value");
      Preconditions.requireNonBlank(valueDigest, "valueDigest");
      Preconditions.requireNonNull(artifact, "artifact");
      Preconditions.requireNonNegative(costMicros, "costMicros");
    }

    @Override
    public StepStatus status() {
      return StepStatus.SUCCEEDED;
    }
  }

  /**
   * A step that executed and failed.
   *
   * @param stepId the step identity
   * @param failure the classified failure
   * @param reason a short operator-facing explanation
   * @param costMicros the spend incurred before failing, which is real and is not refunded
   */
  record Failed(StepId stepId, FailureClass failure, String reason, long costMicros)
      implements StepResult {

    /**
     * Validates the result.
     *
     * @param stepId the step identity
     * @param failure the classified failure
     * @param reason the explanation
     * @param costMicros the spend incurred
     */
    public Failed {
      Preconditions.requireNonNull(stepId, "stepId");
      Preconditions.requireNonNull(failure, "failure");
      Preconditions.requireNonBlank(reason, "reason");
      Preconditions.requireNonNegative(costMicros, "costMicros");
    }

    @Override
    public StepStatus status() {
      return failure == FailureClass.STEP_TIMEOUT ? StepStatus.TIMED_OUT : StepStatus.FAILED;
    }
  }

  /**
   * A step cancelled before it could finish.
   *
   * @param stepId the step identity
   * @param cause why it was cancelled
   * @param costMicros the spend incurred before cancelling
   */
  record Cancelled(StepId stepId, CancellationCause cause, long costMicros) implements StepResult {

    /**
     * Validates the result.
     *
     * @param stepId the step identity
     * @param cause the cancellation cause
     * @param costMicros the spend incurred
     */
    public Cancelled {
      Preconditions.requireNonNull(stepId, "stepId");
      Preconditions.requireNonNull(cause, "cause");
      Preconditions.requireNonNegative(costMicros, "costMicros");
    }

    @Override
    public StepStatus status() {
      return StepStatus.CANCELLED;
    }
  }

  /**
   * A step that parked the run until a recorded instant.
   *
   * <p>Produced by {@link StepKind#WAIT}. The run leaves memory entirely; {@code wakeAt} is a
   * recorded value in the history, not a live timer, so no node holds anything while it elapses.
   *
   * @param stepId the step identity
   * @param wakeAt the instant the run becomes claimable again
   */
  record Deferred(StepId stepId, Instant wakeAt) implements StepResult {

    /**
     * Validates the result.
     *
     * @param stepId the step identity
     * @param wakeAt the recorded wake instant
     */
    public Deferred {
      Preconditions.requireNonNull(stepId, "stepId");
      Preconditions.requireNonNull(wakeAt, "wakeAt");
    }

    @Override
    public long costMicros() {
      return 0L;
    }

    @Override
    public StepStatus status() {
      // Succeeded, not scheduled. A wait's entire job is to decide when to wake, and it has done
      // it;
      // the pause that follows belongs to the run, not to an unfinished step.
      return StepStatus.SUCCEEDED;
    }
  }
}

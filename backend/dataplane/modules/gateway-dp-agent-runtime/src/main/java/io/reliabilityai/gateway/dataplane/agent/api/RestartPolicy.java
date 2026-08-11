package io.reliabilityai.gateway.dataplane.agent.api;

import io.reliabilityai.gateway.common.Preconditions;
import java.time.Duration;

/**
 * A run's declared failure-handling policy (AD-025 AGT-16, SVC-1).
 *
 * <p><b>Mandatory, with no opt-out.</b> Akka's lesson is that supervision configured per-actor is
 * supervision usually omitted; OTP's is that supervision baked into the runtime is supervision
 * always present. Every run gets one of these, and a plan that declares nothing gets {@link
 * #STRICT}.
 *
 * <p>The policy is <em>data</em>, never code (AD-025 §68.3). A strategy expressed as a lambda could
 * not be authorized at admission, recorded in the history, or replayed — and a supervision rule
 * that governance cannot see is a supervision rule that cannot be governed.
 *
 * @param strategy what to do when a step fails
 * @param retryMode how a retry is delayed, when the strategy retries
 * @param maxAttemptsPerStep total attempts per step, including the first
 * @param baseDelay the retry schedule's base
 * @param maxDelay the retry schedule's ceiling; never below {@code baseDelay}
 * @param intensity the run-wide restart bound
 * @param restartFromStep for {@link SupervisionStrategy#RESTART_FROM}, the step name to rewind to;
 *     empty otherwise
 * @param compensationStep for {@link SupervisionStrategy#COMPENSATE}, the compensating step name;
 *     empty otherwise
 */
public record RestartPolicy(
    SupervisionStrategy strategy,
    RetryMode retryMode,
    int maxAttemptsPerStep,
    Duration baseDelay,
    Duration maxDelay,
    RestartIntensity intensity,
    String restartFromStep,
    String compensationStep) {

  /** The default a plan gets when it declares nothing: fail the run, retry nothing. */
  public static final RestartPolicy STRICT =
      new RestartPolicy(
          SupervisionStrategy.FAIL_RUN,
          RetryMode.NONE,
          1,
          Duration.ZERO,
          Duration.ZERO,
          RestartIntensity.NONE,
          "",
          "");

  /**
   * A common transient-tolerant policy: three attempts, exponential backoff, OTP-default intensity.
   */
  public static final RestartPolicy RESILIENT =
      new RestartPolicy(
          SupervisionStrategy.RETRY_STEP,
          RetryMode.EXPONENTIAL,
          3,
          Duration.ofMillis(200),
          Duration.ofSeconds(30),
          RestartIntensity.DEFAULT,
          "",
          "");

  /**
   * Validates the policy, refusing combinations that cannot be executed.
   *
   * @param strategy what to do when a step fails
   * @param retryMode how a retry is delayed
   * @param maxAttemptsPerStep total attempts per step
   * @param baseDelay the retry schedule's base
   * @param maxDelay the retry schedule's ceiling
   * @param intensity the run-wide restart bound
   * @param restartFromStep the rewind target, or empty
   * @param compensationStep the compensation target, or empty
   */
  public RestartPolicy {
    Preconditions.requireNonNull(strategy, "strategy");
    Preconditions.requireNonNull(retryMode, "retryMode");
    Preconditions.requireNonNull(baseDelay, "baseDelay");
    Preconditions.requireNonNull(maxDelay, "maxDelay");
    Preconditions.requireNonNull(intensity, "intensity");
    Preconditions.requireNonNull(restartFromStep, "restartFromStep");
    Preconditions.requireNonNull(compensationStep, "compensationStep");
    if (maxAttemptsPerStep < 1) {
      throw new IllegalArgumentException(
          "maxAttemptsPerStep must be >= 1, was " + maxAttemptsPerStep);
    }
    if (baseDelay.isNegative() || maxDelay.isNegative()) {
      throw new IllegalArgumentException("delays must be non-negative");
    }
    if (maxDelay.compareTo(baseDelay) < 0) {
      throw new IllegalArgumentException("maxDelay " + maxDelay + " below baseDelay " + baseDelay);
    }
    // A policy that says "restart from" without naming a target is not a policy, it is a bug that
    // would surface as a NullPointerException at the worst possible moment — during failure
    // handling.
    if (strategy == SupervisionStrategy.RESTART_FROM && restartFromStep.isBlank()) {
      throw new IllegalArgumentException("RESTART_FROM requires restartFromStep");
    }
    if (strategy == SupervisionStrategy.COMPENSATE && compensationStep.isBlank()) {
      throw new IllegalArgumentException("COMPENSATE requires compensationStep");
    }
    if (strategy == SupervisionStrategy.RETRY_STEP && !retryMode.retries()) {
      throw new IllegalArgumentException("RETRY_STEP requires a retryMode that retries");
    }
  }

  /**
   * Returns a fresh retry budget for one step under this policy.
   *
   * @return an unconsumed budget sized by {@link #maxAttemptsPerStep()}
   */
  public RetryBudget newRetryBudget() {
    return RetryBudget.of(maxAttemptsPerStep);
  }

  /**
   * Computes the delay before a given attempt.
   *
   * @param attempt the 1-based attempt number being scheduled
   * @return the delay, clamped to {@link #maxDelay()}
   */
  public Duration delayFor(final int attempt) {
    return retryMode.delayFor(attempt, baseDelay, maxDelay);
  }
}

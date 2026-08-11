package io.reliabilityai.gateway.dataplane.reliability.api;

/**
 * The shared retry-budget accounting seam (Doc 20 §14/§30, RE-D8). A coordinated,
 * bounded-consistency budget across the request population — <b>not</b> a naive shared mutable
 * counter. Each retry/hedge attempt attempts to consume one unit; when the budget is exhausted,
 * retries stop platform-wide so requests surface rather than amplify (no retry storms).
 * Implementations are thread-safe.
 */
public interface RetryBudgetPort {

  /**
   * Attempts to consume one retry unit from the shared budget (Doc 20 §14).
   *
   * @return {@code true} if a retry unit was available; {@code false} when the budget is exhausted
   */
  boolean tryConsumeRetry();
}

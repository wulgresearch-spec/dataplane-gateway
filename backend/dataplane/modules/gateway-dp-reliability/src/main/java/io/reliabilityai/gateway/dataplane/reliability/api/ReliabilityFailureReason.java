package io.reliabilityai.gateway.dataplane.reliability.api;

/**
 * The typed, content-free reason a request was surfaced instead of succeeding (Doc 20 RE-INV/§18).
 */
public enum ReliabilityFailureReason {
  /** The shared retry budget is exhausted platform-wide (Doc 20 §14, RE-D8). */
  RETRY_BUDGET_EXHAUSTED("retry-budget-exhausted"),
  /** The per-request attempt ceiling was reached (Doc 20 §14). */
  ATTEMPT_CEILING_REACHED("attempt-ceiling-reached"),
  /** The total deadline elapsed (Doc 20 §15/§9). */
  DEADLINE_EXCEEDED("deadline-exceeded"),
  /** Every candidate in the immutable list was exhausted (Doc 20 §9, RE-D3). */
  CANDIDATES_EXHAUSTED("candidates-exhausted"),
  /** A non-retryable provider error was surfaced (Doc 20 §13). */
  NON_RETRYABLE("non-retryable"),
  /** The reliability policy snapshot was unavailable — fail closed (Doc 20 §16, AD-022). */
  POLICY_UNAVAILABLE("policy-unavailable"),
  /**
   * The routing decision's TTL lapsed; a fresh decision must be requested, never executed stale
   * (Doc 19 §30.1 RC-6, PR-A15).
   */
  ROUTING_DECISION_STALE("routing-decision-stale");

  private final String code;

  ReliabilityFailureReason(final String code) {
    this.code = code;
  }

  /**
   * The content-free reason code.
   *
   * @return the code
   */
  public String code() {
    return code;
  }
}

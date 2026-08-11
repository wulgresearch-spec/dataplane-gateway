package io.reliabilityai.gateway.dataplane.governance.api;

import io.reliabilityai.gateway.common.Preconditions;

/**
 * What one request would have done under two different generations of policy.
 *
 * <p>The question a governance change actually raises is never "is the new policy valid" — a
 * compiler can answer that — but "who does it break". This record answers it one request at a time:
 * the same question, put to the generation in force and to a candidate, with the two verdicts side
 * by side.
 *
 * <p>Neither evaluation has any effect. No audit is emitted under an enforcement identity, no
 * counter moves, and nothing is installed. Simulating a policy that would deny every request is
 * safe, which is exactly the property that makes it worth doing before the change goes live rather
 * than after.
 *
 * @param baseline what the generation in force decides
 * @param candidate what the proposed generation would decide
 */
public record SimulationResult(PolicyDecision baseline, PolicyDecision candidate) {

  /** Validates the pairing. */
  public SimulationResult {
    Preconditions.requireNonNull(baseline, "baseline");
    Preconditions.requireNonNull(candidate, "candidate");
  }

  /**
   * Whether the candidate changes this request's verdict.
   *
   * @return {@code true} when the two verdicts differ
   */
  public boolean changed() {
    return baseline.verdict() != candidate.verdict();
  }

  /**
   * Whether the candidate refuses a request the current generation admits — the transition an
   * operator needs to see before rolling out, because it is the one that turns into a support
   * ticket.
   *
   * @return {@code true} when admission becomes refusal
   */
  public boolean newlyDenied() {
    return baseline.admits() && !candidate.admits();
  }

  /**
   * Whether the candidate admits a request the current generation refuses.
   *
   * @return {@code true} when refusal becomes admission
   */
  public boolean newlyAdmitted() {
    return !baseline.admits() && candidate.admits();
  }
}

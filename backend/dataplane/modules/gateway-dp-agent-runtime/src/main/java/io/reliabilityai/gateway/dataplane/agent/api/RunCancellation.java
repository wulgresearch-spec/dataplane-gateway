package io.reliabilityai.gateway.dataplane.agent.api;

import io.reliabilityai.gateway.common.Preconditions;
import java.time.Instant;

/**
 * A cancellation request against a run (AD-025 §32).
 *
 * <p><b>Cancellation cannot be refused</b> and is accepted from every non-terminal state (AD-025
 * SM-7). A run has no veto and no cleanup tail: a post-cancellation cleanup step would be an
 * unbounded appendix on a run that may be being cancelled precisely because it spent too much.
 * Anything that must happen on cancellation is the session's or the tool's compensation, which
 * AD-024 already scopes.
 *
 * @param cause why the run is being cancelled
 * @param reason a short operator-facing explanation, never blank
 * @param requestedAt when the cancellation was recorded
 */
public record RunCancellation(CancellationCause cause, String reason, Instant requestedAt) {

  /**
   * Validates the cancellation.
   *
   * @param cause why the run is being cancelled
   * @param reason the operator-facing explanation
   * @param requestedAt the recorded instant
   */
  public RunCancellation {
    Preconditions.requireNonNull(cause, "cause");
    Preconditions.requireNonBlank(reason, "reason");
    Preconditions.requireNonNull(requestedAt, "requestedAt");
  }

  /**
   * Creates a cancellation.
   *
   * @param cause why the run is being cancelled
   * @param reason the operator-facing explanation
   * @param requestedAt the recorded instant
   * @return the cancellation
   */
  public static RunCancellation of(
      final CancellationCause cause, final String reason, final Instant requestedAt) {
    return new RunCancellation(cause, reason, requestedAt);
  }

  /**
   * Returns the terminal reason this cancellation ends the run with.
   *
   * @return the mapped terminal reason
   */
  public TerminalReason terminalReason() {
    return cause.terminalReason();
  }

  /**
   * Reports whether work performed after this point may be billed.
   *
   * <p>Always false, by AD-025 AGT-28. Work already performed <em>is</em> billed — a tool that
   * already ran was already charged by the layer that ran it, and pretending otherwise would
   * misreport cost.
   *
   * @return false, always
   */
  public boolean billsSubsequentWork() {
    return false;
  }
}

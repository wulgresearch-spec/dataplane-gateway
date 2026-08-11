package io.reliabilityai.gateway.dataplane.agent.api;

/**
 * Why a run was cancelled.
 *
 * <p>Every cause becomes a durable history event. A cancellation that is not recorded is a run that
 * stopped for no reason anybody can later explain, which is exactly the outcome the audit trail
 * exists to prevent.
 */
public enum CancellationCause {

  /** The caller asked. */
  USER(TerminalReason.CANCELLED_CALLER),

  /** The wall-clock bound expired. */
  TIMEOUT(TerminalReason.TERMINATED_TIMEOUT),

  /** The supervisor gave up: restart intensity, or a strategy that terminates. */
  SUPERVISOR(TerminalReason.FAILED_RESTART_INTENSITY),

  /** Policy changed mid-run and revoked the run's authority. */
  POLICY(TerminalReason.TERMINATED_POLICY),

  /** A parent run ended, so its children end with it (AD-025 AGT-28). */
  PARENT(TerminalReason.CANCELLED_PARENT),

  /** A tool failed in a way that makes continuing pointless. */
  PLUGIN_FAILURE(TerminalReason.FAILED_STEP),

  /** A provider failed in a way that makes continuing pointless. */
  PROVIDER_FAILURE(TerminalReason.FAILED_STEP),

  /** An operator intervened. */
  OPERATOR(TerminalReason.CANCELLED_OPERATOR),

  /** The tenant was suspended. */
  TENANT(TerminalReason.CANCELLED_TENANT);

  private final TerminalReason reason;

  CancellationCause(final TerminalReason reason) {
    this.reason = reason;
  }

  /**
   * Returns the terminal reason a run cancelled for this cause records.
   *
   * @return the mapped terminal reason
   */
  public TerminalReason terminalReason() {
    return reason;
  }

  /**
   * Reports whether the cancellation came from outside the runtime.
   *
   * <p>Used only for metrics dimensioning: an externally-cancelled run is a product signal, whereas
   * a self-cancelled run is an operational one.
   *
   * @return true for caller, parent, operator and tenant causes
   */
  public boolean external() {
    return this == USER || this == PARENT || this == OPERATOR || this == TENANT;
  }
}

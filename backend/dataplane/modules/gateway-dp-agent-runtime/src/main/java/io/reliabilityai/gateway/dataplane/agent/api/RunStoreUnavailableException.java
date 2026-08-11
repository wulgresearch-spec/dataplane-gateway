package io.reliabilityai.gateway.dataplane.agent.api;

/**
 * The Run Store could not be reached.
 *
 * <p>Thrown rather than returned as an empty history, always (AD-025 HSC-8). An empty history is
 * indistinguishable from a new run, so a store that reported unavailability by returning nothing
 * would cause a recovering node to restart a live run from step one — repeating every side effect
 * and paying for the whole run again. Failing loudly is the cheap option.
 *
 * <p>The runtime's response is to stall the run, never to proceed unrecorded (AD-025 AGT-14,
 * §44.4). An executor that carried on would be executing a run whose history is a lie.
 */
public final class RunStoreUnavailableException extends RuntimeException {

  private static final long serialVersionUID = 1L;

  /**
   * Creates the exception.
   *
   * @param message what could not be reached and why
   */
  public RunStoreUnavailableException(final String message) {
    super(message);
  }

  /**
   * Creates the exception with a cause.
   *
   * @param message what could not be reached and why
   * @param cause the underlying failure
   */
  public RunStoreUnavailableException(final String message, final Throwable cause) {
    super(message, cause);
  }
}

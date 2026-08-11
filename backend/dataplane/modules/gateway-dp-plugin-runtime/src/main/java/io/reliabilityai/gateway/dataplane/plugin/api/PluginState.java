package io.reliabilityai.gateway.dataplane.plugin.api;

/**
 * The lifecycle state of a registered plugin (Doc 28 §D).
 *
 * <p>Only {@link #READY} admits execution. Every other state — including {@link #INITIALIZING},
 * which looks transiently harmless — refuses it, so a plugin can never serve a request before its
 * dependencies are up or after it has begun shutting down. The legal transitions are enforced by
 * {@code PluginLifecycle}, not by this enum.
 */
public enum PluginState {

  /** Verified and bound, not yet initialized. Refuses execution. */
  REGISTERED,

  /** Running its start hook. Refuses execution. */
  INITIALIZING,

  /** Started and healthy. The only state that admits execution. */
  READY,

  /** Running its stop hook; in-flight work is being cancelled. Refuses execution. */
  STOPPING,

  /** Stopped cleanly. Refuses execution; may be started again. */
  STOPPED,

  /** Start or execution failed terminally. Refuses execution until reloaded. */
  FAILED,

  /** Administratively disabled through the governed config toggle (Doc 28 HRC-5). */
  DISABLED;

  /**
   * Whether a plugin in this state may be invoked.
   *
   * @return true only for {@link #READY}
   */
  public boolean admitsExecution() {
    return this == READY;
  }

  /**
   * Whether this is an end state that only a reload or an explicit start can leave.
   *
   * @return true for {@link #STOPPED}, {@link #FAILED} and {@link #DISABLED}
   */
  public boolean terminal() {
    return this == STOPPED || this == FAILED || this == DISABLED;
  }
}

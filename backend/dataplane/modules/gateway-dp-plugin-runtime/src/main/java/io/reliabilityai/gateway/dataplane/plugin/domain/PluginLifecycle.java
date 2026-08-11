package io.reliabilityai.gateway.dataplane.plugin.domain;

import io.reliabilityai.gateway.common.Preconditions;
import io.reliabilityai.gateway.dataplane.plugin.api.PluginState;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

/**
 * The plugin lifecycle state machine (Doc 28 §D).
 *
 * <p>A pure function from (state, state) to legality, with no fields and no I/O. Keeping it
 * separate from the registry means the legal transitions can be exhaustively tested without
 * constructing a runtime, and means the registry cannot quietly invent a transition inline.
 *
 * <p>The table is deliberately restrictive. In particular {@code REGISTERED → READY} is illegal:
 * reaching READY without passing through INITIALIZING would mean a plugin serving traffic before
 * its start hook ran. And nothing transitions <em>out of</em> FAILED except through reload, so a
 * plugin that failed to start cannot drift back into service on its own.
 */
public final class PluginLifecycle {

  private static final Map<PluginState, Set<PluginState>> LEGAL = legalTransitions();

  private PluginLifecycle() {}

  private static Map<PluginState, Set<PluginState>> legalTransitions() {
    final Map<PluginState, Set<PluginState>> legal = new EnumMap<>(PluginState.class);
    // Bound but not started. It can begin starting, be disabled, or be torn down.
    legal.put(
        PluginState.REGISTERED,
        EnumSet.of(PluginState.INITIALIZING, PluginState.DISABLED, PluginState.STOPPED));
    // Running its start hook: it either comes up, fails, or is stopped mid-start.
    legal.put(
        PluginState.INITIALIZING,
        EnumSet.of(PluginState.READY, PluginState.FAILED, PluginState.STOPPING));
    // Serving. A terminal execution failure can knock it straight to FAILED.
    legal.put(
        PluginState.READY,
        EnumSet.of(PluginState.STOPPING, PluginState.FAILED, PluginState.DISABLED));
    // Shutting down: it lands stopped, or its stop hook fails.
    legal.put(PluginState.STOPPING, EnumSet.of(PluginState.STOPPED, PluginState.FAILED));
    // Stopped cleanly: it can be started again or disabled.
    legal.put(PluginState.STOPPED, EnumSet.of(PluginState.INITIALIZING, PluginState.DISABLED));
    // Failed terminally. Only an explicit re-registration or reload gets out of here, and both
    // replace
    // the binding rather than transitioning it, so FAILED has no outgoing edge at all.
    legal.put(PluginState.FAILED, EnumSet.noneOf(PluginState.class));
    // Administratively disabled (Doc 28 HRC-5). Re-enabling returns it to REGISTERED, from which
    // the
    // ordinary start path applies — it never jumps straight back to READY.
    legal.put(PluginState.DISABLED, EnumSet.of(PluginState.REGISTERED));
    return Map.copyOf(legal);
  }

  /**
   * Whether a transition is legal.
   *
   * @param from the current state
   * @param to the proposed state
   * @return true if the transition is permitted
   */
  public static boolean permits(final PluginState from, final PluginState to) {
    Preconditions.requireNonNull(from, "from");
    Preconditions.requireNonNull(to, "to");
    return LEGAL.getOrDefault(from, Set.of()).contains(to);
  }

  /**
   * The states reachable in one step.
   *
   * @param from the current state
   * @return the legal next states, possibly empty
   */
  public static Set<PluginState> nextStates(final PluginState from) {
    Preconditions.requireNonNull(from, "from");
    return Set.copyOf(LEGAL.getOrDefault(from, Set.of()));
  }

  /**
   * Asserts a transition is legal.
   *
   * @param from the current state
   * @param to the proposed state
   * @return the new state
   * @throws IllegalStateException if the transition is not permitted
   */
  public static PluginState transition(final PluginState from, final PluginState to) {
    if (!permits(from, to)) {
      throw new IllegalStateException("illegal plugin transition " + from + " -> " + to);
    }
    return to;
  }
}

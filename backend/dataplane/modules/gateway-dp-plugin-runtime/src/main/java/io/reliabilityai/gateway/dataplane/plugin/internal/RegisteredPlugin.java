package io.reliabilityai.gateway.dataplane.plugin.internal;

import io.reliabilityai.gateway.common.Preconditions;
import io.reliabilityai.gateway.dataplane.plugin.api.Plugin;
import io.reliabilityai.gateway.dataplane.plugin.api.PluginDescriptor;
import io.reliabilityai.gateway.dataplane.plugin.api.PluginHealth;
import io.reliabilityai.gateway.dataplane.plugin.api.PluginId;
import io.reliabilityai.gateway.dataplane.plugin.api.PluginManifest;
import io.reliabilityai.gateway.dataplane.plugin.api.PluginState;
import io.reliabilityai.gateway.dataplane.plugin.domain.PluginLifecycle;
import java.time.Instant;

/**
 * One bound plugin: its verified manifest, its instance, and its mutable lifecycle state.
 *
 * <p>The only mutable thing in the runtime, and deliberately the only one. Everything a caller sees
 * is an immutable {@link PluginDescriptor} taken from here, so no caller can hold a reference
 * through which state changes underneath it.
 *
 * <p>State transitions are guarded by {@link PluginLifecycle} and applied under this object's own
 * monitor. The request path never takes that lock — it reads the volatile state field — so an
 * operator command stopping a plugin cannot block a dispatch already in flight.
 */
public final class RegisteredPlugin {

  private final PluginManifest manifest;
  private final Plugin instance;
  private final String digestHex;
  private final Instant registeredAt;
  private final long generation;

  private volatile PluginState state = PluginState.REGISTERED;
  private volatile PluginHealth health = PluginHealth.UNKNOWN;

  /**
   * Binds a verified plugin.
   *
   * @param manifest the verified manifest
   * @param instance the plugin instance
   * @param digestHex the verified content digest
   * @param registeredAt when it was bound, from the injected clock
   * @param generation the reload generation
   */
  public RegisteredPlugin(
      final PluginManifest manifest,
      final Plugin instance,
      final String digestHex,
      final Instant registeredAt,
      final long generation) {
    this.manifest = Preconditions.requireNonNull(manifest, "manifest");
    this.instance = Preconditions.requireNonNull(instance, "instance");
    this.digestHex = Preconditions.requireNonBlank(digestHex, "digestHex");
    this.registeredAt = Preconditions.requireNonNull(registeredAt, "registeredAt");
    this.generation = Preconditions.requireNonNegative(generation, "generation");
  }

  /**
   * The verified manifest.
   *
   * @return the manifest
   */
  public PluginManifest manifest() {
    return manifest;
  }

  /**
   * The plugin id.
   *
   * @return the id
   */
  public PluginId id() {
    return manifest.id();
  }

  /**
   * The bound instance.
   *
   * @return the instance
   */
  public Plugin instance() {
    return instance;
  }

  /**
   * The reload generation.
   *
   * @return the generation
   */
  public long generation() {
    return generation;
  }

  /**
   * The current lifecycle state.
   *
   * @return the state
   */
  public PluginState state() {
    return state;
  }

  /**
   * The last observed health.
   *
   * @return the health
   */
  public PluginHealth health() {
    return health;
  }

  /**
   * Records an observed health verdict. Advisory only; never gates dispatch.
   *
   * @param observed the verdict
   */
  public void observeHealth(final PluginHealth observed) {
    this.health = Preconditions.requireNonNull(observed, "observed");
  }

  /**
   * Applies a lifecycle transition if it is legal and the plugin is still in the expected state.
   *
   * <p>Compare-and-set rather than a plain assignment: two operator commands arriving together must
   * not both believe they moved the plugin, or one of them will go on to run a start hook for a
   * plugin the other already stopped.
   *
   * @param expected the state the caller believes the plugin is in
   * @param next the state to move to
   * @return true if this call performed the transition
   * @throws IllegalStateException if the transition is not legal
   */
  public synchronized boolean transition(final PluginState expected, final PluginState next) {
    if (state != expected) {
      return false;
    }
    state = PluginLifecycle.transition(expected, next);
    return true;
  }

  /**
   * Forces the plugin into FAILED from wherever it is.
   *
   * <p>The one transition that bypasses the table, because a plugin that has crashed is already in
   * whatever state it crashed in and refusing to record that would leave a dead plugin advertised
   * as READY.
   */
  public synchronized void markFailed() {
    state = PluginState.FAILED;
  }

  /**
   * An immutable view of this plugin right now.
   *
   * @return the descriptor
   */
  public PluginDescriptor describe() {
    return new PluginDescriptor(manifest, state, health, digestHex, registeredAt, generation);
  }
}

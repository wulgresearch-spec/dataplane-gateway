package io.reliabilityai.gateway.dataplane.plugin.api;

import io.reliabilityai.gateway.common.Preconditions;
import java.time.Instant;

/**
 * An immutable point-in-time view of one registered plugin (Doc 28 §6).
 *
 * <p>A descriptor is what {@code list()} and {@code snapshot()} hand back. It is a value, not a
 * live handle: an observer holding one cannot flip a plugin's state, and cannot see a later state
 * change through it either. That is deliberate — a mutable view shared across threads is how an
 * operator command and a request-path dispatch end up disagreeing about whether a plugin is
 * enabled.
 *
 * @param manifest the verified manifest
 * @param state the lifecycle state at the moment this descriptor was taken
 * @param health the last observed health
 * @param digestHex the verified content digest, in hex
 * @param registeredAt when the plugin was bound
 * @param generation the reload generation; incremented on every successful reload
 */
public record PluginDescriptor(
    PluginManifest manifest,
    PluginState state,
    PluginHealth health,
    String digestHex,
    Instant registeredAt,
    long generation) {

  /** Compact constructor validating the view. */
  public PluginDescriptor {
    Preconditions.requireNonNull(manifest, "manifest");
    Preconditions.requireNonNull(state, "state");
    Preconditions.requireNonNull(health, "health");
    Preconditions.requireNonBlank(digestHex, "digestHex");
    Preconditions.requireNonNull(registeredAt, "registeredAt");
    Preconditions.requireNonNegative(generation, "generation");
  }

  /**
   * The plugin's id.
   *
   * @return the id
   */
  public PluginId id() {
    return manifest.id();
  }

  /**
   * The plugin's pinned version.
   *
   * @return the version
   */
  public PluginVersion version() {
    return manifest.version();
  }
}

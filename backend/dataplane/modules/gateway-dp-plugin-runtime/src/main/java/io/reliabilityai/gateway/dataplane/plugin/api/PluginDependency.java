package io.reliabilityai.gateway.dataplane.plugin.api;

import io.reliabilityai.gateway.common.Preconditions;

/**
 * A declared dependency on another plugin (Doc 28 §14).
 *
 * <p>The dependency drives start ordering, stop ordering and version compatibility. It is
 * <b>not</b> a call edge: Doc 28 §30.1 gives a plugin no capability to invoke another plugin, so
 * this expresses "start me after that one and refuse if its version is wrong", never "let me call
 * it".
 *
 * @param pluginId the plugin depended upon
 * @param minimumVersion the lowest version that satisfies this dependency
 */
public record PluginDependency(PluginId pluginId, PluginVersion minimumVersion) {

  /** Compact constructor validating both components. */
  public PluginDependency {
    Preconditions.requireNonNull(pluginId, "pluginId");
    Preconditions.requireNonNull(minimumVersion, "minimumVersion");
  }

  /**
   * Whether a resolved version satisfies this dependency.
   *
   * @param candidate the version actually registered
   * @return true if the candidate is compatible and recent enough
   */
  public boolean satisfiedBy(final PluginVersion candidate) {
    return candidate != null && candidate.satisfies(minimumVersion);
  }
}

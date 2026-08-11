package io.reliabilityai.gateway.dataplane.plugin.domain;

import io.reliabilityai.gateway.common.Preconditions;
import io.reliabilityai.gateway.dataplane.plugin.api.PluginCapabilities;
import io.reliabilityai.gateway.dataplane.plugin.api.PluginManifest;
import io.reliabilityai.gateway.dataplane.plugin.api.PluginPermissions;

/**
 * The deny-by-default capability gate (Doc 28 PRT-D5, §31).
 *
 * <p>Every method here answers false unless something explicitly said yes. That is the whole
 * design: there is no default-allow branch to get wrong, and adding a new resource kind means
 * adding a new method that also starts from no.
 *
 * <p>A grant needs <b>both</b> halves — the capability and the matching permission. Either alone is
 * not enough: {@code net.egress} without a host allow-list can reach nothing, and a host allow-list
 * without {@code net.egress} grants nothing. Requiring both means neither half can be widened
 * accidentally without the other being noticed.
 */
public final class PermissionEvaluator {

  private PermissionEvaluator() {}

  /**
   * Whether the plugin may reach the host through the mediated network API.
   *
   * @param manifest the vetted manifest
   * @param host the host being requested
   * @return true only if both the capability and an exact host grant are present
   */
  public static boolean allowsNetwork(final PluginManifest manifest, final String host) {
    Preconditions.requireNonNull(manifest, "manifest");
    return manifest.requiredCapabilities().has(PluginCapabilities.NETWORK_EGRESS)
        && manifest.permissions().allowsHost(host);
  }

  /**
   * Whether the plugin may read the path through the mediated file API.
   *
   * @param manifest the vetted manifest
   * @param path the absolute path being requested
   * @return true only if both the capability and a covering path grant are present
   */
  public static boolean allowsFilesystem(final PluginManifest manifest, final String path) {
    Preconditions.requireNonNull(manifest, "manifest");
    return manifest.requiredCapabilities().has(PluginCapabilities.FILESYSTEM_READ)
        && manifest.permissions().allowsPath(path);
  }

  /**
   * Whether the plugin may read the environment variable.
   *
   * @param manifest the vetted manifest
   * @param key the variable name
   * @return true only if both the capability and an exact name grant are present
   */
  public static boolean allowsEnvironment(final PluginManifest manifest, final String key) {
    Preconditions.requireNonNull(manifest, "manifest");
    return manifest.requiredCapabilities().has(PluginCapabilities.ENVIRONMENT_READ)
        && manifest.permissions().allowsEnvironment(key);
  }

  /**
   * Whether the plugin holds a named host capability.
   *
   * <p>Refuses anything on the forbidden list outright, even if a manifest somehow carried it here.
   * The registry already refuses such manifests, and this is the second gate: a single missed check
   * at bind time should not become a granted forbidden capability at call time.
   *
   * @param manifest the vetted manifest
   * @param capability the capability name
   * @return true if the capability is granted and not forbidden
   */
  public static boolean allowsCapability(final PluginManifest manifest, final String capability) {
    Preconditions.requireNonNull(manifest, "manifest");
    if (capability == null || PluginCapabilities.FORBIDDEN.contains(capability)) {
      return false;
    }
    return manifest.requiredCapabilities().has(capability);
  }

  /**
   * The capabilities actually granted to an invocation.
   *
   * <p>Recomputed rather than copied from the manifest: the granted set is the manifest's request
   * <em>minus</em> anything unbacked, so what the plugin is told it has matches what the gate will
   * actually let it do.
   *
   * @param manifest the vetted manifest
   * @return the effective capability set
   */
  public static PluginCapabilities granted(final PluginManifest manifest) {
    Preconditions.requireNonNull(manifest, "manifest");
    final PluginPermissions permissions = manifest.permissions();
    final java.util.Set<String> effective = new java.util.TreeSet<>();
    for (final String capability : manifest.requiredCapabilities().requested()) {
      if (PluginCapabilities.FORBIDDEN.contains(capability)) {
        continue;
      }
      final boolean backed =
          switch (capability) {
            case PluginCapabilities.NETWORK_EGRESS -> !permissions.networkHosts().isEmpty();
            case PluginCapabilities.FILESYSTEM_READ -> !permissions.filesystemPaths().isEmpty();
            case PluginCapabilities.ENVIRONMENT_READ -> !permissions.environmentKeys().isEmpty();
            default -> true;
          };
      if (backed) {
        effective.add(capability);
      }
    }
    return new PluginCapabilities(effective);
  }
}

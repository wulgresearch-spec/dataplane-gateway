package io.reliabilityai.gateway.dataplane.plugin.api;

import io.reliabilityai.gateway.common.Preconditions;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

/**
 * The least-privilege permission grant a vetted manifest declares (Doc 28 PRT-D5, §32–33).
 *
 * <p>Deny-by-default is expressed structurally: every field is an allow-list, and {@link #none()} —
 * three empty sets — is what a manifest that declares nothing gets. There is no wildcard and no
 * "inherit host" option, because either one would turn a vetted grant into an open one.
 *
 * <p>Secrets are deliberately absent. There is no secret permission to grant, at any level, because
 * Doc 28 PRT-D8 makes credential access unavailable to plugins as a matter of construction rather
 * than configuration — an operator cannot enable what the type system cannot express.
 *
 * @param filesystemPaths absolute path prefixes the plugin may read (empty = no filesystem access)
 * @param networkHosts hosts the plugin may reach through the mediated host API (empty = no egress)
 * @param environmentKeys environment variable names the plugin may read (empty = none)
 */
public record PluginPermissions(
    Set<String> filesystemPaths, Set<String> networkHosts, Set<String> environmentKeys) {

  /** Compact constructor taking sorted, immutable copies so ordering is deterministic. */
  public PluginPermissions {
    // Set.copyOf is applied here, not inside sorted(): SpotBugs judges exposure one frame at a
    // time and cannot see a copy made further down.
    filesystemPaths = Set.copyOf(sorted(filesystemPaths, "filesystemPaths"));
    networkHosts = Set.copyOf(sorted(networkHosts, "networkHosts"));
    environmentKeys = Set.copyOf(sorted(environmentKeys, "environmentKeys"));
  }

  /**
   * The empty grant: no filesystem, no network, no environment.
   *
   * @return a permission set granting nothing
   */
  public static PluginPermissions none() {
    return new PluginPermissions(Set.of(), Set.of(), Set.of());
  }

  /**
   * Whether the plugin may reach the given host through the mediated host API.
   *
   * <p>Matching is exact. A suffix match would make {@code evil-example.com} satisfy a grant for
   * {@code example.com}.
   *
   * @param host the host being requested
   * @return true if the host was explicitly granted
   */
  public boolean allowsHost(final String host) {
    return host != null && networkHosts.contains(host);
  }

  /**
   * Whether the plugin may read the given path.
   *
   * <p>A grant is a path prefix, and the candidate must be normalized before it gets here: a path
   * containing {@code ..} is refused outright rather than resolved, since resolving it is exactly
   * how a prefix check gets walked out of.
   *
   * @param path the absolute path being requested
   * @return true if some granted prefix covers the path
   */
  public boolean allowsPath(final String path) {
    if (path == null || path.isBlank() || path.contains("..")) {
      return false;
    }
    for (final String granted : filesystemPaths) {
      if (path.equals(granted)
          || path.startsWith(granted.endsWith("/") ? granted : granted + "/")) {
        return true;
      }
    }
    return false;
  }

  /**
   * Whether the plugin may read the given environment variable.
   *
   * @param key the variable name
   * @return true if the name was explicitly granted
   */
  public boolean allowsEnvironment(final String key) {
    return key != null && environmentKeys.contains(key);
  }

  /**
   * Whether this grant confers nothing at all.
   *
   * @return true if every allow-list is empty
   */
  public boolean empty() {
    return filesystemPaths.isEmpty() && networkHosts.isEmpty() && environmentKeys.isEmpty();
  }

  private static Set<String> sorted(final Set<String> values, final String field) {
    if (values == null) {
      return Set.of();
    }
    final Set<String> copy = new TreeSet<>();
    for (final String value : values) {
      copy.add(Preconditions.requireNonBlank(value, field));
    }
    return copy;
  }

  /**
   * The granted hosts in deterministic order, for audit and telemetry.
   *
   * @return the sorted host list
   */
  public List<String> hostsInOrder() {
    return List.copyOf(new TreeSet<>(networkHosts));
  }
}

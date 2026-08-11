package io.reliabilityai.gateway.dataplane.plugin.api;

import io.reliabilityai.gateway.canonical.plugin.ExtensionPoint;
import io.reliabilityai.gateway.common.Preconditions;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * Everything the C12 control plane declared about a plugin (Doc 28 §6, §12).
 *
 * <p>This record is <b>declared, not authored</b>: the runtime reads it, verifies it against the
 * signed snapshot, and refuses anything inconsistent. It never fills in a default for a field the
 * manifest omitted, because a runtime-supplied default is a privilege nobody vetted (Doc 28 STC-7,
 * ROC-8).
 *
 * <p>Ordering fields are here for a reason. {@code priority} plus the plugin id gives the total
 * order Doc 28 POC-2 requires — order is a property of the vetted snapshot, so it cannot vary with
 * registration timing, map iteration or wall-clock.
 *
 * @param id the stable plugin id
 * @param name the human-readable name
 * @param version the pinned version
 * @param vendor the publishing vendor
 * @param description the human-readable description
 * @param type the execution substrate
 * @param trustTier how far the operator trusts this code
 * @param extensionPoints the frozen extension points this plugin binds to (may be empty for a pure
 *     tool plugin)
 * @param priority the ordering priority within an extension point; lower runs first
 * @param permissions the least-privilege permission grant
 * @param requiredCapabilities the host capabilities requested
 * @param toolDefinitions the tools this plugin exposes
 * @param resources the per-invocation resource budget
 * @param configuration opaque, content-free configuration the plugin receives at start
 * @param health the health-probe policy
 * @param dependencies the plugins that must be ready before this one starts
 */
public record PluginManifest(
    PluginId id,
    String name,
    PluginVersion version,
    String vendor,
    String description,
    PluginType type,
    TrustTier trustTier,
    Set<ExtensionPoint> extensionPoints,
    int priority,
    PluginPermissions permissions,
    PluginCapabilities requiredCapabilities,
    List<ToolDefinition> toolDefinitions,
    ResourceBudget resources,
    Map<String, String> configuration,
    HealthPolicy health,
    List<PluginDependency> dependencies) {

  /** Compact constructor validating and canonicalizing every declared field. */
  public PluginManifest {
    Preconditions.requireNonNull(id, "id");
    Preconditions.requireNonBlank(name, "name");
    Preconditions.requireNonNull(version, "version");
    Preconditions.requireNonBlank(vendor, "vendor");
    Preconditions.requireNonNull(description, "description");
    Preconditions.requireNonNull(type, "type");
    Preconditions.requireNonNull(trustTier, "trustTier");
    Preconditions.requireNonNull(permissions, "permissions");
    Preconditions.requireNonNull(requiredCapabilities, "requiredCapabilities");
    Preconditions.requireNonNull(resources, "resources");
    Preconditions.requireNonNull(health, "health");

    extensionPoints =
        extensionPoints == null || extensionPoints.isEmpty()
            ? Set.of()
            : Set.copyOf(new TreeSet<>(extensionPoints));

    // Tool names must be unique within a plugin: two tools sharing a name makes dispatch ambiguous,
    // and resolving the ambiguity by picking one silently executes something the caller did not ask
    // for.
    final List<ToolDefinition> tools =
        toolDefinitions == null ? List.of() : List.copyOf(toolDefinitions);
    final Set<String> seen = new TreeSet<>();
    for (final ToolDefinition tool : tools) {
      if (!seen.add(tool.name())) {
        throw new IllegalArgumentException("duplicate tool name in manifest");
      }
    }
    toolDefinitions = tools;

    configuration = configuration == null ? Map.of() : Map.copyOf(configuration);

    // Sorted by id so the dependency list — and therefore the startup order derived from it — does
    // not depend on how the manifest happened to be written.
    final List<PluginDependency> declared =
        dependencies == null ? new ArrayList<>() : new ArrayList<>(dependencies);
    declared.sort(Comparator.comparing(dependency -> dependency.pluginId().value()));
    final Set<String> distinct = new TreeSet<>();
    for (final PluginDependency dependency : declared) {
      if (!distinct.add(dependency.pluginId().value())) {
        throw new IllegalArgumentException("duplicate dependency in manifest");
      }
      if (dependency.pluginId().equals(id)) {
        throw new IllegalArgumentException("plugin declares a dependency on itself");
      }
    }
    dependencies = List.copyOf(declared);
  }

  /**
   * Whether this plugin binds to the given frozen extension point.
   *
   * @param point the extension point
   * @return true if the manifest declared it
   */
  public boolean bindsTo(final ExtensionPoint point) {
    return extensionPoints.contains(point);
  }

  /**
   * Looks up a declared tool by name.
   *
   * @param toolName the tool name
   * @return the definition, or null when this plugin declares no such tool
   */
  public ToolDefinition tool(final String toolName) {
    for (final ToolDefinition definition : toolDefinitions) {
      if (definition.name().equals(toolName)) {
        return definition;
      }
    }
    return null;
  }

  /**
   * The content-free manifest summary used in audit records and telemetry.
   *
   * <p>Only ids, counts and categories — never configuration values, which are opaque to this
   * module and could carry anything (Doc 14 §7.1).
   *
   * @return the summary map, in deterministic key order
   */
  public Map<String, String> summary() {
    final Map<String, String> summary = new LinkedHashMap<>();
    summary.put("pluginId", id.value());
    summary.put("version", version.toString());
    summary.put("type", type.name());
    summary.put("trustTier", trustTier.name());
    summary.put("extensionPoints", String.valueOf(extensionPoints.size()));
    summary.put("tools", String.valueOf(toolDefinitions.size()));
    summary.put("dependencies", String.valueOf(dependencies.size()));
    return Map.copyOf(summary);
  }
}

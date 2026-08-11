package io.reliabilityai.gateway.dataplane.plugin.domain;

import io.reliabilityai.gateway.common.Preconditions;
import io.reliabilityai.gateway.dataplane.plugin.api.ExtensionPlugin;
import io.reliabilityai.gateway.dataplane.plugin.api.IsolationLevel;
import io.reliabilityai.gateway.dataplane.plugin.api.Plugin;
import io.reliabilityai.gateway.dataplane.plugin.api.PluginCapabilities;
import io.reliabilityai.gateway.dataplane.plugin.api.PluginManifest;
import io.reliabilityai.gateway.dataplane.plugin.api.RegistrationOutcome.RefusalReason;
import io.reliabilityai.gateway.dataplane.plugin.api.ToolPlugin;
import io.reliabilityai.gateway.dataplane.plugin.api.TrustTier;
import java.util.Optional;

/**
 * Checks a manifest against everything Doc 28 refuses to load (Doc 28 §13, §31, §ISO).
 *
 * <p>Pure: manifest and instance in, refusal reason or nothing out. Every check here happens at
 * <em>bind</em> time rather than at call time, which is the point — a plugin whose manifest asks
 * for a forbidden capability is never bound, so there is no running plugin whose every call has to
 * be defended against.
 *
 * <p>The checks are ordered from most to least severe, so a manifest that fails several is reported
 * against the one an operator most needs to see. A manifest requesting {@code secret.read} should
 * be reported as a forbidden capability even if it also has an unsatisfied dependency.
 */
public final class ManifestValidator {

  private ManifestValidator() {}

  /**
   * Validates a manifest and the instance bound to it.
   *
   * @param manifest the verified manifest
   * @param instance the plugin instance being bound
   * @param isolation the containment the substrate that would run it provides
   * @return the refusal reason, or empty when the plugin may be bound
   */
  public static Optional<RefusalReason> validate(
      final PluginManifest manifest, final Plugin instance, final IsolationLevel isolation) {
    Preconditions.requireNonNull(manifest, "manifest");
    Preconditions.requireNonNull(instance, "instance");
    Preconditions.requireNonNull(isolation, "isolation");

    final PluginCapabilities capabilities = manifest.requiredCapabilities();
    if (!capabilities.forbidden().isEmpty()) {
      return Optional.of(RefusalReason.FORBIDDEN_CAPABILITY);
    }
    if (!capabilities.unknown().isEmpty()) {
      return Optional.of(RefusalReason.UNKNOWN_CAPABILITY);
    }

    // Doc 28 ISO-1: third-party code never runs in the host JVM, whatever its manifest declares.
    // This
    // is the single check that keeps the isolation guarantee honest, so it is checked against the
    // substrate's own reported level rather than against the declared plugin type — a type could be
    // mis-declared, but the substrate knows what it actually is.
    if (manifest.trustTier() == TrustTier.THIRD_PARTY && !isolation.containsUntrustedCode()) {
      return Optional.of(RefusalReason.ISOLATION_INSUFFICIENT);
    }
    if (manifest.type().requiresProcessIsolation() && !isolation.containsUntrustedCode()) {
      return Optional.of(RefusalReason.ISOLATION_INSUFFICIENT);
    }
    if (!manifest.type().supported()) {
      return Optional.of(RefusalReason.UNSUPPORTED_TYPE);
    }

    // A capability is only granted if a matching permission backs it. Otherwise a manifest could
    // request net.egress with no hosts and the runtime would grant a capability that can reach
    // nothing — an entitlement that reads as broader than it is.
    if (capabilities.has(PluginCapabilities.NETWORK_EGRESS)
        && manifest.permissions().networkHosts().isEmpty()) {
      return Optional.of(RefusalReason.UNGRANTED_CAPABILITY);
    }
    if (capabilities.has(PluginCapabilities.FILESYSTEM_READ)
        && manifest.permissions().filesystemPaths().isEmpty()) {
      return Optional.of(RefusalReason.UNGRANTED_CAPABILITY);
    }
    if (capabilities.has(PluginCapabilities.ENVIRONMENT_READ)
        && manifest.permissions().environmentKeys().isEmpty()) {
      return Optional.of(RefusalReason.UNGRANTED_CAPABILITY);
    }

    // The reverse direction: a permission granted without the capability that uses it is also
    // refused.
    // A stale host in an allow-list nobody can reach is exactly the kind of drift that gets copied
    // forward into a manifest that later does request the capability.
    if (!manifest.permissions().networkHosts().isEmpty()
        && !capabilities.has(PluginCapabilities.NETWORK_EGRESS)) {
      return Optional.of(RefusalReason.UNGRANTED_CAPABILITY);
    }

    if (!manifest.equals(instance.manifest())) {
      return Optional.of(RefusalReason.MANIFEST_MISMATCH);
    }

    final boolean extension = instance instanceof ExtensionPlugin;
    final boolean tool = instance instanceof ToolPlugin;
    if (!extension && !tool) {
      return Optional.of(RefusalReason.NO_CAPABILITY_INTERFACE);
    }
    // A manifest declaring extension points needs an instance that can serve them, and one
    // declaring
    // tools needs one that can run them. Binding either mismatch would produce a plugin that is
    // dispatched to and can only ever be isolated.
    if (!manifest.extensionPoints().isEmpty() && !extension) {
      return Optional.of(RefusalReason.NO_CAPABILITY_INTERFACE);
    }
    if (!manifest.toolDefinitions().isEmpty() && !tool) {
      return Optional.of(RefusalReason.NO_CAPABILITY_INTERFACE);
    }
    if (!manifest.toolDefinitions().isEmpty()
        && !capabilities.has(PluginCapabilities.TOOL_EXECUTION)) {
      return Optional.of(RefusalReason.UNGRANTED_CAPABILITY);
    }
    return Optional.empty();
  }
}

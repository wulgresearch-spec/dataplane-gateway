package io.reliabilityai.gateway.ports;

import io.reliabilityai.gateway.canonical.plugin.ExtensionPoint;
import io.reliabilityai.gateway.canonical.plugin.PluginContext;
import io.reliabilityai.gateway.canonical.plugin.PluginResult;

/**
 * The Plugin Runtime port (C12, Doc 28, AD-002). Invokes sandboxed plugins registered at a given
 * extension point and returns their advisory result. Plugins only contribute additive signals; the
 * owning pipeline stage decides and a failed plugin is isolated, never weakening the mandatory
 * pipeline (Doc 28 §EPFC, PEB-1..3). Fail-closed to {@link PluginResult.Isolated}.
 */
public interface PluginRuntimePort {

  /**
   * Invokes the sandboxed plugins registered at the extension point (Doc 28 §6).
   *
   * @param extensionPoint the extension point being evaluated
   * @param context the content-free plugin context
   * @return the plugin result (completed advisory contribution or isolated)
   */
  PluginResult invokeAt(ExtensionPoint extensionPoint, PluginContext context);
}

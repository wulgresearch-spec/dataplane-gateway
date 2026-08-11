package io.reliabilityai.gateway.dataplane.plugin.api;

/**
 * The lifecycle contract every plugin implements (Doc 28 §D).
 *
 * <p>This interface carries <b>only</b> lifecycle. What a plugin can actually do is expressed by
 * the capability interfaces it also implements — {@link ExtensionPlugin}, {@link ToolPlugin},
 * {@link StreamingToolPlugin} — so a plugin declares its powers by its type rather than by a flag,
 * and the runtime can refuse a mismatch at registration instead of discovering it at call time.
 *
 * <p>That split is what lets the Agent Runtime and Memory Runtime arrive later without breaking
 * anything: each adds a new capability interface extending {@code Plugin}, and every existing
 * plugin, registry entry, sandbox and audit path keeps working untouched.
 *
 * <p><b>Implementations are called concurrently.</b> The runtime invokes a READY plugin from many
 * virtual threads at once and does not serialize calls. An implementation that keeps mutable state
 * across invocations is responsible for its own safety — and, per Doc 28 §36.1, must not keep
 * per-tenant state at all.
 */
public interface Plugin {

  /**
   * The manifest this plugin was built against.
   *
   * <p>The runtime verifies this matches the signed manifest it registered. A mismatch means the
   * code and the vetted declaration disagree, and the plugin is refused rather than reconciled.
   *
   * @return the manifest
   */
  PluginManifest manifest();

  /**
   * Starts the plugin. Called once per generation, before any invocation.
   *
   * <p>Throwing here moves the plugin to {@link PluginState#FAILED} and it is never invoked. That
   * is the intended way to refuse: a plugin that cannot initialize should say so loudly rather than
   * start and fail every call.
   *
   * @param context the mediated startup context
   * @throws Exception if the plugin cannot start
   */
  void start(ToolContext context) throws Exception;

  /**
   * Stops the plugin and releases its resources.
   *
   * <p>Called once per generation. Must not throw; a throwing stop is logged as a content-free
   * failure and the plugin is moved to STOPPED regardless, because refusing to let a plugin be
   * stopped would make shutdown depend on plugin cooperation.
   */
  void stop();

  /**
   * The plugin's self-reported health (Doc 28 §J).
   *
   * <p>Advisory. The runtime routes on {@link PluginState}, never on this.
   *
   * @return the health verdict
   */
  default PluginHealth health() {
    return PluginHealth.UNKNOWN;
  }
}

package io.reliabilityai.gateway.dataplane.plugin.api;

import io.reliabilityai.gateway.canonical.plugin.ExtensionPoint;
import java.util.List;
import java.util.Optional;

/**
 * The runtime's local binding table of verified plugins (Doc 28 §ROC).
 *
 * <p><b>This is not the plugin registry of record.</b> Doc 28 ROC-1 puts that with the C12 control
 * plane, along with vetting, signing, discovery, provenance and publishing. What lives here is the
 * node's own answer to "which already-vetted plugins am I currently able to dispatch to" — {@link
 * #register} verifies and binds, it never approves. A snapshot that fails verification is refused
 * here; nothing that happens here can make an unvetted plugin runnable.
 *
 * <p><b>Registration is explicit.</b> A caller hands over both the signed snapshot and the instance
 * to bind. There is no classpath scan, no {@code ServiceLoader}, no reflection and no annotation
 * discovery — Doc 28 PRT-A4 forbids unrestricted reflection, and a discovery mechanism is a way for
 * code to get loaded that nobody wrote down.
 *
 * <p>Implementations are thread-safe. Operator commands run concurrently with request-path lookups,
 * and {@link #lookup} must never observe a half-applied reload.
 */
public interface PluginRegistryPort {

  /**
   * Verifies a snapshot and binds the instance to it.
   *
   * <p>The plugin lands in {@link PluginState#REGISTERED}, not READY: binding and starting are
   * separate so a dependency graph can be bound whole and then started in order (Doc 28 §14).
   *
   * @param snapshot the C12-vetted, signed, digest-pinned snapshot
   * @param instance the plugin to bind
   * @return bound, or refused with a content-free reason
   */
  RegistrationOutcome register(VettedPluginSnapshot snapshot, Plugin instance);

  /**
   * Stops and unbinds a plugin.
   *
   * <p>Stops it first if it is running. An unregister that left a started plugin behind would leak
   * its resources and its threads.
   *
   * @param pluginId the plugin to unbind
   * @return true if a plugin was unbound; false if none was registered
   */
  boolean unregister(PluginId pluginId);

  /**
   * Looks up a bound plugin.
   *
   * <p>On the request path. Must not block on I/O, must not consult the control plane, and must not
   * allocate proportionally to registry size (AD-022, Doc 28 PRT-D2).
   *
   * @param pluginId the plugin
   * @return the descriptor, or empty when nothing is bound under that id
   */
  Optional<PluginDescriptor> lookup(PluginId pluginId);

  /**
   * Replaces a plugin with a new verified version, atomically.
   *
   * <p>Doc 28 HRC-2 forbids mutating a loaded plugin's behavior, so this never patches the running
   * instance: it verifies the new snapshot, starts the new instance, swaps the binding and stops
   * the old one. A failure at any step leaves the previous version serving (Doc 28 HRC-6,
   * last-known-good) rather than leaving the node with no plugin at all.
   *
   * @param snapshot the new vetted snapshot
   * @param instance the new instance
   * @return bound with the new descriptor, or refused with the old version still serving
   */
  RegistrationOutcome reload(VettedPluginSnapshot snapshot, Plugin instance);

  /**
   * An immutable point-in-time view of every bound plugin.
   *
   * <p>Consistent: taken under the registry's own lock, so it never shows a plugin mid-transition.
   *
   * @return the descriptors, ordered by plugin id
   */
  List<PluginDescriptor> snapshot();

  /**
   * The plugins bound to a frozen extension point, in deterministic execution order.
   *
   * <p>The order is the total order of Doc 28 POC-2: manifest priority first, plugin id as
   * tiebreak. It is derived from the vetted manifests alone, so it does not vary with registration
   * timing, map iteration order or wall-clock (Doc 28 POC-1/POC-6).
   *
   * @param extensionPoint the frozen point
   * @return the descriptors in execution order
   */
  List<PluginDescriptor> list(ExtensionPoint extensionPoint);
}

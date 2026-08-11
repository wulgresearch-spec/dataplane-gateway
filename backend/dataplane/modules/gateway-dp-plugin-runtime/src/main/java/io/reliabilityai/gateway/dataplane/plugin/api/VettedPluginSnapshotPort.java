package io.reliabilityai.gateway.dataplane.plugin.api;

import java.util.List;
import java.util.Optional;

/**
 * The read-only feed of vetted snapshots from the C12 control plane (Doc 28 §ROC, AD-022).
 *
 * <p>Read-only in the strict sense: there is no publish, approve or register method, because Doc 28
 * ROC-1..ROC-6 place all of that with the control plane. The runtime consumes what it is given.
 *
 * <p>Reads here are <b>never on the request path</b>. AD-022 and Doc 28 PRT-D2 put verification at
 * load time, so this port is consulted at startup, on an operator reload, and nowhere else — a
 * synchronous control-plane call per request is exactly what AD-022 exists to prevent.
 */
public interface VettedPluginSnapshotPort {

  /**
   * Every snapshot the control plane currently publishes for this node.
   *
   * @return the available snapshots; empty when the control plane has published none
   */
  List<VettedPluginSnapshot> available();

  /**
   * The snapshot for a specific plugin at a specific version.
   *
   * <p>Version-pinned by design (Doc 28 §11): there is no "give me the latest", because resolving
   * "latest" at load time means the bytes that get verified vary with when the node happened to
   * start.
   *
   * @param pluginId the plugin
   * @param version the pinned version
   * @return the snapshot, or empty when the control plane publishes no such version
   */
  Optional<VettedPluginSnapshot> pinned(PluginId pluginId, PluginVersion version);
}

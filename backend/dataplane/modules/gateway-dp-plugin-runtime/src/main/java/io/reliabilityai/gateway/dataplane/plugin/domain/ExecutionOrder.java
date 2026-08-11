package io.reliabilityai.gateway.dataplane.plugin.domain;

import io.reliabilityai.gateway.common.Preconditions;
import io.reliabilityai.gateway.dataplane.plugin.api.PluginDescriptor;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * The deterministic total order plugins run in at an extension point (Doc 28 §POC).
 *
 * <p>Manifest priority first, plugin id as tiebreak. Both come from the vetted snapshot, so the
 * order is a property of the plugin set and nothing else — not registration timing, not hash
 * iteration order, not wall-clock (Doc 28 POC-2). Replaying the same snapshot gives the same order,
 * which is what Doc 28 POC-6/RPC-1 need.
 *
 * <p>The id tiebreak is not cosmetic. Two plugins at the same priority would otherwise be ordered
 * by whatever the sort happened to do, and "usually stable" is exactly the kind of ordering that
 * holds until the day it decides a governance signal.
 */
public final class ExecutionOrder {

  /** Priority ascending, then plugin id ascending. Total, and total is the point. */
  public static final Comparator<PluginDescriptor> COMPARATOR =
      Comparator.<PluginDescriptor>comparingInt(descriptor -> descriptor.manifest().priority())
          .thenComparing(descriptor -> descriptor.id().value());

  private ExecutionOrder() {}

  /**
   * Sorts descriptors into execution order.
   *
   * @param descriptors the plugins bound at a point
   * @return a new list in deterministic execution order
   */
  public static List<PluginDescriptor> sort(final List<PluginDescriptor> descriptors) {
    Preconditions.requireNonNull(descriptors, "descriptors");
    final List<PluginDescriptor> ordered = new ArrayList<>(descriptors);
    ordered.sort(COMPARATOR);
    return List.copyOf(ordered);
  }
}

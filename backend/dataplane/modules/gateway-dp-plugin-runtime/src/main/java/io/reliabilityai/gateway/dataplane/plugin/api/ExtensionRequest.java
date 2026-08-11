package io.reliabilityai.gateway.dataplane.plugin.api;

import io.reliabilityai.gateway.canonical.plugin.ExtensionPoint;
import io.reliabilityai.gateway.common.Preconditions;
import java.util.Map;

/**
 * What a plugin bound to a frozen extension point gets to see (Doc 28 §15, §EPC).
 *
 * <p>Content-free by construction: a bounded map of signals the owning stage published, and nothing
 * else. There is no request body, no response, no route, no credential and no callback. Doc 28
 * EPC-5 makes the plugin's answer advisory, so it needs enough to form an opinion and nothing that
 * would let it act on one.
 *
 * <p>Every plugin at a point receives the <em>same</em> input. Doc 28 POC-3 keeps plugins mutually
 * isolated: one plugin never sees another's contribution, so no plugin can be made to depend on
 * running after another.
 *
 * @param extensionPoint the frozen point being evaluated
 * @param context the mediated execution context
 * @param signals the content-free signals the owning stage published
 */
public record ExtensionRequest(
    ExtensionPoint extensionPoint, ToolContext context, Map<String, String> signals) {

  /** Compact constructor validating the advisory input. */
  public ExtensionRequest {
    Preconditions.requireNonNull(extensionPoint, "extensionPoint");
    Preconditions.requireNonNull(context, "context");
    signals = signals == null ? Map.of() : Map.copyOf(signals);
  }
}

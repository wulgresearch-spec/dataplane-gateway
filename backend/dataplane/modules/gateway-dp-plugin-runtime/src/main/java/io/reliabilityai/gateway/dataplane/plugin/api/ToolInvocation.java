package io.reliabilityai.gateway.dataplane.plugin.api;

import io.reliabilityai.gateway.common.Preconditions;

/**
 * One complete unit of tool work: who, what, under which budget (Doc 28 §6 {@code
 * PluginInvocation}).
 *
 * <p>An invocation is transient and per-request. Nothing about it is cached and nothing survives
 * the call, which is what makes Doc 28 §36.1's "no cross-request, no cross-tenant state" a
 * structural property rather than a discipline: there is no place to put retained state.
 *
 * @param pluginId the plugin being invoked
 * @param request the tool call
 * @param context the mediated execution context
 * @param budget the enforced resource quota, already clamped to the request's remaining time
 */
public record ToolInvocation(
    PluginId pluginId, ToolRequest request, ToolContext context, ResourceBudget budget) {

  /** Compact constructor validating the unit of work. */
  public ToolInvocation {
    Preconditions.requireNonNull(pluginId, "pluginId");
    Preconditions.requireNonNull(request, "request");
    Preconditions.requireNonNull(context, "context");
    Preconditions.requireNonNull(budget, "budget");
  }

  /**
   * The tool being invoked.
   *
   * @return the tool name
   */
  public String toolName() {
    return request.toolName();
  }
}

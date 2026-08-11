package io.reliabilityai.gateway.dataplane.plugin.api;

import io.reliabilityai.gateway.canonical.identity.CorrelationId;
import io.reliabilityai.gateway.canonical.identity.TenantScope;

/**
 * Executes a tool exposed by a registered plugin (Doc 28 §15).
 *
 * <p><b>Deliberately not bound to the request pipeline.</b> Model-driven tool calling — model
 * returns tool calls, gateway executes them, gateway feeds results back — needs a post-invoke or
 * response-transform hook, and Doc 28 EPC-3 forbids both by name. Wiring this into the pipeline
 * would be a bypass of exactly the kind AD-018 exists to prevent.
 *
 * <p>So the engine is built and callable, and the caller that drives a tool loop is the future
 * Agent Runtime, arriving with the ADR that Doc 28 EPC-6 requires to add an extension point.
 * Everything below this seam — sandbox, quotas, governance, cost, audit, streaming, cancellation —
 * is finished and tested, and needs no change when that caller appears.
 *
 * <p>Every execution passes governance, tenant isolation, the permission gate, the resource budget,
 * audit and cost. There is no path through this port that skips any of them.
 */
public interface ToolExecutionPort {

  /**
   * Executes one tool call and waits for its result.
   *
   * <p>Never throws for a plugin failure — a failure is an {@link ToolExecutionResult.Isolated}, so
   * the caller proceeds unweakened (Doc 28 §EPFC).
   *
   * @param pluginId the plugin exposing the tool
   * @param request the tool call
   * @param tenantScope the tenant to attribute the invocation to
   * @param correlationId the request correlation id
   * @return the outcome, never null
   */
  ToolExecutionResult execute(
      PluginId pluginId, ToolRequest request, TenantScope tenantScope, CorrelationId correlationId);

  /**
   * Executes one tool call as a stream of events.
   *
   * <p>Returns as soon as the invocation is admitted; events arrive as the plugin produces them.
   * The caller <b>must</b> close or cancel the returned stream — that is what releases the
   * producer.
   *
   * <p>A refused invocation still returns a stream: it yields a single {@link PluginEvent.Failed}
   * and ends. Returning a stream on every path means the caller has one shape to handle rather than
   * two.
   *
   * @param pluginId the plugin exposing the tool
   * @param request the tool call
   * @param tenantScope the tenant to attribute the invocation to
   * @param correlationId the request correlation id
   * @return the event stream, never null
   */
  PluginStream stream(
      PluginId pluginId, ToolRequest request, TenantScope tenantScope, CorrelationId correlationId);
}

package io.reliabilityai.gateway.dataplane.app.binding;

import io.reliabilityai.gateway.common.Preconditions;
import io.reliabilityai.gateway.dataplane.agent.api.AgentToolPort;
import io.reliabilityai.gateway.dataplane.agent.api.FailureClass;
import io.reliabilityai.gateway.dataplane.plugin.api.PluginFailureKind;
import io.reliabilityai.gateway.dataplane.plugin.api.PluginId;
import io.reliabilityai.gateway.dataplane.plugin.api.ToolExecutionPort;
import io.reliabilityai.gateway.dataplane.plugin.api.ToolExecutionResult;
import io.reliabilityai.gateway.dataplane.plugin.api.ToolRequest;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Runs an agent's tool steps through the existing Plugin Runtime, unchanged.
 *
 * <p>Every guarantee C12 already makes still applies and is not re-implemented here: the sandbox,
 * the permission evaluation, the resource budget, the failure isolation, the cost reporting. This
 * adapter translates vocabulary and nothing else.
 *
 * <p><b>Capability, not plugin identity.</b> An agent plan names a capability; the mapping from
 * capability to a concrete {@link PluginId} lives here, in the composition root. A plan therefore
 * cannot pin itself to a particular plugin build, and swapping the plugin behind a capability
 * changes no plan and invalidates no in-flight run.
 *
 * <p><b>Output is untrusted by default.</b> Every artifact this adapter produces is tainted unless
 * the capability appears in the trusted set the operator configured. A tool's output may be a web
 * page, a file, or a record written by an earlier prompt injection; treating it as trusted by
 * default would silently disarm the run-level Rule of Two, because the limb would stop being
 * counted.
 */
public final class PluginRuntimeToolAdapter implements AgentToolPort {

  private final ToolExecutionPort tools;
  private final Map<String, CapabilityBinding> bindings;
  private final Map<String, ToolOutcome> completed = new ConcurrentHashMap<>();

  /**
   * How a capability resolves to something the Plugin Runtime can run.
   *
   * @param pluginId the plugin that provides the capability
   * @param toolName the tool within that plugin
   * @param trusted whether policy declares this capability's output trustworthy; false by default
   */
  public record CapabilityBinding(PluginId pluginId, String toolName, boolean trusted) {

    /**
     * Validates the binding.
     *
     * @param pluginId the providing plugin
     * @param toolName the tool name
     * @param trusted the trust declaration
     */
    public CapabilityBinding {
      Preconditions.requireNonNull(pluginId, "pluginId");
      Preconditions.requireNonBlank(toolName, "toolName");
    }

    /**
     * Creates an untrusted binding, which is what almost every capability should be.
     *
     * @param pluginId the providing plugin
     * @param toolName the tool name
     * @return the binding, with output marked untrusted
     */
    public static CapabilityBinding of(final PluginId pluginId, final String toolName) {
      return new CapabilityBinding(pluginId, toolName, false);
    }
  }

  /**
   * Creates the adapter.
   *
   * @param tools the existing Plugin Runtime tool-execution port
   * @param bindings capability to plugin-and-tool, fixed at composition time
   */
  public PluginRuntimeToolAdapter(
      final ToolExecutionPort tools, final Map<String, CapabilityBinding> bindings) {
    this.tools = Preconditions.requireNonNull(tools, "tools");
    this.bindings = Preconditions.immutableMap(bindings, "bindings");
  }

  @Override
  public ToolOutcome invoke(final ToolCall call) {
    Preconditions.requireNonNull(call, "call");

    final CapabilityBinding binding = bindings.get(call.capability());
    if (binding == null) {
      // An unbound capability is a deployment error, not a tool failure, and it is permanent:
      // retrying
      // will not conjure a binding. Classifying it as transient would burn the run's retry budget
      // on
      // something no amount of waiting fixes.
      return remember(
          call,
          new Rejected(
              FailureClass.STEP_PERMANENT,
              "no plugin bound to capability '" + call.capability() + "'",
              0L));
    }

    final ToolExecutionResult result;
    try {
      result =
          tools.execute(
              binding.pluginId(),
              new ToolRequest(binding.toolName(), call.arguments(), Map.of()),
              call.tenant(),
              call.correlationId());
    } catch (final RuntimeException failure) {
      return remember(
          call, new Rejected(FailureClass.TOOL_FAILURE, failure.getClass().getSimpleName(), 0L));
    }

    return remember(call, translate(result, binding));
  }

  private static ToolOutcome translate(
      final ToolExecutionResult result, final CapabilityBinding binding) {
    return switch (result) {
      case ToolExecutionResult.Completed done ->
          new Produced(done.response().output(), done.cost().tenantCostMicros(), binding.trusted());
      case ToolExecutionResult.Isolated isolated ->
          new Rejected(
              classify(isolated.error().kind()),
              isolated.error().kind() + ":" + isolated.error().code(),
              isolated.cost().tenantCostMicros());
      case ToolExecutionResult.Cancelled cancelled ->
          new Aborted(cancelled.cost().tenantCostMicros());
    };
  }

  /**
   * Maps a plugin failure kind onto the agent's closed taxonomy.
   *
   * <p>The line drawn here is retryability, and it is drawn conservatively. A permission violation
   * or a malformed argument will fail identically on a second attempt, so retrying only spends
   * money; a timeout or a resource exhaustion may not.
   */
  private static FailureClass classify(final PluginFailureKind kind) {
    return switch (kind) {
      case TIMEOUT -> FailureClass.STEP_TIMEOUT;
      case PERMISSION -> FailureClass.STEP_DENIED;
      case CANCELLED -> FailureClass.CANCELLED;
      // PLUGIN_BUG is a defect in the tool: the same arguments will produce the same crash, so it
      // is
      // permanent rather than a transient tool failure worth spending a retry on.
      case PLUGIN_BUG -> FailureClass.STEP_PERMANENT;
      case NETWORK, RESOURCE_EXCEEDED, PANIC, UNKNOWN -> FailureClass.TOOL_FAILURE;
    };
  }

  @Override
  public void cancel(final String invocationRef) {
    Preconditions.requireNonNull(invocationRef, "invocationRef");
    // The Plugin Runtime owns invocation lifetime and enforces its own deadline and resource
    // budget.
    // Reaching into a sandbox from here would duplicate that ownership and could leave the
    // runtime's
    // accounting disagreeing with its own.
  }

  @Override
  public Optional<ToolOutcome> lookup(final String invocationRef) {
    Preconditions.requireNonNull(invocationRef, "invocationRef");
    return Optional.ofNullable(completed.get(invocationRef));
  }

  private ToolOutcome remember(final ToolCall call, final ToolOutcome outcome) {
    completed.put(call.invocationRef(), outcome);
    return outcome;
  }

  /**
   * Returns the capabilities this adapter can serve.
   *
   * @return the bound capability names
   */
  public java.util.Set<String> boundCapabilities() {
    return bindings.keySet();
  }
}

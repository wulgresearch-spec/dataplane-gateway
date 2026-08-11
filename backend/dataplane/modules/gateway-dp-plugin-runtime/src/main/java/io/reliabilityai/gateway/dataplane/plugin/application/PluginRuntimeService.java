package io.reliabilityai.gateway.dataplane.plugin.application;

import io.reliabilityai.gateway.canonical.audit.AuditRecord;
import io.reliabilityai.gateway.canonical.identity.CorrelationId;
import io.reliabilityai.gateway.canonical.identity.TenantScope;
import io.reliabilityai.gateway.canonical.plugin.ExtensionPoint;
import io.reliabilityai.gateway.canonical.plugin.PluginContext;
import io.reliabilityai.gateway.canonical.plugin.PluginResult;
import io.reliabilityai.gateway.common.Preconditions;
import io.reliabilityai.gateway.dataplane.plugin.api.ExtensionPlugin;
import io.reliabilityai.gateway.dataplane.plugin.api.ExtensionRequest;
import io.reliabilityai.gateway.dataplane.plugin.api.PluginAuditSinkPort;
import io.reliabilityai.gateway.dataplane.plugin.api.PluginAuthorizationPort;
import io.reliabilityai.gateway.dataplane.plugin.api.PluginCapabilities;
import io.reliabilityai.gateway.dataplane.plugin.api.PluginCostSinkPort;
import io.reliabilityai.gateway.dataplane.plugin.api.PluginDescriptor;
import io.reliabilityai.gateway.dataplane.plugin.api.PluginFailureKind;
import io.reliabilityai.gateway.dataplane.plugin.api.PluginId;
import io.reliabilityai.gateway.dataplane.plugin.api.PluginInvocationCost;
import io.reliabilityai.gateway.dataplane.plugin.api.PluginTelemetryPort;
import io.reliabilityai.gateway.dataplane.plugin.api.ResourceBudget;
import io.reliabilityai.gateway.dataplane.plugin.api.SandboxHostPort;
import io.reliabilityai.gateway.dataplane.plugin.api.SandboxInvocation;
import io.reliabilityai.gateway.dataplane.plugin.api.SandboxOutcome;
import io.reliabilityai.gateway.dataplane.plugin.api.ToolContext;
import io.reliabilityai.gateway.dataplane.plugin.domain.PermissionEvaluator;
import io.reliabilityai.gateway.dataplane.plugin.internal.RegisteredPlugin;
import io.reliabilityai.gateway.ports.ClockPort;
import io.reliabilityai.gateway.ports.PluginRuntimePort;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Dispatches plugins at the frozen extension points (Doc 28 §EPC, §EPFC, §POC).
 *
 * <p>This is the <b>only</b> seam between the Plugin Runtime and the request pipeline, and it is
 * the frozen {@link PluginRuntimePort}. Doc 28 EPC-1 permits exactly five binding points and EPC-3
 * forbids post-routing, pre-invoke, post-invoke, response-transform, provider interception and
 * stream mutation. The runtime offers no method that could serve any of them.
 *
 * <p><b>The return type is the guarantee.</b> {@link PluginResult.Completed} carries a {@code
 * Map<String, String>} — an advisory signal, nothing more. There is no shape a plugin can return
 * that decides, mutates or overrides anything, so Doc 28 EPC-5's "the owning stage decides" holds
 * by construction rather than by convention.
 *
 * <p><b>Fail-closed means fail-quiet.</b> Every failure — plugin not registered, not authorized,
 * crashed, overran, breached a quota — becomes {@link PluginResult.Isolated}. Nothing propagates to
 * the caller, so the owning stage proceeds exactly as if the plugin did not exist (Doc 28 §EPFC).
 * That is why nothing in this class throws.
 */
public final class PluginRuntimeService implements PluginRuntimePort {

  private final PluginRegistryService registry;
  private final SandboxHostPort sandbox;
  private final PluginAuthorizationPort authorization;
  private final PluginAuditSinkPort audit;
  private final PluginTelemetryPort telemetry;
  private final PluginCostSinkPort costs;
  private final ClockPort clock;

  /**
   * Creates the dispatch service.
   *
   * @param registry the binding table
   * @param sandbox the isolation substrate
   * @param authorization the deny-by-default plugin authorization gate
   * @param audit the content-free audit sink
   * @param telemetry the content-free telemetry sink
   * @param costs the per-invocation cost sink
   * @param clock the injected clock
   */
  public PluginRuntimeService(
      final PluginRegistryService registry,
      final SandboxHostPort sandbox,
      final PluginAuthorizationPort authorization,
      final PluginAuditSinkPort audit,
      final PluginTelemetryPort telemetry,
      final PluginCostSinkPort costs,
      final ClockPort clock) {
    this.registry = Preconditions.requireNonNull(registry, "registry");
    this.sandbox = Preconditions.requireNonNull(sandbox, "sandbox");
    this.authorization = Preconditions.requireNonNull(authorization, "authorization");
    this.audit = Preconditions.requireNonNull(audit, "audit");
    this.telemetry = Preconditions.requireNonNull(telemetry, "telemetry");
    this.costs = Preconditions.requireNonNull(costs, "costs");
    this.clock = Preconditions.requireNonNull(clock, "clock");
  }

  @Override
  public PluginResult invokeAt(final ExtensionPoint extensionPoint, final PluginContext context) {
    if (extensionPoint == null || context == null) {
      return new PluginResult.Isolated("invalid-invocation");
    }
    if (context.extensionPoint() != extensionPoint) {
      // The context names a different point than the caller asked for. Honouring either reading
      // would
      // mean guessing which binding the operator meant.
      return new PluginResult.Isolated("extension-point-mismatch");
    }
    return dispatch(
        PluginId.of(context.pluginId()),
        extensionPoint,
        context.correlationId(),
        context.tenantScope(),
        context.deadline(),
        Map.of());
  }

  /**
   * Invokes every plugin bound at a point, in deterministic order, and returns their contributions.
   *
   * <p>Additive to the frozen port rather than a replacement for it: {@link PluginContext} names a
   * single plugin, so the frozen signature cannot express "everything at this point". The owning
   * stage gets the full ordered set and aggregates it itself, which is what Doc 28 POC-4 assigns to
   * the stage rather than to the runtime.
   *
   * <p>Plugins run <b>sequentially</b> in {@link io.reliabilityai.gateway.dataplane.plugin.domain
   * .ExecutionOrder} order. Running them concurrently would be faster and would make the aggregate
   * timing — and any accidental ordering dependence — nondeterministic, which Doc 28 POC-1 forbids.
   * Each plugin gets the same input and never sees another's contribution (POC-3).
   *
   * @param extensionPoint the frozen point being evaluated
   * @param correlationId the request correlation id
   * @param tenantScope the tenant scope
   * @param deadline the point at which the whole dispatch must be finished
   * @param signals the content-free signals the owning stage publishes to every plugin
   * @return each plugin's result, in execution order
   */
  public Map<PluginId, PluginResult> invokeAll(
      final ExtensionPoint extensionPoint,
      final CorrelationId correlationId,
      final TenantScope tenantScope,
      final Instant deadline,
      final Map<String, String> signals) {
    Preconditions.requireNonNull(extensionPoint, "extensionPoint");
    Preconditions.requireNonNull(correlationId, "correlationId");
    Preconditions.requireNonNull(tenantScope, "tenantScope");
    Preconditions.requireNonNull(deadline, "deadline");

    final List<PluginDescriptor> bound = registry.list(extensionPoint);
    final Map<PluginId, PluginResult> results = new LinkedHashMap<>();
    for (final PluginDescriptor descriptor : bound) {
      results.put(
          descriptor.id(),
          dispatch(
              descriptor.id(),
              extensionPoint,
              correlationId,
              tenantScope,
              deadline,
              signals == null ? Map.of() : signals));
    }
    return java.util.Collections.unmodifiableMap(results);
  }

  /**
   * The plugins bound at a point, in the order they will run.
   *
   * <p>Exposed so an operator — or a determinism test — can read the order without executing
   * anything.
   *
   * @param extensionPoint the frozen point
   * @return the plugin ids in execution order
   */
  public List<PluginId> executionOrder(final ExtensionPoint extensionPoint) {
    Preconditions.requireNonNull(extensionPoint, "extensionPoint");
    final List<PluginId> order = new ArrayList<>();
    for (final PluginDescriptor descriptor : registry.list(extensionPoint)) {
      order.add(descriptor.id());
    }
    return List.copyOf(order);
  }

  /** One plugin, one point, fail-closed on every path. */
  private PluginResult dispatch(
      final PluginId pluginId,
      final ExtensionPoint extensionPoint,
      final CorrelationId correlationId,
      final TenantScope tenantScope,
      final Instant deadline,
      final Map<String, String> signals) {

    final RegisteredPlugin registered = registry.binding(pluginId);
    if (registered == null || !registered.state().admitsExecution()) {
      return new PluginResult.Isolated("plugin-unavailable");
    }
    if (!registered.manifest().bindsTo(extensionPoint)) {
      // Doc 28 EPC-7: a plugin only ever runs at a point its vetted manifest declared.
      return new PluginResult.Isolated("point-not-bound");
    }
    if (!(registered.instance() instanceof ExtensionPlugin extension)) {
      return new PluginResult.Isolated("not-an-extension-plugin");
    }
    if (!authorization.authorize(tenantScope, registered.describe()).permitted()) {
      return isolated(pluginId, extensionPoint, tenantScope, PluginFailureKind.PERMISSION);
    }

    // The deadline the caller supplied wins whenever it is nearer than the manifest's budget: a
    // plugin
    // must never outlast the stage that invoked it (Doc 28 REC-3).
    final Duration remaining = Duration.between(clock.now(), deadline);
    if (remaining.isZero() || remaining.isNegative()) {
      return isolated(pluginId, extensionPoint, tenantScope, PluginFailureKind.TIMEOUT);
    }
    final ResourceBudget budget = registered.manifest().resources().clampedTo(remaining);

    final PluginCapabilities granted = PermissionEvaluator.granted(registered.manifest());
    final ToolContext context =
        new ToolContext(
            correlationId,
            granted.has(PluginCapabilities.READ_TENANT_SCOPE)
                ? tenantScope
                : TenantScope.of("redacted", "redacted"),
            clock.now().plus(budget.wallClock()),
            granted,
            registered.manifest().configuration());
    final ExtensionRequest request = new ExtensionRequest(extensionPoint, context, signals);

    final SandboxOutcome<Map<String, String>> outcome =
        sandbox.run(
            new SandboxInvocation(pluginId, correlationId, budget, context.deadline()),
            () -> extension.contributeAt(request));

    recordCost(pluginId, extensionPoint, tenantScope, outcome);

    if (outcome instanceof SandboxOutcome.Completed<Map<String, String>> completed) {
      final Map<String, String> contribution = completed.value();
      if (contribution == null) {
        return isolated(pluginId, extensionPoint, tenantScope, PluginFailureKind.PLUGIN_BUG);
      }
      telemetry.invocation(
          pluginId,
          extensionPoint.name(),
          PluginTelemetryPort.Outcome.COMPLETED,
          outcome.usage().wallClock());
      recordAudit(pluginId, extensionPoint, tenantScope, "completed");
      // Copied on the way out: the plugin still holds a reference to whatever it returned, and a
      // mutable map crossing this boundary would let it change a signal after the stage read it.
      return new PluginResult.Completed(Map.copyOf(contribution));
    }
    if (outcome instanceof SandboxOutcome.Cancelled<Map<String, String>>) {
      telemetry.invocation(
          pluginId,
          extensionPoint.name(),
          PluginTelemetryPort.Outcome.CANCELLED,
          outcome.usage().wallClock());
      return new PluginResult.Isolated("cancelled");
    }
    final PluginFailureKind kind =
        outcome instanceof SandboxOutcome.Breached<Map<String, String>> breached
            ? breached.kind()
            : ((SandboxOutcome.Threw<Map<String, String>>) outcome).kind();
    if (outcome instanceof SandboxOutcome.Breached<Map<String, String>>) {
      telemetry.budgetBreach(pluginId, PluginTelemetryPort.Resource.WALL_CLOCK);
    }
    return isolated(pluginId, extensionPoint, tenantScope, kind);
  }

  private PluginResult isolated(
      final PluginId pluginId,
      final ExtensionPoint extensionPoint,
      final TenantScope tenantScope,
      final PluginFailureKind kind) {
    telemetry.isolated(pluginId, kind);
    telemetry.invocation(
        pluginId, extensionPoint.name(), PluginTelemetryPort.Outcome.ISOLATED, Duration.ZERO);
    recordAudit(pluginId, extensionPoint, tenantScope, "isolated-" + label(kind));
    return new PluginResult.Isolated(label(kind));
  }

  private void recordCost(
      final PluginId pluginId,
      final ExtensionPoint extensionPoint,
      final TenantScope tenantScope,
      final SandboxOutcome<?> outcome) {
    try {
      costs.record(
          new PluginInvocationCost(
              pluginId,
              extensionPoint.name(),
              tenantScope,
              outcome.usage().wallClock(),
              outcome.usage().cpuMillisEstimate(),
              outcome.usage().memoryBytesEstimate(),
              0L,
              0L));
    } catch (final RuntimeException sinkFailure) {
      // A cost sink must not be able to fail the dispatch it is accounting for.
    }
  }

  private void recordAudit(
      final PluginId pluginId,
      final ExtensionPoint extensionPoint,
      final TenantScope tenantScope,
      final String outcome) {
    try {
      audit.record(
          new AuditRecord(
              new CorrelationId("plugin-extension"),
              null,
              "tool_called",
              pluginId.value() + "@" + extensionPoint.name(),
              outcome,
              tenantScope,
              clock.now()));
    } catch (final RuntimeException auditFailure) {
      // Audit is a side effect, never a gate.
    }
  }

  private static String label(final PluginFailureKind kind) {
    return kind.name().toLowerCase(Locale.ROOT);
  }
}

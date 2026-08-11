package io.reliabilityai.gateway.dataplane.plugin.application;

import io.reliabilityai.gateway.canonical.audit.AuditRecord;
import io.reliabilityai.gateway.canonical.identity.CorrelationId;
import io.reliabilityai.gateway.canonical.identity.TenantScope;
import io.reliabilityai.gateway.common.Preconditions;
import io.reliabilityai.gateway.dataplane.plugin.api.PluginAuditSinkPort;
import io.reliabilityai.gateway.dataplane.plugin.api.PluginAuthorizationPort;
import io.reliabilityai.gateway.dataplane.plugin.api.PluginCapabilities;
import io.reliabilityai.gateway.dataplane.plugin.api.PluginCostSinkPort;
import io.reliabilityai.gateway.dataplane.plugin.api.PluginEvent;
import io.reliabilityai.gateway.dataplane.plugin.api.PluginFailureKind;
import io.reliabilityai.gateway.dataplane.plugin.api.PluginId;
import io.reliabilityai.gateway.dataplane.plugin.api.PluginInvocationCost;
import io.reliabilityai.gateway.dataplane.plugin.api.PluginManifest;
import io.reliabilityai.gateway.dataplane.plugin.api.PluginStream;
import io.reliabilityai.gateway.dataplane.plugin.api.PluginTelemetryPort;
import io.reliabilityai.gateway.dataplane.plugin.api.ResourceBudget;
import io.reliabilityai.gateway.dataplane.plugin.api.SandboxHostPort;
import io.reliabilityai.gateway.dataplane.plugin.api.SandboxInvocation;
import io.reliabilityai.gateway.dataplane.plugin.api.SandboxOutcome;
import io.reliabilityai.gateway.dataplane.plugin.api.StreamingToolPlugin;
import io.reliabilityai.gateway.dataplane.plugin.api.ToolContext;
import io.reliabilityai.gateway.dataplane.plugin.api.ToolError;
import io.reliabilityai.gateway.dataplane.plugin.api.ToolExecutionPort;
import io.reliabilityai.gateway.dataplane.plugin.api.ToolExecutionResult;
import io.reliabilityai.gateway.dataplane.plugin.api.ToolInvocation;
import io.reliabilityai.gateway.dataplane.plugin.api.ToolPlugin;
import io.reliabilityai.gateway.dataplane.plugin.api.ToolRequest;
import io.reliabilityai.gateway.dataplane.plugin.api.ToolResponse;
import io.reliabilityai.gateway.dataplane.plugin.domain.PermissionEvaluator;
import io.reliabilityai.gateway.dataplane.plugin.internal.BoundedPluginStream;
import io.reliabilityai.gateway.dataplane.plugin.internal.RegisteredPlugin;
import io.reliabilityai.gateway.ports.ClockPort;
import java.time.Duration;
import java.util.Locale;

/**
 * Executes tools exposed by registered plugins (Doc 28 §15, §REC, §36.1, §46–47).
 *
 * <p><b>One admission path.</b> Registered and READY, then tenant-authorized, then
 * capability-checked, then budgeted, then sandboxed. Both {@link #execute} and {@link #stream} run
 * the same gate through {@code admit}, so there is no second door and no ordering to get wrong
 * between them.
 *
 * <p><b>Cost is always recorded.</b> Success, isolation, cancellation and refusal all emit a {@link
 * PluginInvocationCost}. Recording only successes would make a plugin that fails every call look
 * free, which is exactly backwards.
 *
 * <p><b>Nothing here can bypass a pipeline stage,</b> because nothing here can reach one. This
 * service holds a registry, a substrate, a clock and four sinks — no provider, no credential, no
 * router, no response. Doc 28 EPC-8's "no response-mutation, provider-call or stream-write
 * capability" is enforced by the object graph rather than by a rule someone has to remember.
 */
public final class ToolExecutionService implements ToolExecutionPort {

  /** How many events may buffer between a streaming plugin and its consumer. */
  private static final int STREAM_BUFFER_EVENTS = 64;

  /** The scope handed to a plugin that was not granted {@code read.tenant-scope}. */
  private static final TenantScope REDACTED_SCOPE = TenantScope.of("redacted", "redacted");

  private final PluginRegistryService registry;
  private final SandboxHostPort sandbox;
  private final PluginAuthorizationPort authorization;
  private final PluginAuditSinkPort audit;
  private final PluginTelemetryPort telemetry;
  private final PluginCostSinkPort costs;
  private final ClockPort clock;
  private final long toolCostMicros;

  /**
   * Creates the execution service.
   *
   * @param registry the binding table
   * @param sandbox the isolation substrate
   * @param authorization the deny-by-default plugin authorization gate
   * @param audit the content-free audit sink
   * @param telemetry the content-free telemetry sink
   * @param costs the per-invocation cost sink
   * @param clock the injected clock
   * @param toolCostMicros the operator-declared cost of one tool invocation
   */
  public ToolExecutionService(
      final PluginRegistryService registry,
      final SandboxHostPort sandbox,
      final PluginAuthorizationPort authorization,
      final PluginAuditSinkPort audit,
      final PluginTelemetryPort telemetry,
      final PluginCostSinkPort costs,
      final ClockPort clock,
      final long toolCostMicros) {
    this.registry = Preconditions.requireNonNull(registry, "registry");
    this.sandbox = Preconditions.requireNonNull(sandbox, "sandbox");
    this.authorization = Preconditions.requireNonNull(authorization, "authorization");
    this.audit = Preconditions.requireNonNull(audit, "audit");
    this.telemetry = Preconditions.requireNonNull(telemetry, "telemetry");
    this.costs = Preconditions.requireNonNull(costs, "costs");
    this.clock = Preconditions.requireNonNull(clock, "clock");
    this.toolCostMicros = Preconditions.requireNonNegative(toolCostMicros, "toolCostMicros");
  }

  @Override
  public ToolExecutionResult execute(
      final PluginId pluginId,
      final ToolRequest request,
      final TenantScope tenantScope,
      final CorrelationId correlationId) {
    return execute(pluginId, request, tenantScope, correlationId, null);
  }

  /**
   * Executes a tool, clamping its budget to the caller's remaining time.
   *
   * <p>The extra parameter is what keeps Doc 28 REC-3 true: the runtime owns the plugin's deadline,
   * but a plugin must never outlive the request that triggered it. A caller inside a request passes
   * what it has left; a caller outside one passes null and gets the manifest budget.
   *
   * @param pluginId the plugin exposing the tool
   * @param request the tool call
   * @param tenantScope the tenant to attribute the invocation to
   * @param correlationId the request correlation id
   * @param remaining the caller's remaining time, or null for none
   * @return the outcome, never null
   */
  public ToolExecutionResult execute(
      final PluginId pluginId,
      final ToolRequest request,
      final TenantScope tenantScope,
      final CorrelationId correlationId,
      final Duration remaining) {

    final Admission admission = admit(pluginId, request, tenantScope, correlationId, remaining);
    if (admission.refusal() != null) {
      return isolate(
          pluginId, request.toolName(), tenantScope, admission.refusal(), Duration.ZERO, 0L, 0L);
    }

    final RegisteredPlugin registered = admission.plugin();
    final ToolInvocation invocation = admission.invocation();
    final ToolPlugin tool = (ToolPlugin) registered.instance();

    final SandboxOutcome<ToolResponse> outcome =
        sandbox.run(
            new SandboxInvocation(
                pluginId, correlationId, invocation.budget(), invocation.context().deadline()),
            () -> tool.invoke(invocation));

    return settle(pluginId, request.toolName(), tenantScope, outcome);
  }

  @Override
  public PluginStream stream(
      final PluginId pluginId,
      final ToolRequest request,
      final TenantScope tenantScope,
      final CorrelationId correlationId) {
    return stream(pluginId, request, tenantScope, correlationId, null);
  }

  /**
   * Streams a tool invocation, clamping its budget to the caller's remaining time.
   *
   * @param pluginId the plugin exposing the tool
   * @param request the tool call
   * @param tenantScope the tenant to attribute the invocation to
   * @param correlationId the request correlation id
   * @param remaining the caller's remaining time, or null for none
   * @return the event stream, never null
   */
  public PluginStream stream(
      final PluginId pluginId,
      final ToolRequest request,
      final TenantScope tenantScope,
      final CorrelationId correlationId,
      final Duration remaining) {

    final Admission admission = admit(pluginId, request, tenantScope, correlationId, remaining);
    if (admission.refusal() != null) {
      isolate(
          pluginId, request.toolName(), tenantScope, admission.refusal(), Duration.ZERO, 0L, 0L);
      return refusedStream(admission.refusal());
    }

    final RegisteredPlugin registered = admission.plugin();
    if (!(registered.instance() instanceof StreamingToolPlugin streaming)) {
      // The plugin can run the tool but cannot stream it. Draining its unary result into a
      // one-event
      // stream would be a plausible convenience and a lie about how the work was produced.
      isolate(
          pluginId,
          request.toolName(),
          tenantScope,
          PluginFailureKind.PLUGIN_BUG,
          Duration.ZERO,
          0L,
          0L);
      return refusedStream(PluginFailureKind.PLUGIN_BUG);
    }
    if (!registered.manifest().requiredCapabilities().has(PluginCapabilities.TOOL_STREAMING)) {
      isolate(
          pluginId,
          request.toolName(),
          tenantScope,
          PluginFailureKind.PERMISSION,
          Duration.ZERO,
          0L,
          0L);
      return refusedStream(PluginFailureKind.PERMISSION);
    }

    final ToolInvocation invocation = admission.invocation();
    final long startedNanos = System.nanoTime();

    // The stream owns cancellation. Its hook interrupts the producer thread; for a process plugin
    // the
    // substrate's own deadline machinery destroys the child, so an abandoned stream leaves nothing
    // running on either substrate.
    final BoundedPluginStream channel = new BoundedPluginStream(STREAM_BUFFER_EVENTS, () -> {});

    final Thread producer =
        Thread.ofVirtual()
            .name("plugin-stream-" + pluginId.value())
            .start(
                () -> {
                  channel.bindProducer(Thread.currentThread());
                  final SandboxOutcome<Boolean> outcome =
                      sandbox.run(
                          new SandboxInvocation(
                              pluginId,
                              correlationId,
                              invocation.budget(),
                              invocation.context().deadline()),
                          () -> {
                            streaming.invokeStreaming(invocation, channel);
                            return Boolean.TRUE;
                          });
                  finishStream(
                      pluginId, request.toolName(), tenantScope, channel, outcome, startedNanos);
                });
    channel.bindProducer(producer);
    return channel;
  }

  /**
   * Plants the terminal event and records the accounting once the producer is done.
   *
   * <p>If the plugin already emitted a terminal, nothing is added — {@link BoundedPluginStream}
   * delivers the first terminal and the queue is drained on cancel, so a second would be
   * unreachable anyway. If it did not, a {@code Failed} is planted: the runtime never fabricates a
   * {@code Completed} for a plugin that did not claim success.
   */
  private void finishStream(
      final PluginId pluginId,
      final String toolName,
      final TenantScope tenantScope,
      final BoundedPluginStream channel,
      final SandboxOutcome<Boolean> outcome,
      final long startedNanos) {

    final Duration elapsed = Duration.ofNanos(System.nanoTime() - startedNanos);
    if (outcome instanceof SandboxOutcome.Completed<Boolean>) {
      if (channel.open()) {
        // The plugin returned without a terminal. That is a contract breach, not a success.
        channel.emit(new PluginEvent.Failed(ToolError.of(PluginFailureKind.PLUGIN_BUG)));
      }
      recordCost(pluginId, toolName, tenantScope, outcome.usage(), elapsed);
      telemetry.invocation(pluginId, toolName, PluginTelemetryPort.Outcome.COMPLETED, elapsed);
      recordAudit(pluginId, toolName, tenantScope, "tool_stream_completed", "completed");
      return;
    }

    final PluginFailureKind kind = kindOf(outcome);
    if (channel.open()) {
      channel.emit(new PluginEvent.Failed(ToolError.of(kind)));
    }
    recordCost(pluginId, toolName, tenantScope, outcome.usage(), elapsed);
    reportFailure(pluginId, toolName, tenantScope, kind, outcome, elapsed);
  }

  /**
   * The single admission gate both entry points share.
   *
   * <p>Ordered from cheapest and most decisive to most expensive: existence, then state, then the
   * tool being declared, then authorization. Authorizing a call to a tool that does not exist would
   * ask the policy engine a question with no meaning.
   */
  private Admission admit(
      final PluginId pluginId,
      final ToolRequest request,
      final TenantScope tenantScope,
      final CorrelationId correlationId,
      final Duration remaining) {
    Preconditions.requireNonNull(pluginId, "pluginId");
    Preconditions.requireNonNull(request, "request");
    Preconditions.requireNonNull(tenantScope, "tenantScope");
    Preconditions.requireNonNull(correlationId, "correlationId");

    final RegisteredPlugin registered = registry.binding(pluginId);
    if (registered == null || !registered.state().admitsExecution()) {
      return Admission.refused(PluginFailureKind.PLUGIN_BUG);
    }
    if (!(registered.instance() instanceof ToolPlugin)) {
      return Admission.refused(PluginFailureKind.PLUGIN_BUG);
    }
    final PluginManifest manifest = registered.manifest();
    if (manifest.tool(request.toolName()) == null) {
      // Only tools the signed manifest declared. A plugin cannot serve a tool nobody vetted, even
      // if
      // its code would happily answer to the name.
      return Admission.refused(PluginFailureKind.PERMISSION);
    }
    if (!manifest.requiredCapabilities().has(PluginCapabilities.TOOL_EXECUTION)) {
      return Admission.refused(PluginFailureKind.PERMISSION);
    }
    if (!authorization.authorize(tenantScope, registered.describe()).permitted()) {
      return Admission.refused(PluginFailureKind.PERMISSION);
    }

    final PluginCapabilities granted = PermissionEvaluator.granted(manifest);
    final ResourceBudget budget =
        remaining == null || remaining.isZero() || remaining.isNegative()
            ? manifest.resources()
            : manifest.resources().clampedTo(remaining);

    final ToolContext context =
        new ToolContext(
            correlationId,
            // Tenant isolation: the scope is built from this invocation's tenant and never cached,
            // so
            // there is nothing for a later invocation to inherit (Doc 28 §36.1, AD-021). A plugin
            // without the capability gets a redacted scope rather than the real one.
            granted.has(PluginCapabilities.READ_TENANT_SCOPE) ? tenantScope : REDACTED_SCOPE,
            clock.now().plus(budget.wallClock()),
            granted,
            manifest.configuration());

    return Admission.admitted(registered, new ToolInvocation(pluginId, request, context, budget));
  }

  /** Turns a sandbox outcome into a result, recording cost, telemetry and audit on every path. */
  private ToolExecutionResult settle(
      final PluginId pluginId,
      final String toolName,
      final TenantScope tenantScope,
      final SandboxOutcome<ToolResponse> outcome) {

    final Duration elapsed = outcome.usage().wallClock();
    final PluginInvocationCost cost =
        recordCost(pluginId, toolName, tenantScope, outcome.usage(), elapsed);

    if (outcome instanceof SandboxOutcome.Completed<ToolResponse> completed) {
      if (completed.value() == null) {
        // A tool that returned null did not produce a response. Treating null as an empty success
        // would hand the caller a fabricated answer.
        reportFailure(
            pluginId, toolName, tenantScope, PluginFailureKind.PLUGIN_BUG, outcome, elapsed);
        return new ToolExecutionResult.Isolated(ToolError.of(PluginFailureKind.PLUGIN_BUG), cost);
      }
      telemetry.invocation(pluginId, toolName, PluginTelemetryPort.Outcome.COMPLETED, elapsed);
      recordAudit(pluginId, toolName, tenantScope, "tool_finished", "completed");
      return new ToolExecutionResult.Completed(completed.value(), cost);
    }
    if (outcome instanceof SandboxOutcome.Cancelled<ToolResponse>) {
      telemetry.invocation(pluginId, toolName, PluginTelemetryPort.Outcome.CANCELLED, elapsed);
      recordAudit(pluginId, toolName, tenantScope, "tool_cancelled", "cancelled");
      return new ToolExecutionResult.Cancelled(cost);
    }
    final PluginFailureKind kind = kindOf(outcome);
    reportFailure(pluginId, toolName, tenantScope, kind, outcome, elapsed);
    return new ToolExecutionResult.Isolated(ToolError.of(kind), cost);
  }

  private ToolExecutionResult isolate(
      final PluginId pluginId,
      final String toolName,
      final TenantScope tenantScope,
      final PluginFailureKind kind,
      final Duration elapsed,
      final long cpuMillis,
      final long memoryBytes) {
    final PluginInvocationCost cost =
        new PluginInvocationCost(
            pluginId, toolName, tenantScope, elapsed, cpuMillis, memoryBytes, toolCostMicros, 0L);
    emitCost(cost);
    telemetry.isolated(pluginId, kind);
    telemetry.invocation(pluginId, toolName, PluginTelemetryPort.Outcome.ISOLATED, elapsed);
    recordAudit(pluginId, toolName, tenantScope, "tool_finished", "isolated-" + label(kind));
    return new ToolExecutionResult.Isolated(ToolError.of(kind), cost);
  }

  private void reportFailure(
      final PluginId pluginId,
      final String toolName,
      final TenantScope tenantScope,
      final PluginFailureKind kind,
      final SandboxOutcome<?> outcome,
      final Duration elapsed) {
    telemetry.isolated(pluginId, kind);
    telemetry.invocation(pluginId, toolName, PluginTelemetryPort.Outcome.ISOLATED, elapsed);
    if (outcome instanceof SandboxOutcome.Breached<?>) {
      telemetry.budgetBreach(pluginId, resourceOf(kind));
    }
    recordAudit(pluginId, toolName, tenantScope, "tool_finished", "isolated-" + label(kind));
  }

  private PluginInvocationCost recordCost(
      final PluginId pluginId,
      final String toolName,
      final TenantScope tenantScope,
      final SandboxOutcome.ResourceUsage usage,
      final Duration elapsed) {
    final PluginInvocationCost cost =
        new PluginInvocationCost(
            pluginId,
            toolName,
            tenantScope,
            elapsed,
            usage.cpuMillisEstimate(),
            usage.memoryBytesEstimate(),
            toolCostMicros,
            0L);
    emitCost(cost);
    return cost;
  }

  private void emitCost(final PluginInvocationCost cost) {
    try {
      costs.record(cost);
    } catch (final RuntimeException sinkFailure) {
      // A cost sink must not be able to fail the invocation it is accounting for (Doc 27 OT-A1).
    }
  }

  private void recordAudit(
      final PluginId pluginId,
      final String toolName,
      final TenantScope tenantScope,
      final String action,
      final String outcome) {
    try {
      audit.record(
          new AuditRecord(
              new CorrelationId("plugin-tool"),
              null,
              action,
              pluginId.value() + "/" + toolName,
              outcome,
              tenantScope,
              clock.now()));
    } catch (final RuntimeException auditFailure) {
      // Audit is a side effect, never a gate.
    }
  }

  private static PluginStream refusedStream(final PluginFailureKind kind) {
    final BoundedPluginStream stream = new BoundedPluginStream(1, () -> {});
    stream.emit(new PluginEvent.Failed(ToolError.of(kind)));
    return stream;
  }

  private static PluginFailureKind kindOf(final SandboxOutcome<?> outcome) {
    if (outcome instanceof SandboxOutcome.Breached<?> breached) {
      return breached.kind();
    }
    if (outcome instanceof SandboxOutcome.Threw<?> threw) {
      return threw.kind();
    }
    if (outcome instanceof SandboxOutcome.Cancelled<?>) {
      return PluginFailureKind.CANCELLED;
    }
    return PluginFailureKind.UNKNOWN;
  }

  private static PluginTelemetryPort.Resource resourceOf(final PluginFailureKind kind) {
    return switch (kind) {
      case TIMEOUT -> PluginTelemetryPort.Resource.WALL_CLOCK;
      case RESOURCE_EXCEEDED -> PluginTelemetryPort.Resource.MEMORY;
      case NETWORK -> PluginTelemetryPort.Resource.IO;
      default -> PluginTelemetryPort.Resource.CPU;
    };
  }

  private static String label(final PluginFailureKind kind) {
    return kind.name().toLowerCase(Locale.ROOT);
  }

  /** The result of the admission gate: either a refusal reason or an admitted invocation. */
  private record Admission(
      RegisteredPlugin plugin, ToolInvocation invocation, PluginFailureKind refusal) {

    static Admission refused(final PluginFailureKind kind) {
      return new Admission(null, null, kind);
    }

    static Admission admitted(final RegisteredPlugin plugin, final ToolInvocation invocation) {
      return new Admission(plugin, invocation, null);
    }
  }
}

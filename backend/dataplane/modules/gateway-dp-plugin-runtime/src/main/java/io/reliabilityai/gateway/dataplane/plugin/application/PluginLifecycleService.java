package io.reliabilityai.gateway.dataplane.plugin.application;

import io.reliabilityai.gateway.canonical.audit.AuditRecord;
import io.reliabilityai.gateway.canonical.identity.CorrelationId;
import io.reliabilityai.gateway.canonical.identity.TenantScope;
import io.reliabilityai.gateway.common.Preconditions;
import io.reliabilityai.gateway.dataplane.plugin.api.PluginAuditSinkPort;
import io.reliabilityai.gateway.dataplane.plugin.api.PluginHealth;
import io.reliabilityai.gateway.dataplane.plugin.api.PluginId;
import io.reliabilityai.gateway.dataplane.plugin.api.PluginState;
import io.reliabilityai.gateway.dataplane.plugin.api.PluginTelemetryPort;
import io.reliabilityai.gateway.dataplane.plugin.api.SandboxHostPort;
import io.reliabilityai.gateway.dataplane.plugin.api.SandboxInvocation;
import io.reliabilityai.gateway.dataplane.plugin.api.SandboxOutcome;
import io.reliabilityai.gateway.dataplane.plugin.api.ToolContext;
import io.reliabilityai.gateway.dataplane.plugin.domain.PermissionEvaluator;
import io.reliabilityai.gateway.dataplane.plugin.internal.RegisteredPlugin;
import io.reliabilityai.gateway.ports.ClockPort;
import java.util.ArrayList;
import java.util.List;

/**
 * Starts, stops, enables, disables, reloads and restarts plugins in dependency order (Doc 28 §D,
 * §HRC).
 *
 * <p><b>Order is not cosmetic.</b> Startup follows the dependency graph so a plugin is never asked
 * to start before something it declared it needs; shutdown is the exact reverse so nothing is torn
 * out from under a plugin still running. Both orders are deterministic (Doc 28 POC-1), so two nodes
 * with identical config bring their plugins up in identical sequence.
 *
 * <p><b>Start hooks are sandboxed too.</b> A plugin's {@code start} runs inside the substrate under
 * the same budget as an invocation. Without that, a plugin whose start hook hangs would hang node
 * startup — the plugin runtime would have become a way to prevent the gateway from booting, which
 * is a worse failure than any plugin misbehaviour it was built to contain.
 *
 * <p><b>Hot reload does not mutate code.</b> Doc 28 HRC-2 forbids changing a loaded plugin's
 * behavior in place, so {@link #restart} stops and restarts the same pinned instance, and swapping
 * to a new version goes through {@code PluginRegistryService.reload}, which verifies the new
 * snapshot before it disturbs the incumbent.
 */
public final class PluginLifecycleService {

  private final PluginRegistryService registry;
  private final SandboxHostPort sandbox;
  private final PluginAuditSinkPort audit;
  private final PluginTelemetryPort telemetry;
  private final ClockPort clock;
  private final TenantScope systemScope;

  /**
   * Creates the lifecycle service.
   *
   * @param registry the binding table
   * @param sandbox the substrate that contains start and stop hooks
   * @param audit the content-free audit sink
   * @param telemetry the content-free telemetry sink
   * @param clock the injected clock
   * @param systemScope the tenant scope node-level lifecycle actions are attributed to
   */
  public PluginLifecycleService(
      final PluginRegistryService registry,
      final SandboxHostPort sandbox,
      final PluginAuditSinkPort audit,
      final PluginTelemetryPort telemetry,
      final ClockPort clock,
      final TenantScope systemScope) {
    this.registry = Preconditions.requireNonNull(registry, "registry");
    this.sandbox = Preconditions.requireNonNull(sandbox, "sandbox");
    this.audit = Preconditions.requireNonNull(audit, "audit");
    this.telemetry = Preconditions.requireNonNull(telemetry, "telemetry");
    this.clock = Preconditions.requireNonNull(clock, "clock");
    this.systemScope = Preconditions.requireNonNull(systemScope, "systemScope");
  }

  /**
   * Starts every registered plugin in dependency order.
   *
   * <p>A plugin whose start fails is marked FAILED and startup continues. Aborting the whole
   * sequence would let one bad plugin deny the node every other plugin it was configured with — the
   * opposite of the isolation Doc 28 §EPFC asks for.
   *
   * @return the plugins that reached READY, in the order they were started
   */
  public List<PluginId> startAll() {
    final List<PluginId> order = registry.dependencyGraph().startOrder();
    final List<PluginId> started = new ArrayList<>();
    for (final PluginId pluginId : order) {
      if (start(pluginId)) {
        started.add(pluginId);
      }
    }
    return List.copyOf(started);
  }

  /**
   * Stops every running plugin in reverse dependency order.
   *
   * @return the plugins stopped, in the order they were stopped
   */
  public List<PluginId> stopAll() {
    final List<PluginId> order = registry.dependencyGraph().stopOrder();
    final List<PluginId> stopped = new ArrayList<>();
    for (final PluginId pluginId : order) {
      if (stop(pluginId)) {
        stopped.add(pluginId);
      }
    }
    return List.copyOf(stopped);
  }

  /**
   * Starts one plugin, running its start hook inside the substrate.
   *
   * @param pluginId the plugin
   * @return true if the plugin reached READY
   */
  public boolean start(final PluginId pluginId) {
    Preconditions.requireNonNull(pluginId, "pluginId");
    final RegisteredPlugin registered = registry.binding(pluginId);
    if (registered == null) {
      return false;
    }
    final PluginState from = registered.state();
    if (from != PluginState.REGISTERED && from != PluginState.STOPPED) {
      return false;
    }
    if (!registered.transition(from, PluginState.INITIALIZING)) {
      return false; // another thread got there first
    }
    telemetry.lifecycle(pluginId, from, PluginState.INITIALIZING);

    final ToolContext context =
        new ToolContext(
            new CorrelationId("plugin-start-" + pluginId.value()),
            systemScope,
            clock.now().plus(registered.manifest().resources().wallClock()),
            PermissionEvaluator.granted(registered.manifest()),
            registered.manifest().configuration());

    final SandboxOutcome<Boolean> outcome =
        sandbox.run(
            new SandboxInvocation(
                pluginId,
                context.correlationId(),
                registered.manifest().resources(),
                context.deadline()),
            () -> {
              registered.instance().start(context);
              return Boolean.TRUE;
            });

    if (!(outcome instanceof SandboxOutcome.Completed<Boolean>)) {
      registered.markFailed();
      telemetry.lifecycle(pluginId, PluginState.INITIALIZING, PluginState.FAILED);
      record(pluginId, "plugin_failed", "start-" + outcomeLabel(outcome));
      return false;
    }
    registered.transition(PluginState.INITIALIZING, PluginState.READY);
    registered.observeHealth(observeHealth(registered));
    telemetry.lifecycle(pluginId, PluginState.INITIALIZING, PluginState.READY);
    record(pluginId, "plugin_started", "ready");
    return true;
  }

  /**
   * Stops one plugin, running its stop hook inside the substrate.
   *
   * @param pluginId the plugin
   * @return true if the plugin was running and is now stopped
   */
  public boolean stop(final PluginId pluginId) {
    Preconditions.requireNonNull(pluginId, "pluginId");
    final RegisteredPlugin registered = registry.binding(pluginId);
    if (registered == null) {
      return false;
    }
    final PluginState from = registered.state();
    if (from != PluginState.READY && from != PluginState.INITIALIZING) {
      return false;
    }
    if (!registered.transition(from, PluginState.STOPPING)) {
      return false;
    }
    telemetry.lifecycle(pluginId, from, PluginState.STOPPING);

    final SandboxOutcome<Boolean> outcome =
        sandbox.run(
            new SandboxInvocation(
                pluginId,
                new CorrelationId("plugin-stop-" + pluginId.value()),
                registered.manifest().resources(),
                clock.now().plus(registered.manifest().resources().wallClock())),
            () -> {
              registered.instance().stop();
              return Boolean.TRUE;
            });

    if (outcome instanceof SandboxOutcome.Completed<Boolean>) {
      registered.transition(PluginState.STOPPING, PluginState.STOPPED);
      telemetry.lifecycle(pluginId, PluginState.STOPPING, PluginState.STOPPED);
      record(pluginId, "plugin_unloaded", "stopped");
    } else {
      // A stop hook that hangs or throws leaves the plugin FAILED rather than STOPPED. It is out of
      // service either way; the distinction tells the operator the plugin did not shut down
      // cleanly.
      registered.markFailed();
      telemetry.lifecycle(pluginId, PluginState.STOPPING, PluginState.FAILED);
      record(pluginId, "plugin_failed", "stop-" + outcomeLabel(outcome));
    }
    return true;
  }

  /**
   * Stops and starts a plugin without changing its version (Doc 28 HRC-2).
   *
   * <p>The same pinned instance comes back up. Nothing about the plugin's code changes, which is
   * the distinction between a restart and an upgrade — an upgrade is a new verified snapshot and
   * goes through the registry's reload.
   *
   * @param pluginId the plugin
   * @return true if the plugin is READY afterwards
   */
  public boolean restart(final PluginId pluginId) {
    stop(pluginId);
    return start(pluginId);
  }

  /**
   * Disables a plugin through the governed config toggle (Doc 28 HRC-5).
   *
   * <p>A disabled plugin is stopped and stays bound. It disappears from every extension point's
   * dispatch list immediately, because {@code list} filters on live state.
   *
   * @param pluginId the plugin
   * @return true if the plugin is now DISABLED
   */
  public boolean disable(final PluginId pluginId) {
    Preconditions.requireNonNull(pluginId, "pluginId");
    final RegisteredPlugin registered = registry.binding(pluginId);
    if (registered == null) {
      return false;
    }
    stop(pluginId);
    final PluginState from = registered.state();
    if (from == PluginState.DISABLED) {
      return true;
    }
    if (!registered.transition(from, PluginState.DISABLED)) {
      return false;
    }
    telemetry.lifecycle(pluginId, from, PluginState.DISABLED);
    record(pluginId, "plugin_unloaded", "disabled");
    return true;
  }

  /**
   * Re-enables a disabled plugin and starts it.
   *
   * <p>It returns to REGISTERED first and then takes the ordinary start path. Jumping straight to
   * READY would skip the start hook, leaving a plugin serving traffic without having initialized.
   *
   * @param pluginId the plugin
   * @return true if the plugin is READY afterwards
   */
  public boolean enable(final PluginId pluginId) {
    Preconditions.requireNonNull(pluginId, "pluginId");
    final RegisteredPlugin registered = registry.binding(pluginId);
    if (registered == null || registered.state() != PluginState.DISABLED) {
      return false;
    }
    if (!registered.transition(PluginState.DISABLED, PluginState.REGISTERED)) {
      return false;
    }
    telemetry.lifecycle(pluginId, PluginState.DISABLED, PluginState.REGISTERED);
    return start(pluginId);
  }

  /**
   * Probes a plugin's health out of band and records the verdict (Doc 28 §J).
   *
   * <p>Advisory: the verdict is recorded on the descriptor and never gates dispatch.
   *
   * @param pluginId the plugin
   * @return the observed health
   */
  public PluginHealth probeHealth(final PluginId pluginId) {
    Preconditions.requireNonNull(pluginId, "pluginId");
    final RegisteredPlugin registered = registry.binding(pluginId);
    if (registered == null) {
      return PluginHealth.UNKNOWN;
    }
    final PluginHealth observed = observeHealth(registered);
    registered.observeHealth(observed);
    return observed;
  }

  /**
   * Runs the plugin's health hook inside the substrate under the probe timeout.
   *
   * <p>A probe that hangs or throws yields FAILED rather than propagating: a health check must not
   * be able to fail the thing it is checking on, and a hung probe answering "unknown forever" is
   * less useful to an operator than an explicit failure.
   */
  private PluginHealth observeHealth(final RegisteredPlugin registered) {
    final SandboxOutcome<PluginHealth> outcome =
        sandbox.run(
            new SandboxInvocation(
                registered.id(),
                new CorrelationId("plugin-health-" + registered.id().value()),
                registered
                    .manifest()
                    .resources()
                    .clampedTo(registered.manifest().health().probeTimeout()),
                clock.now().plus(registered.manifest().health().probeTimeout())),
            () -> registered.instance().health());
    if (outcome instanceof SandboxOutcome.Completed<PluginHealth> completed) {
      return completed.value() == null ? PluginHealth.UNKNOWN : completed.value();
    }
    return PluginHealth.FAILED;
  }

  private static String outcomeLabel(final SandboxOutcome<?> outcome) {
    if (outcome instanceof SandboxOutcome.Breached<?> breached) {
      return breached.kind().name().toLowerCase(java.util.Locale.ROOT);
    }
    if (outcome instanceof SandboxOutcome.Threw<?> threw) {
      return threw.kind().name().toLowerCase(java.util.Locale.ROOT);
    }
    return "cancelled";
  }

  private void record(final PluginId pluginId, final String action, final String outcome) {
    try {
      audit.record(
          new AuditRecord(
              new CorrelationId("plugin-lifecycle"),
              null,
              action,
              pluginId.value(),
              outcome,
              systemScope,
              clock.now()));
    } catch (final RuntimeException auditFailure) {
      // Audit is a side effect, never a gate (Doc 27 OT-A1).
    }
  }
}

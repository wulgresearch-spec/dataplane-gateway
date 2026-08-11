package io.reliabilityai.gateway.dataplane.app.binding;

import io.reliabilityai.gateway.canonical.context.TenantContext;
import io.reliabilityai.gateway.canonical.identity.CorrelationId;
import io.reliabilityai.gateway.canonical.plugin.ExtensionPoint;
import io.reliabilityai.gateway.canonical.plugin.PluginResult;
import io.reliabilityai.gateway.common.Preconditions;
import io.reliabilityai.gateway.dataplane.plugin.api.PluginId;
import io.reliabilityai.gateway.dataplane.plugin.application.PluginRuntimeService;
import io.reliabilityai.gateway.ports.ClockPort;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Runs the plugins bound at one frozen extension point, on behalf of the request pipeline.
 *
 * <p>This is the seam that was missing. The Plugin Runtime was fully built, sandboxed,
 * quota-enforced and tested, and then never called: {@code RequestPipeline} contained no reference
 * to it, so every plugin on every node was dead code. Doc 28 §2 says the runtime "applies to every
 * request, at each frozen extension point where a vetted plugin is bound" — this class is what
 * makes that true.
 *
 * <p><b>Additive by construction.</b> It returns an {@link ExtensionOutcome} that no mandatory
 * stage reads. Doc 28 PRT-D1 requires that removing every plugin change no mandatory-stage outcome,
 * and the cheapest way to guarantee that is for the contributions to have nowhere to go. A stage
 * that wants to consume a signal changes its own semantics to do so, under its own review.
 *
 * <p><b>It cannot throw and it cannot hang the request.</b> A plugin that crashes is already
 * isolated by the runtime; a runtime that itself throws is caught here; and the per-point budget is
 * clamped to the request's own deadline, so plugins can never extend a request beyond the deadline
 * the caller was promised. All three failure directions end in an empty outcome and a pipeline that
 * proceeds unweakened (Doc 28 §EPFC).
 *
 * <p><b>An unwired node behaves identically to a node with no plugins bound.</b> Both produce
 * {@link ExtensionOutcome#none}. If they differed, the mere presence of the plugin runtime would be
 * observable from the request path, which is the first step toward it mattering.
 */
public final class ExtensionPointDispatcher {

  /** A dispatcher for a node running no plugin runtime; every point contributes nothing. */
  public static final ExtensionPointDispatcher DISABLED = new ExtensionPointDispatcher();

  private final PluginRuntimeService runtime;
  private final ClockPort clock;
  private final Duration pointBudget;

  private ExtensionPointDispatcher() {
    this.runtime = null;
    this.clock = null;
    this.pointBudget = Duration.ZERO;
  }

  /**
   * Creates a dispatcher over a live plugin runtime.
   *
   * @param runtime the plugin runtime that owns sandboxing, ordering and isolation
   * @param clock the injected clock — the only source of time
   * @param pointBudget the wall-clock budget for all plugins at one point, clamped to the request
   *     deadline
   */
  public ExtensionPointDispatcher(
      final PluginRuntimeService runtime, final ClockPort clock, final Duration pointBudget) {
    this.runtime = Preconditions.requireNonNull(runtime, "runtime");
    this.clock = Preconditions.requireNonNull(clock, "clock");
    Preconditions.requireNonNull(pointBudget, "pointBudget");
    if (pointBudget.isNegative() || pointBudget.isZero()) {
      throw new IllegalArgumentException("pointBudget must be positive");
    }
    this.pointBudget = pointBudget;
  }

  /**
   * Runs every plugin bound at a point and collects what they contributed.
   *
   * @param point the frozen extension point
   * @param correlationId the request correlation id
   * @param tenant the resolved tenant scope
   * @param requestDeadline the deadline the caller was promised
   * @param signals content-free signals the owning stage publishes to every plugin
   * @return what the plugins contributed — never null, and this method never throws
   */
  public ExtensionOutcome dispatch(
      final ExtensionPoint point,
      final CorrelationId correlationId,
      final TenantContext tenant,
      final Instant requestDeadline,
      final Map<String, String> signals) {
    if (runtime == null || point == null || correlationId == null || tenant == null) {
      return ExtensionOutcome.none(point == null ? ExtensionPoint.TELEMETRY : point);
    }
    try {
      final Instant deadline = pointDeadline(requestDeadline);
      if (deadline == null) {
        // The request is already past its deadline. Running plugins now would spend budget the
        // caller
        // no longer has, so the point contributes nothing rather than borrowing time.
        return ExtensionOutcome.none(point);
      }
      final Map<PluginId, PluginResult> results =
          runtime.invokeAll(
              point,
              correlationId,
              tenant.tenantScope(),
              deadline,
              signals == null ? Map.of() : signals);
      return collect(point, results);
    } catch (final RuntimeException dispatchFailure) {
      // The runtime isolates plugin failures itself; reaching here means the runtime failed. The
      // pipeline still owes the caller a response, so the point contributes nothing.
      return ExtensionOutcome.none(point);
    }
  }

  /**
   * The deadline for this point: now plus the budget, never past the request's own deadline.
   *
   * @return the deadline, or null when the request deadline has already passed
   */
  private Instant pointDeadline(final Instant requestDeadline) {
    final Instant now = clock.now();
    if (now == null) {
      return null;
    }
    final Instant budgeted = now.plus(pointBudget);
    if (requestDeadline == null) {
      return budgeted;
    }
    if (!requestDeadline.isAfter(now)) {
      return null;
    }
    return budgeted.isBefore(requestDeadline) ? budgeted : requestDeadline;
  }

  private static ExtensionOutcome collect(
      final ExtensionPoint point, final Map<PluginId, PluginResult> results) {
    if (results == null || results.isEmpty()) {
      return ExtensionOutcome.none(point);
    }
    final Map<String, Map<String, String>> contributions = new LinkedHashMap<>();
    final List<String> isolated = new ArrayList<>();
    for (final Map.Entry<PluginId, PluginResult> entry : results.entrySet()) {
      final String pluginId = entry.getKey().value();
      if (entry.getValue() instanceof PluginResult.Completed completed) {
        contributions.put(pluginId, completed.contribution());
      } else {
        isolated.add(pluginId);
      }
    }
    return new ExtensionOutcome(point, contributions, isolated);
  }
}

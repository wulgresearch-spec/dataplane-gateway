package io.reliabilityai.gateway.dataplane.plugin;

import static org.assertj.core.api.Assertions.assertThat;

import io.reliabilityai.gateway.canonical.identity.CorrelationId;
import io.reliabilityai.gateway.canonical.plugin.ExtensionPoint;
import io.reliabilityai.gateway.canonical.plugin.PluginContext;
import io.reliabilityai.gateway.canonical.plugin.PluginResult;
import io.reliabilityai.gateway.dataplane.plugin.api.PluginAuditSinkPort;
import io.reliabilityai.gateway.dataplane.plugin.api.PluginAuthorizationPort;
import io.reliabilityai.gateway.dataplane.plugin.api.PluginCapabilities;
import io.reliabilityai.gateway.dataplane.plugin.api.PluginCostSinkPort;
import io.reliabilityai.gateway.dataplane.plugin.api.PluginId;
import io.reliabilityai.gateway.dataplane.plugin.api.PluginManifest;
import io.reliabilityai.gateway.dataplane.plugin.api.PluginTelemetryPort;
import io.reliabilityai.gateway.dataplane.plugin.application.PluginLifecycleService;
import io.reliabilityai.gateway.dataplane.plugin.application.PluginRegistryService;
import io.reliabilityai.gateway.dataplane.plugin.application.PluginRuntimeService;
import io.reliabilityai.gateway.dataplane.plugin.internal.InProcessSandbox;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Dispatch at the frozen five extension points (Doc 28 §EPC, §EPFC, §POC). */
@DisplayName("extension point dispatch")
class ExtensionPointDispatchTest {

  private final PluginFixture.TestClock clock = new PluginFixture.TestClock();
  private final InProcessSandbox sandbox = new InProcessSandbox(128);
  private final PluginRegistryService registry =
      new PluginRegistryService(
          PluginFixture.trustAll(),
          sandbox,
          PluginAuditSinkPort.NO_OP,
          PluginTelemetryPort.NO_OP,
          clock,
          64);
  private final PluginLifecycleService lifecycle =
      new PluginLifecycleService(
          registry,
          sandbox,
          PluginAuditSinkPort.NO_OP,
          PluginTelemetryPort.NO_OP,
          clock,
          PluginFixture.TENANT);

  private PluginRuntimeService runtime(final PluginAuthorizationPort authorization) {
    return new PluginRuntimeService(
        registry,
        sandbox,
        authorization,
        PluginAuditSinkPort.NO_OP,
        PluginTelemetryPort.NO_OP,
        PluginCostSinkPort.NO_OP,
        clock);
  }

  private TestPlugins.FixedExtension bind(
      final String id,
      final ExtensionPoint point,
      final int priority,
      final Map<String, String> contribution) {
    final PluginManifest manifest = PluginFixture.extensionManifest(id, point, priority);
    final TestPlugins.FixedExtension plugin =
        new TestPlugins.FixedExtension(manifest, contribution);
    registry.register(PluginFixture.snapshot(manifest), plugin);
    lifecycle.start(PluginId.of(id));
    return plugin;
  }

  private PluginContext contextFor(final String pluginId, final ExtensionPoint point) {
    return new PluginContext(
        pluginId,
        point,
        new CorrelationId("dispatch-1"),
        PluginFixture.TENANT,
        clock.now().plusSeconds(5));
  }

  @Test
  @DisplayName("a bound plugin contributes an advisory signal")
  void boundPluginContributes() {
    bind("classifier", ExtensionPoint.CLASSIFICATION, 0, Map.of("category", "billing"));

    final PluginResult result =
        runtime(PluginAuthorizationPort.PERMIT_ALL)
            .invokeAt(
                ExtensionPoint.CLASSIFICATION,
                contextFor("classifier", ExtensionPoint.CLASSIFICATION));

    assertThat(result).isInstanceOf(PluginResult.Completed.class);
    assertThat(((PluginResult.Completed) result).contribution())
        .containsEntry("category", "billing");
  }

  @Test
  @DisplayName("a plugin only runs at a point its vetted manifest declared")
  void pluginOnlyRunsAtItsDeclaredPoint() {
    final TestPlugins.FixedExtension plugin =
        bind("router-hint", ExtensionPoint.PRE_ROUTING, 0, Map.of("hint", "eu"));

    final PluginResult result =
        runtime(PluginAuthorizationPort.PERMIT_ALL)
            .invokeAt(
                ExtensionPoint.VALIDATION, contextFor("router-hint", ExtensionPoint.VALIDATION));

    // Doc 28 EPC-7: a plugin declaring one point cannot be dispatched at another.
    assertThat(result).isInstanceOf(PluginResult.Isolated.class);
    assertThat(plugin.invocations.get()).isZero();
  }

  @Test
  @DisplayName("a context naming a different point than the caller is refused")
  void mismatchedContextIsRefused() {
    bind("mismatch", ExtensionPoint.TELEMETRY, 0, Map.of());

    final PluginResult result =
        runtime(PluginAuthorizationPort.PERMIT_ALL)
            .invokeAt(
                ExtensionPoint.CLASSIFICATION, contextFor("mismatch", ExtensionPoint.TELEMETRY));

    assertThat(((PluginResult.Isolated) result).reason()).isEqualTo("extension-point-mismatch");
  }

  @Test
  @DisplayName("a crashing plugin is isolated and the stage proceeds unweakened")
  void crashingPluginIsIsolated() {
    final PluginManifest manifest =
        PluginFixture.extensionManifest("exploder", ExtensionPoint.VALIDATION, 0);
    registry.register(
        PluginFixture.snapshot(manifest), new TestPlugins.ThrowingExtension(manifest));
    lifecycle.start(PluginId.of("exploder"));

    final PluginResult result =
        runtime(PluginAuthorizationPort.PERMIT_ALL)
            .invokeAt(ExtensionPoint.VALIDATION, contextFor("exploder", ExtensionPoint.VALIDATION));

    // Doc 28 §EPFC: nothing propagates; the owning stage decides on its own inputs.
    assertThat(((PluginResult.Isolated) result).reason()).isEqualTo("plugin_bug");
  }

  @Test
  @DisplayName("an unregistered plugin is isolated, never thrown")
  void unregisteredPluginIsIsolated() {
    final PluginResult result =
        runtime(PluginAuthorizationPort.PERMIT_ALL)
            .invokeAt(ExtensionPoint.ATTRIBUTION, contextFor("ghost", ExtensionPoint.ATTRIBUTION));

    assertThat(((PluginResult.Isolated) result).reason()).isEqualTo("plugin-unavailable");
  }

  @Test
  @DisplayName("a null argument is isolated rather than propagating a null pointer")
  void nullArgumentsAreIsolated() {
    final PluginRuntimeService runtime = runtime(PluginAuthorizationPort.PERMIT_ALL);
    assertThat(runtime.invokeAt(null, null)).isInstanceOf(PluginResult.Isolated.class);
    assertThat(runtime.invokeAt(ExtensionPoint.TELEMETRY, null))
        .isInstanceOf(PluginResult.Isolated.class);
  }

  @Test
  @DisplayName("a denied plugin never runs")
  void deniedPluginNeverRuns() {
    final TestPlugins.FixedExtension plugin =
        bind("governed", ExtensionPoint.CLASSIFICATION, 0, Map.of("k", "v"));

    final PluginResult result =
        runtime(PluginAuthorizationPort.DENY_ALL)
            .invokeAt(
                ExtensionPoint.CLASSIFICATION,
                contextFor("governed", ExtensionPoint.CLASSIFICATION));

    assertThat(result).isInstanceOf(PluginResult.Isolated.class);
    assertThat(plugin.invocations.get()).isZero();
  }

  @Test
  @DisplayName("an expired deadline is isolated before the plugin is entered")
  void expiredDeadlineIsIsolated() {
    final TestPlugins.FixedExtension plugin = bind("late", ExtensionPoint.ATTRIBUTION, 0, Map.of());

    final PluginResult result =
        runtime(PluginAuthorizationPort.PERMIT_ALL)
            .invokeAt(
                ExtensionPoint.ATTRIBUTION,
                new PluginContext(
                    "late",
                    ExtensionPoint.ATTRIBUTION,
                    new CorrelationId("expired"),
                    PluginFixture.TENANT,
                    clock.now().minusSeconds(1)));

    assertThat(((PluginResult.Isolated) result).reason()).isEqualTo("timeout");
    assertThat(plugin.invocations.get()).isZero();
  }

  @Test
  @DisplayName("invokeAll runs every plugin at a point in priority-then-id order")
  void invokeAllRunsInDeterministicOrder() {
    final List<String> log = new CopyOnWriteArrayList<>();
    bindOrderRecorder("zulu", 5, log);
    bindOrderRecorder("alpha", 5, log);
    bindOrderRecorder("first", 1, log);

    runtime(PluginAuthorizationPort.PERMIT_ALL)
        .invokeAll(
            ExtensionPoint.PRE_ROUTING,
            new CorrelationId("order-1"),
            PluginFixture.TENANT,
            clock.now().plusSeconds(10),
            Map.of());

    assertThat(log).containsExactly("first", "alpha", "zulu");
  }

  @Test
  @DisplayName("the same plugin set produces the same order on every run")
  void orderIsStableAcrossRuns() {
    final List<String> log = new CopyOnWriteArrayList<>();
    bindOrderRecorder("delta", 2, log);
    bindOrderRecorder("charlie", 2, log);
    bindOrderRecorder("bravo", 2, log);

    final PluginRuntimeService runtime = runtime(PluginAuthorizationPort.PERMIT_ALL);
    final List<List<String>> runs = new ArrayList<>();
    for (int run = 0; run < 5; run++) {
      log.clear();
      runtime.invokeAll(
          ExtensionPoint.PRE_ROUTING,
          new CorrelationId("stable-" + run),
          PluginFixture.TENANT,
          clock.now().plusSeconds(10),
          Map.of());
      runs.add(List.copyOf(log));
    }

    // Doc 28 POC-1/POC-6: same inputs, same plugin set, same order — every time.
    assertThat(runs).allMatch(order -> order.equals(runs.get(0)));
    assertThat(runs.get(0)).containsExactly("bravo", "charlie", "delta");
  }

  @Test
  @DisplayName("executionOrder reports the order without executing anything")
  void executionOrderIsInspectableWithoutRunning() {
    final List<String> log = new CopyOnWriteArrayList<>();
    bindOrderRecorder("second", 2, log);
    bindOrderRecorder("primary", 1, log);

    assertThat(
            runtime(PluginAuthorizationPort.PERMIT_ALL).executionOrder(ExtensionPoint.PRE_ROUTING))
        .extracting(PluginId::value)
        .containsExactly("primary", "second");
    assertThat(log).isEmpty();
  }

  @Test
  @DisplayName("every plugin at a point receives the identical input")
  void everyPluginSeesTheSameInput() {
    final TestPlugins.FixedExtension left =
        bind("left", ExtensionPoint.CLASSIFICATION, 1, Map.of("side", "left"));
    final TestPlugins.FixedExtension right =
        bind("right", ExtensionPoint.CLASSIFICATION, 2, Map.of("side", "right"));

    runtime(PluginAuthorizationPort.PERMIT_ALL)
        .invokeAll(
            ExtensionPoint.CLASSIFICATION,
            new CorrelationId("shared-input"),
            PluginFixture.TENANT,
            clock.now().plusSeconds(10),
            Map.of("model", "gpt-x"));

    // Doc 28 POC-3: mutually isolated. Neither sees the other's contribution, so neither can be
    // made
    // to depend on running second.
    assertThat(left.lastRequest.get().signals()).isEqualTo(right.lastRequest.get().signals());
    assertThat(left.lastRequest.get().signals()).containsEntry("model", "gpt-x");
  }

  @Test
  @DisplayName("one plugin failing does not stop the others at the same point")
  void oneFailureDoesNotStopThePoint() {
    bind("good-1", ExtensionPoint.VALIDATION, 1, Map.of("ok", "1"));
    final PluginManifest broken =
        PluginFixture.extensionManifest("bad", ExtensionPoint.VALIDATION, 2);
    registry.register(PluginFixture.snapshot(broken), new TestPlugins.ThrowingExtension(broken));
    lifecycle.start(PluginId.of("bad"));
    bind("good-2", ExtensionPoint.VALIDATION, 3, Map.of("ok", "2"));

    final Map<PluginId, PluginResult> results =
        runtime(PluginAuthorizationPort.PERMIT_ALL)
            .invokeAll(
                ExtensionPoint.VALIDATION,
                new CorrelationId("mixed"),
                PluginFixture.TENANT,
                clock.now().plusSeconds(10),
                Map.of());

    assertThat(results.get(PluginId.of("good-1"))).isInstanceOf(PluginResult.Completed.class);
    assertThat(results.get(PluginId.of("bad"))).isInstanceOf(PluginResult.Isolated.class);
    assertThat(results.get(PluginId.of("good-2"))).isInstanceOf(PluginResult.Completed.class);
  }

  @Test
  @DisplayName("invokeAll at a point with no plugins returns nothing and does no work")
  void emptyPointReturnsNothing() {
    assertThat(
            runtime(PluginAuthorizationPort.PERMIT_ALL)
                .invokeAll(
                    ExtensionPoint.TELEMETRY,
                    new CorrelationId("empty"),
                    PluginFixture.TENANT,
                    clock.now().plusSeconds(10),
                    Map.of()))
        .isEmpty();
  }

  @Test
  @DisplayName("a contribution is copied out, so a plugin cannot change it after the stage read it")
  void contributionIsCopiedOut() {
    final Map<String, String> mutable = new java.util.HashMap<>();
    mutable.put("initial", "value");
    final PluginManifest manifest =
        PluginFixture.extensionManifest("mutator", ExtensionPoint.ATTRIBUTION, 0);
    registry.register(
        PluginFixture.snapshot(manifest),
        new TestPlugins.FixedExtension(manifest, mutable) {
          @Override
          public Map<String, String> contributeAt(
              final io.reliabilityai.gateway.dataplane.plugin.api.ExtensionRequest request) {
            return mutable;
          }
        });
    lifecycle.start(PluginId.of("mutator"));

    final PluginResult result =
        runtime(PluginAuthorizationPort.PERMIT_ALL)
            .invokeAt(
                ExtensionPoint.ATTRIBUTION, contextFor("mutator", ExtensionPoint.ATTRIBUTION));

    mutable.put("added-after", "surprise");
    assertThat(((PluginResult.Completed) result).contribution()).containsOnlyKeys("initial");
  }

  @Test
  @DisplayName("a stopped plugin disappears from dispatch without unbinding")
  void stoppedPluginIsSkipped() {
    final List<String> log = new CopyOnWriteArrayList<>();
    bindOrderRecorder("running", 1, log);
    bindOrderRecorder("halted", 2, log);
    lifecycle.stop(PluginId.of("halted"));

    runtime(PluginAuthorizationPort.PERMIT_ALL)
        .invokeAll(
            ExtensionPoint.PRE_ROUTING,
            new CorrelationId("skip"),
            PluginFixture.TENANT,
            clock.now().plusSeconds(10),
            Map.of());

    assertThat(log).containsExactly("running");
    assertThat(registry.lookup(PluginId.of("halted"))).isPresent();
  }

  @Test
  @DisplayName("the frozen extension point set is exactly the five Doc 28 permits")
  void extensionPointSetIsFrozen() {
    assertThat(ExtensionPoint.values())
        .containsExactly(
            ExtensionPoint.PRE_ROUTING,
            ExtensionPoint.CLASSIFICATION,
            ExtensionPoint.VALIDATION,
            ExtensionPoint.TELEMETRY,
            ExtensionPoint.ATTRIBUTION);
  }

  @Test
  @DisplayName("dispatch works at every one of the frozen five")
  void everyFrozenPointDispatches() {
    for (final ExtensionPoint point : ExtensionPoint.values()) {
      final String id = "at-" + point.name().toLowerCase(java.util.Locale.ROOT);
      bind(id, point, 0, Map.of("point", point.name()));
      final PluginResult result =
          runtime(PluginAuthorizationPort.PERMIT_ALL).invokeAt(point, contextFor(id, point));
      assertThat(result).as("point %s", point).isInstanceOf(PluginResult.Completed.class);
    }
  }

  @Test
  @DisplayName("a plugin overrunning its budget at a point is isolated as a timeout")
  void slowExtensionIsIsolated() {
    final PluginManifest manifest =
        PluginFixture.manifest(
            "sluggish",
            Set.of(ExtensionPoint.CLASSIFICATION),
            List.of(),
            PluginCapabilities.of(PluginCapabilities.READ_ROUTING_HINTS),
            0,
            List.of());
    registry.register(
        PluginFixture.snapshot(manifest),
        new TestPlugins.FixedExtension(manifest, Map.of()) {
          @Override
          public Map<String, String> contributeAt(
              final io.reliabilityai.gateway.dataplane.plugin.api.ExtensionRequest request)
              throws Exception {
            Thread.sleep(Duration.ofSeconds(30).toMillis());
            return Map.of();
          }
        });
    lifecycle.start(PluginId.of("sluggish"));

    final PluginResult result =
        runtime(PluginAuthorizationPort.PERMIT_ALL)
            .invokeAt(
                ExtensionPoint.CLASSIFICATION,
                new PluginContext(
                    "sluggish",
                    ExtensionPoint.CLASSIFICATION,
                    new CorrelationId("slow-point"),
                    PluginFixture.TENANT,
                    clock.now().plusMillis(150)));

    assertThat(((PluginResult.Isolated) result).reason()).isEqualTo("timeout");
  }

  private void bindOrderRecorder(final String id, final int priority, final List<String> log) {
    final PluginManifest manifest =
        PluginFixture.extensionManifest(id, ExtensionPoint.PRE_ROUTING, priority);
    registry.register(
        PluginFixture.snapshot(manifest), new TestPlugins.OrderRecordingExtension(manifest, log));
    lifecycle.start(PluginId.of(id));
  }
}

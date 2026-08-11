package io.reliabilityai.gateway.dataplane.plugin;

import static org.assertj.core.api.Assertions.assertThat;

import io.reliabilityai.gateway.canonical.plugin.ExtensionPoint;
import io.reliabilityai.gateway.dataplane.plugin.api.PluginAuditSinkPort;
import io.reliabilityai.gateway.dataplane.plugin.api.PluginCapabilities;
import io.reliabilityai.gateway.dataplane.plugin.api.PluginDependency;
import io.reliabilityai.gateway.dataplane.plugin.api.PluginHealth;
import io.reliabilityai.gateway.dataplane.plugin.api.PluginId;
import io.reliabilityai.gateway.dataplane.plugin.api.PluginManifest;
import io.reliabilityai.gateway.dataplane.plugin.api.PluginState;
import io.reliabilityai.gateway.dataplane.plugin.api.PluginTelemetryPort;
import io.reliabilityai.gateway.dataplane.plugin.api.PluginVersion;
import io.reliabilityai.gateway.dataplane.plugin.application.PluginLifecycleService;
import io.reliabilityai.gateway.dataplane.plugin.application.PluginRegistryService;
import io.reliabilityai.gateway.dataplane.plugin.internal.InProcessSandbox;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Start, stop, enable, disable, restart and health (Doc 28 §D, §HRC, §J). */
@DisplayName("plugin lifecycle service")
class PluginLifecycleServiceTest {

  private final PluginFixture.TestClock clock = new PluginFixture.TestClock();
  private final InProcessSandbox sandbox = new InProcessSandbox(64);
  private final PluginRegistryService registry =
      new PluginRegistryService(
          PluginFixture.trustAll(),
          sandbox,
          PluginAuditSinkPort.NO_OP,
          PluginTelemetryPort.NO_OP,
          clock,
          32);
  private final PluginLifecycleService lifecycle =
      new PluginLifecycleService(
          registry,
          sandbox,
          PluginAuditSinkPort.NO_OP,
          PluginTelemetryPort.NO_OP,
          clock,
          PluginFixture.TENANT);

  private void bindExtension(final String id, final List<PluginDependency> dependencies) {
    final PluginManifest manifest =
        PluginFixture.manifest(
            id,
            Set.of(ExtensionPoint.TELEMETRY),
            List.of(),
            PluginCapabilities.of(PluginCapabilities.EMIT_TELEMETRY),
            0,
            dependencies);
    registry.register(
        PluginFixture.snapshot(manifest), new TestPlugins.FixedExtension(manifest, Map.of()));
  }

  @Test
  @DisplayName("starting a plugin runs its start hook and reaches READY")
  void startReachesReady() {
    bindExtension("startable", List.of());
    assertThat(lifecycle.start(PluginId.of("startable"))).isTrue();
    assertThat(registry.lookup(PluginId.of("startable")).orElseThrow().state())
        .isEqualTo(PluginState.READY);
  }

  @Test
  @DisplayName("a plugin whose start hook throws lands in FAILED and is never dispatchable")
  void failingStartLandsInFailed() {
    final PluginManifest manifest = PluginFixture.toolManifest("unstartable");
    registry.register(PluginFixture.snapshot(manifest), new TestPlugins.UnstartableTool(manifest));

    assertThat(lifecycle.start(PluginId.of("unstartable"))).isFalse();
    assertThat(registry.lookup(PluginId.of("unstartable")).orElseThrow().state())
        .isEqualTo(PluginState.FAILED);
  }

  @Test
  @DisplayName("one plugin failing to start does not stop the others")
  void oneFailureDoesNotAbortStartup() {
    final PluginManifest broken = PluginFixture.toolManifest("broken");
    registry.register(PluginFixture.snapshot(broken), new TestPlugins.UnstartableTool(broken));
    bindExtension("healthy", List.of());

    final List<PluginId> started = lifecycle.startAll();
    assertThat(started).extracting(PluginId::value).containsExactly("healthy");
  }

  @Test
  @DisplayName("startAll follows dependency order")
  void startAllFollowsDependencyOrder() {
    bindExtension("library", List.of());
    bindExtension(
        "consumer",
        List.of(new PluginDependency(PluginId.of("library"), new PluginVersion(1, 0, 0))));

    assertThat(lifecycle.startAll())
        .extracting(PluginId::value)
        .containsExactly("library", "consumer");
  }

  @Test
  @DisplayName("stopAll is the exact reverse of startAll")
  void stopAllReversesStartAll() {
    bindExtension("base", List.of());
    bindExtension(
        "middle", List.of(new PluginDependency(PluginId.of("base"), new PluginVersion(1, 0, 0))));
    bindExtension(
        "top", List.of(new PluginDependency(PluginId.of("middle"), new PluginVersion(1, 0, 0))));
    lifecycle.startAll();

    assertThat(lifecycle.stopAll())
        .extracting(PluginId::value)
        .containsExactly("top", "middle", "base");
  }

  @Test
  @DisplayName("stopping a READY plugin reaches STOPPED")
  void stopReachesStopped() {
    bindExtension("stoppable", List.of());
    lifecycle.start(PluginId.of("stoppable"));

    assertThat(lifecycle.stop(PluginId.of("stoppable"))).isTrue();
    assertThat(registry.lookup(PluginId.of("stoppable")).orElseThrow().state())
        .isEqualTo(PluginState.STOPPED);
  }

  @Test
  @DisplayName("stopping an already stopped plugin is a no-op")
  void stopIsIdempotent() {
    bindExtension("idempotent", List.of());
    lifecycle.start(PluginId.of("idempotent"));
    lifecycle.stop(PluginId.of("idempotent"));

    assertThat(lifecycle.stop(PluginId.of("idempotent"))).isFalse();
  }

  @Test
  @DisplayName("a stopped plugin can start again")
  void stoppedPluginRestarts() {
    bindExtension("cyclable", List.of());
    lifecycle.start(PluginId.of("cyclable"));
    lifecycle.stop(PluginId.of("cyclable"));

    assertThat(lifecycle.start(PluginId.of("cyclable"))).isTrue();
    assertThat(registry.lookup(PluginId.of("cyclable")).orElseThrow().state())
        .isEqualTo(PluginState.READY);
  }

  @Test
  @DisplayName("restart brings the same pinned version back up")
  void restartKeepsTheSameGeneration() {
    bindExtension("restartable", List.of());
    lifecycle.start(PluginId.of("restartable"));

    assertThat(lifecycle.restart(PluginId.of("restartable"))).isTrue();
    // Doc 28 HRC-2: a restart is not an upgrade. The generation is unchanged because no new
    // snapshot
    // was verified.
    assertThat(registry.lookup(PluginId.of("restartable")).orElseThrow().generation())
        .isEqualTo(0L);
  }

  @Test
  @DisplayName("disabling stops the plugin and removes it from dispatch immediately")
  void disableRemovesFromDispatch() {
    bindExtension("disablable", List.of());
    lifecycle.start(PluginId.of("disablable"));
    assertThat(registry.list(ExtensionPoint.TELEMETRY)).hasSize(1);

    assertThat(lifecycle.disable(PluginId.of("disablable"))).isTrue();
    assertThat(registry.lookup(PluginId.of("disablable")).orElseThrow().state())
        .isEqualTo(PluginState.DISABLED);
    assertThat(registry.list(ExtensionPoint.TELEMETRY)).isEmpty();
  }

  @Test
  @DisplayName("disabling is idempotent")
  void disableIsIdempotent() {
    bindExtension("twice-disabled", List.of());
    lifecycle.start(PluginId.of("twice-disabled"));
    lifecycle.disable(PluginId.of("twice-disabled"));

    assertThat(lifecycle.disable(PluginId.of("twice-disabled"))).isTrue();
  }

  @Test
  @DisplayName("enabling runs the start hook again rather than jumping to READY")
  void enableRunsTheStartHook() {
    bindExtension("re-enabled", List.of());
    lifecycle.start(PluginId.of("re-enabled"));
    lifecycle.disable(PluginId.of("re-enabled"));

    assertThat(lifecycle.enable(PluginId.of("re-enabled"))).isTrue();
    assertThat(registry.lookup(PluginId.of("re-enabled")).orElseThrow().state())
        .isEqualTo(PluginState.READY);
    assertThat(registry.list(ExtensionPoint.TELEMETRY)).hasSize(1);
  }

  @Test
  @DisplayName("enabling a plugin that is not disabled is refused")
  void enableOnlyAppliesToDisabled() {
    bindExtension("already-running", List.of());
    lifecycle.start(PluginId.of("already-running"));

    assertThat(lifecycle.enable(PluginId.of("already-running"))).isFalse();
  }

  @Test
  @DisplayName("lifecycle commands for an unknown plugin are refused, not thrown")
  void unknownPluginIsRefusedQuietly() {
    assertThat(lifecycle.start(PluginId.of("ghost"))).isFalse();
    assertThat(lifecycle.stop(PluginId.of("ghost"))).isFalse();
    assertThat(lifecycle.disable(PluginId.of("ghost"))).isFalse();
    assertThat(lifecycle.enable(PluginId.of("ghost"))).isFalse();
    assertThat(lifecycle.probeHealth(PluginId.of("ghost"))).isEqualTo(PluginHealth.UNKNOWN);
  }

  @Test
  @DisplayName("a health probe records the plugin's verdict without gating dispatch")
  void healthIsObservedNotEnforced() {
    final PluginManifest manifest = PluginFixture.toolManifest("self-reporting");
    final TestPlugins.FunctionTool plugin =
        new TestPlugins.FunctionTool(manifest, invocation -> null);
    registry.register(PluginFixture.snapshot(manifest), plugin);
    lifecycle.start(PluginId.of("self-reporting"));

    assertThat(lifecycle.probeHealth(PluginId.of("self-reporting"))).isEqualTo(PluginHealth.READY);
    assertThat(registry.lookup(PluginId.of("self-reporting")).orElseThrow().health())
        .isEqualTo(PluginHealth.READY);
  }

  @Test
  @DisplayName("a plugin reporting FAILED health still dispatches, because health is advisory")
  void failedHealthDoesNotRemoveFromDispatch() {
    final PluginManifest manifest =
        PluginFixture.manifest(
            "sickly",
            Set.of(ExtensionPoint.TELEMETRY),
            List.of(),
            PluginCapabilities.of(PluginCapabilities.EMIT_TELEMETRY),
            0,
            List.of());
    registry.register(
        PluginFixture.snapshot(manifest),
        new TestPlugins.FixedExtension(manifest, Map.of()) {
          @Override
          public PluginHealth health() {
            return PluginHealth.FAILED;
          }
        });
    lifecycle.start(PluginId.of("sickly"));
    lifecycle.probeHealth(PluginId.of("sickly"));

    // A plugin could report anything about itself. The runtime routes on the state it owns.
    assertThat(registry.lookup(PluginId.of("sickly")).orElseThrow().health())
        .isEqualTo(PluginHealth.FAILED);
    assertThat(registry.list(ExtensionPoint.TELEMETRY))
        .extracting(descriptor -> descriptor.id().value())
        .contains("sickly");
  }

  @Test
  @DisplayName("a plugin that has not been probed reports UNKNOWN, never healthy")
  void unprobedHealthIsUnknown() {
    bindExtension("unprobed", List.of());
    assertThat(registry.lookup(PluginId.of("unprobed")).orElseThrow().health())
        .isEqualTo(PluginHealth.UNKNOWN);
  }

  @Test
  @DisplayName("unregistering a running plugin stops it first")
  void unregisterStopsFirst() {
    final PluginManifest manifest = PluginFixture.toolManifest("running");
    final TestPlugins.FunctionTool plugin =
        new TestPlugins.FunctionTool(manifest, invocation -> null);
    registry.register(PluginFixture.snapshot(manifest), plugin);
    lifecycle.start(PluginId.of("running"));

    assertThat(registry.unregister(PluginId.of("running"))).isTrue();
    assertThat(registry.lookup(PluginId.of("running"))).isEmpty();
  }
}

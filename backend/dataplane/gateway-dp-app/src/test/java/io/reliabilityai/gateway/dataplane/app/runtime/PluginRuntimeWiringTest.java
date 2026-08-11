package io.reliabilityai.gateway.dataplane.app.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import io.reliabilityai.gateway.canonical.identity.CorrelationId;
import io.reliabilityai.gateway.canonical.identity.TenantScope;
import io.reliabilityai.gateway.canonical.plugin.ExtensionPoint;
import io.reliabilityai.gateway.canonical.plugin.PluginContext;
import io.reliabilityai.gateway.canonical.plugin.PluginResult;
import io.reliabilityai.gateway.dataplane.plugin.api.ExtensionPlugin;
import io.reliabilityai.gateway.dataplane.plugin.api.ExtensionRequest;
import io.reliabilityai.gateway.dataplane.plugin.api.HealthPolicy;
import io.reliabilityai.gateway.dataplane.plugin.api.PluginAuthorizationPort;
import io.reliabilityai.gateway.dataplane.plugin.api.PluginCapabilities;
import io.reliabilityai.gateway.dataplane.plugin.api.PluginId;
import io.reliabilityai.gateway.dataplane.plugin.api.PluginManifest;
import io.reliabilityai.gateway.dataplane.plugin.api.PluginPermissions;
import io.reliabilityai.gateway.dataplane.plugin.api.PluginSignaturePort;
import io.reliabilityai.gateway.dataplane.plugin.api.PluginState;
import io.reliabilityai.gateway.dataplane.plugin.api.PluginType;
import io.reliabilityai.gateway.dataplane.plugin.api.PluginVersion;
import io.reliabilityai.gateway.dataplane.plugin.api.ResourceBudget;
import io.reliabilityai.gateway.dataplane.plugin.api.ToolContext;
import io.reliabilityai.gateway.dataplane.plugin.api.TrustTier;
import io.reliabilityai.gateway.dataplane.plugin.api.VettedPluginSnapshot;
import io.reliabilityai.gateway.dataplane.plugin.application.PluginLifecycleService;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The Plugin Runtime as assembled by the composition root (Doc 28, AD-020). */
@DisplayName("plugin runtime wiring")
class PluginRuntimeWiringTest {

  /** Manually advanced: nothing in startup or plugin dispatch may depend on wall-clock time. */
  private final RuntimeFixture.TestClock clock = new RuntimeFixture.TestClock();

  private static GatewayRuntimeConfig.PluginConfig pluginConfig(
      final PluginAuthorizationPort authorization) {
    return new GatewayRuntimeConfig.PluginConfig(
        snapshot -> PluginSignaturePort.Verdict.TRUSTED,
        authorization,
        Optional.empty(),
        TenantScope.of("system", "node"),
        16,
        32,
        50L,
        false);
  }

  private static PluginManifest manifest(final String id) {
    return new PluginManifest(
        PluginId.of(id),
        id,
        new PluginVersion(1, 0, 0),
        "acme",
        "a wired plugin",
        PluginType.INTERNAL,
        TrustTier.FIRST_PARTY,
        Set.of(ExtensionPoint.CLASSIFICATION),
        0,
        PluginPermissions.none(),
        PluginCapabilities.of(PluginCapabilities.READ_CLASSIFICATION),
        List.of(),
        new ResourceBudget(Duration.ofSeconds(2), 1_000L, 1_048_576L, 1_048_576L),
        Map.of(),
        new HealthPolicy(Duration.ofSeconds(30), Duration.ofSeconds(2), 3),
        List.of());
  }

  private static VettedPluginSnapshot snapshot(final PluginManifest manifest) {
    return new VettedPluginSnapshot(
        manifest,
        "artifact".getBytes(StandardCharsets.UTF_8),
        new byte[] {1},
        new byte[] {2},
        "vetted-by=c12");
  }

  /** A classification plugin that records whether it was stopped. Subclassed to refuse stopping. */
  private static class WiredPlugin implements ExtensionPlugin {
    private final PluginManifest manifest;
    final AtomicBoolean stopped = new AtomicBoolean();

    WiredPlugin(final PluginManifest manifest) {
      this.manifest = manifest;
    }

    @Override
    public PluginManifest manifest() {
      return manifest;
    }

    @Override
    public void start(final ToolContext context) {
      // nothing to initialize
    }

    @Override
    public void stop() {
      stopped.set(true);
    }

    @Override
    public Map<String, String> contributeAt(final ExtensionRequest request) {
      return Map.of("category", "support");
    }
  }

  private GatewayRuntime start(final Path directory, final PluginAuthorizationPort authorization)
      throws Exception {
    final Path wal = Files.createDirectories(directory.resolve("wal"));
    final Path dlq = directory.resolve("dlq.log");
    final GatewayRuntime runtime =
        new GatewayRuntime(
            RuntimeFixture.config(
                new byte[32],
                wal,
                dlq,
                record -> {},
                clock,
                RuntimeFixture.allExternalAdapters(),
                RuntimeFixture.governanceConfigured(),
                RuntimeFixture.providerConfigured(),
                Optional.empty(),
                Optional.empty(),
                Optional.of(pluginConfig(authorization))));
    runtime.start();
    return runtime;
  }

  @Test
  @DisplayName("a node without plugin config exposes no plugin runtime")
  void unconfiguredNodeRunsNoPlugins(@TempDir final Path directory) throws Exception {
    final Path wal = Files.createDirectories(directory.resolve("wal"));
    final GatewayRuntime runtime =
        new GatewayRuntime(
            RuntimeFixture.config(
                new byte[32],
                wal,
                directory.resolve("dlq.log"),
                record -> {},
                clock,
                RuntimeFixture.allExternalAdapters(),
                RuntimeFixture.governanceConfigured(),
                RuntimeFixture.providerConfigured(),
                Optional.empty(),
                Optional.empty()));
    runtime.start();
    try {
      // A Tier-0 host that was not configured for plugins runs none. That is the correct default.
      assertThat(runtime.pluginRegistry()).isEmpty();
      assertThat(runtime.pluginRuntime()).isEmpty();
      assertThat(runtime.toolExecution()).isEmpty();
    } finally {
      runtime.stop();
    }
  }

  @Test
  @DisplayName("a configured node exposes the registry, lifecycle, dispatch seam and tool executor")
  void configuredNodeExposesTheRuntime(@TempDir final Path directory) throws Exception {
    final GatewayRuntime runtime = start(directory, PluginAuthorizationPort.PERMIT_ALL);
    try {
      assertThat(runtime.pluginRegistry()).isPresent();
      assertThat(runtime.pluginLifecycle()).isPresent();
      assertThat(runtime.pluginRuntime()).isPresent();
      assertThat(runtime.toolExecution()).isPresent();
    } finally {
      runtime.stop();
    }
  }

  @Test
  @DisplayName("a plugin registered into the running node dispatches at its frozen point")
  void registeredPluginDispatches(@TempDir final Path directory) throws Exception {
    final GatewayRuntime runtime = start(directory, PluginAuthorizationPort.PERMIT_ALL);
    try {
      final PluginManifest manifest = manifest("wired-classifier");
      assertThat(
              runtime
                  .pluginRegistry()
                  .orElseThrow()
                  .register(snapshot(manifest), new WiredPlugin(manifest))
                  .bound())
          .isTrue();
      assertThat(runtime.pluginLifecycle().orElseThrow().start(PluginId.of("wired-classifier")))
          .isTrue();

      final PluginResult result =
          runtime
              .pluginRuntime()
              .orElseThrow()
              .invokeAt(
                  ExtensionPoint.CLASSIFICATION,
                  new PluginContext(
                      "wired-classifier",
                      ExtensionPoint.CLASSIFICATION,
                      new CorrelationId("wire-1"),
                      RuntimeFixture.TENANT,
                      clock.now().plusSeconds(5)));

      assertThat(((PluginResult.Completed) result).contribution())
          .containsEntry("category", "support");
    } finally {
      runtime.stop();
    }
  }

  @Test
  @DisplayName("the node's authorization gate governs plugin dispatch")
  void authorizationGateGovernsDispatch(@TempDir final Path directory) throws Exception {
    // DENY_ALL is the safe default for a node with no plugin policy wired: it runs no plugins
    // rather
    // than running all of them.
    final GatewayRuntime runtime = start(directory, PluginAuthorizationPort.DENY_ALL);
    try {
      final PluginManifest manifest = manifest("denied-classifier");
      runtime
          .pluginRegistry()
          .orElseThrow()
          .register(snapshot(manifest), new WiredPlugin(manifest));
      runtime.pluginLifecycle().orElseThrow().start(PluginId.of("denied-classifier"));

      final PluginResult result =
          runtime
              .pluginRuntime()
              .orElseThrow()
              .invokeAt(
                  ExtensionPoint.CLASSIFICATION,
                  new PluginContext(
                      "denied-classifier",
                      ExtensionPoint.CLASSIFICATION,
                      new CorrelationId("wire-2"),
                      RuntimeFixture.TENANT,
                      clock.now().plusSeconds(5)));

      assertThat(result).isInstanceOf(PluginResult.Isolated.class);
    } finally {
      runtime.stop();
    }
  }

  @Test
  @DisplayName("shutting the node down stops every running plugin")
  void shutdownStopsPlugins(@TempDir final Path directory) throws Exception {
    final GatewayRuntime runtime = start(directory, PluginAuthorizationPort.PERMIT_ALL);
    final PluginManifest manifest = manifest("stoppable-wired");
    final WiredPlugin plugin = new WiredPlugin(manifest);
    runtime.pluginRegistry().orElseThrow().register(snapshot(manifest), plugin);
    final PluginLifecycleService lifecycle = runtime.pluginLifecycle().orElseThrow();
    lifecycle.start(PluginId.of("stoppable-wired"));

    runtime.stop();

    // A plugin left running past shutdown would still be emitting audit and cost records into a
    // publisher that is closing.
    assertThat(plugin.stopped.get()).isTrue();
  }

  @Test
  @DisplayName("the node still reaches STOPPED when a plugin refuses to stop")
  void misbehavingPluginDoesNotBlockShutdown(@TempDir final Path directory) throws Exception {
    final GatewayRuntime runtime = start(directory, PluginAuthorizationPort.PERMIT_ALL);
    final PluginManifest manifest = manifest("obstinate");
    runtime
        .pluginRegistry()
        .orElseThrow()
        .register(
            snapshot(manifest),
            new WiredPlugin(manifest) {
              @Override
              public void stop() {
                throw new IllegalStateException("I refuse");
              }
            });
    runtime.pluginLifecycle().orElseThrow().start(PluginId.of("obstinate"));

    runtime.stop();

    // Shutdown must not be something a plugin can veto.
    assertThat(runtime.state()).isEqualTo(GatewayRuntime.State.STOPPED);
  }

  @Test
  @DisplayName("an operator can disable and re-enable a plugin without restarting the node")
  void hotDisableAndEnable(@TempDir final Path directory) throws Exception {
    final GatewayRuntime runtime = start(directory, PluginAuthorizationPort.PERMIT_ALL);
    try {
      final PluginManifest manifest = manifest("toggleable");
      runtime
          .pluginRegistry()
          .orElseThrow()
          .register(snapshot(manifest), new WiredPlugin(manifest));
      final PluginLifecycleService lifecycle = runtime.pluginLifecycle().orElseThrow();
      lifecycle.start(PluginId.of("toggleable"));

      assertThat(lifecycle.disable(PluginId.of("toggleable"))).isTrue();
      assertThat(
              runtime
                  .pluginRegistry()
                  .orElseThrow()
                  .lookup(PluginId.of("toggleable"))
                  .orElseThrow()
                  .state())
          .isEqualTo(PluginState.DISABLED);

      assertThat(lifecycle.enable(PluginId.of("toggleable"))).isTrue();
      assertThat(
              runtime
                  .pluginRegistry()
                  .orElseThrow()
                  .lookup(PluginId.of("toggleable"))
                  .orElseThrow()
                  .state())
          .isEqualTo(PluginState.READY);
      // Doc 28 HRC-5: a governed toggle, and the node kept serving throughout.
      assertThat(runtime.state()).isEqualTo(GatewayRuntime.State.READY);
    } finally {
      runtime.stop();
    }
  }

  @Test
  @DisplayName("the request pipeline is assembled without any plugin dependency")
  void pipelineDoesNotDependOnPlugins(@TempDir final Path directory) throws Exception {
    final GatewayRuntime runtime = start(directory, PluginAuthorizationPort.PERMIT_ALL);
    try {
      // Doc 28 PRT-D1: removing every plugin changes no mandatory-stage outcome. The pipeline is
      // constructed and usable with the plugin runtime present and empty.
      assertThat(runtime.pipeline()).isNotNull();
      assertThat(runtime.pluginRegistry().orElseThrow().snapshot()).isEmpty();
      assertThat(runtime.boundStages()).isNotEmpty();
    } finally {
      runtime.stop();
    }
  }
}
